// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.tellmemoreaboutcore.miner.core.Consent
import com.tellmemoreaboutcore.miner.core.CTR_POOLS
import com.tellmemoreaboutcore.miner.core.Ican
import com.tellmemoreaboutcore.miner.core.Job
import com.tellmemoreaboutcore.miner.core.LogEvent
import com.tellmemoreaboutcore.miner.core.LogKey
import com.tellmemoreaboutcore.miner.core.MinerEngine
import com.tellmemoreaboutcore.miner.core.NativeRandomY
import com.tellmemoreaboutcore.miner.core.PoolEndpoint
import com.tellmemoreaboutcore.miner.core.StratumClient
import com.tellmemoreaboutcore.miner.core.SUPPORT_WORKER
import com.tellmemoreaboutcore.miner.core.SupportShare
import com.tellmemoreaboutcore.miner.core.TMMAC_SUPPORT_ADDRESS
import com.tellmemoreaboutcore.miner.core.WorkerName
import java.util.Locale
import java.util.UUID

/**
 * Vordergrund-Dienst: haelt RandomY + Pool-Verbindung am Leben, solange der Nutzer
 * das Mining gestartet hat. Misst alle 10 s Hashrate und Temperatur und stoppt selbst
 * bei Hitze. Jeder Abbruch landet als [MiningState.Snapshot.notice] sichtbar in der App.
 */
class MiningService : Service() {

    // Werden aus Setup-, Rechen-, Pool- und Stopp-Thread gelesen → volatile; Uebergaben unter lock
    @Volatile private var engine: MinerEngine? = null
    @Volatile private var client: StratumClient? = null
    @Volatile private var support: SupportShare? = null
    /** Server der Nutzer-Verbindung; die Support-Verbindung nimmt denselben zuerst. */
    @Volatile private var userEndpoint: PoolEndpoint? = null
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var running = false
    @Volatile private var setupThread: Thread? = null
    @Volatile private var lastStartId = 0
    /** Nur Debug-Build: Support-Fenster 2 min statt 100 min, fuer Tests (adb shell run-as … touch files/support_test). */
    @Volatile private var supportTest = false
    /**
     * Nur Debug-Build, Messversuch (wie weit das Handy selbst geht): weder
     * Hitze-Pause noch Schutzabschaltung der App, nur der Schutz von Android. adb shell run-as … touch
     * files/no_heat_pause; nie im Release.
     */
    @Volatile private var noHeatPause = false
    /** Beginn des Rechnens (nach dem Dataset-Aufbau), Bezug fuer den Zeit-Anteil in der Support-Bilanz. */
    @Volatile private var minedSinceNanos = 0L
    private val lock = Any()

    // Bis Android 12 die in der App gewaehlte Sprache auch fuer Benachrichtigung und Hinweise (AppLanguage)
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Stopp kommt per startService (auch aus der Benachrichtigung im Hintergrund) → kein startForeground
        if (intent?.action == ACTION_STOP) {
            stopMining()
            return START_NOT_STICKY
        }
        // Start kam per startForegroundService: startForeground muss in jedem Fall folgen, sonst Absturz
        startForegroundCompat()
        val wallet = intent?.getStringExtra(EXTRA_WALLET)
        val threads = intent?.getIntExtra(EXTRA_THREADS, 2) ?: 2
        val worker = intent?.getStringExtra(EXTRA_WORKER)?.let(WorkerName::sanitize)
            ?.takeIf(WorkerName::isValid) ?: WorkerName.suggest()
        // Alles in einem Block: ein Stopp dazwischen wuerde sonst einen WakeLock ohne Besitzer hinterlassen
        synchronized(lock) {
            // Laeuft schon, oder der vorige Stopp raeumt noch auf → nichts Neues starten
            if (running || MiningState.state.value.phase == MiningState.Phase.STOPPING) return START_NOT_STICKY
            // Zweite Sicherung hinter der UI: nur gueltige Mainnet-Adressen (der Dienst ist nicht exportiert)
            // ... und nur nach Zustimmung zur aktuellen Textversion; sichtbar im Log, damit ein
            // Fehler hier nicht wie "Knopf tut nichts" aussieht
            val noConsent = Consent.needed(getSharedPreferences("miner", MODE_PRIVATE).getInt(Consent.PREF_VERSION, 0))
            if (noConsent) MiningState.log(LogEvent(LogKey.START_FAILED, listOf("consent")))
            if (noConsent || wallet == null || (Ican.parse(wallet) as? Ican.Result.Valid)?.network != Ican.Network.MAINNET) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            running = true
            supportTest = BuildConfig.DEBUG && java.io.File(filesDir, "support_test").exists()
            noHeatPause = BuildConfig.DEBUG && java.io.File(filesDir, "no_heat_pause").exists()
            if (noHeatPause) MiningState.log(LogEvent(LogKey.TEST_NO_HEAT_PAUSE))
            MiningState.update { it.copy(
                    phase = MiningState.Phase.PREPARING, notice = null, accepted = 0, rejected = 0, cooling = false,
                    startedAtMs = System.currentTimeMillis(), maxTempC = null, summary = null, batteryPercent = null, history = emptyList(), measurements = 0, trail = "", events = 0, prepStep = 0, hashes = 0, lastShareAtMs = 0, charging = null, batteryRefPercent = null, batteryRefAtMs = 0,
                    netBytesAtStart = appNetBytes(),
                ) }
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CorePulse::mining")
                .apply { acquire() }
            setupThread = Thread({ runMining(wallet, worker, threads) }, "mining-setup").apply { start() }
        }
        return START_NOT_STICKY  // nach Prozess-Tod nicht ungefragt wieder anlaufen
    }

    private fun runMining(wallet: String, workerName: String, threads: Int) {
        try {
            mine(wallet, workerName, threads)
        } catch (t: Throwable) {
            // Auf Android beendet eine unbehandelte Exception in irgendeinem Thread den ganzen Prozess
            MiningState.log(LogEvent(LogKey.ERROR, listOf(t.javaClass.simpleName, t.message)))
            stopMining(UiText(R.string.notice_error))
        }
    }

    private fun mine(wallet: String, workerName: String, threads: Int) {
        // Gibt ein voriger Lauf sein Dataset noch frei (Thread rechnete beim Stopp noch), erst darauf warten und
        // erst danach den Speicher messen, sonst meldet die Pruefung unnoetig "zu wenig Speicher" (Skeptiker 30.10.)
        val released = MinerEngine.awaitReleases(RELEASE_WAIT_MS)
        if (!running) return  // Stopp waehrend des Wartens: stopMining raeumt auf
        val mem = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        MiningState.log(LogEvent(LogKey.STARTING, listOf(
            workerName, threads, Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, mem.availMem shr 20, mem.totalMem shr 20,
        )))
        // Hitze-/Akkuschutz schon vor dem Dataset-Aufbau: der laeuft bis ~1 min auf allen Kernen, die Messschleife
        // greift erst danach (Review 30.10.). Gleiche Regel wie dort; schon warm → gar nicht erst anfangen.
        val temp = batteryTemperature()
        val thermal = if (Build.VERSION.SDK_INT >= 29) (getSystemService(POWER_SERVICE) as PowerManager).currentThermalStatus else null
        if (isCritical(temp, thermal) || isHot(temp, thermal)) {
            MiningState.log(LogEvent(LogKey.START_FAILED, listOf("heat ${temp ?: "-"} ${thermal ?: "-"}")))
            stopMining(tooHotNotice(temp))
            return
        }
        // Init braucht kurz ~520 MiB (Cache + Dataset). Darunter killt Android den Prozess kommentarlos.
        // Haengt das alte Dataset noch, zaehlt das wie zu wenig Speicher (zwei Datasets passen nicht)
        if (!released || mem.availMem < MIN_FREE_RAM) {
            stopMining(UiText(R.string.notice_low_memory, listOf((MIN_FREE_RAM shr 20).toInt())))
            return
        }

        val e = MinerEngine(
            threads, fullMem = true,
            onSolution = { job, nonce, hash ->
                MiningState.log(LogEvent(LogKey.SHARE_FOUND, listOf(nonce)))
                // Fund aus dem Support-Abschnitt geht an die TMMAC-Verbindung, alles andere an den Nutzer
                if (support?.submitIfSupport(job, nonce, hash) != true) client?.submit(job, nonce, hash)
            },
            log = { MiningState.log(it) },
            // Der Dataset-Aufbau ist einmalig und darf alle Kerne nutzen
            initThreads = Runtime.getRuntime().availableProcessors(),
        )
        // Stopp kam waehrend der Pruefungen: kein Dataset-Aufbau mehr anfangen
        if (!running) return
        // Startschritte in der App (Design-Runde 30.10.): jetzt der lange Teil, Dataset bauen und Rechenweg pruefen
        MiningState.update { it.copy(prepStep = 1) }
        try {
            e.start()
        } catch (t: Throwable) {
            val reason = t.message ?: t.javaClass.simpleName
            MiningState.log(LogEvent(LogKey.START_FAILED, listOf(reason)))
            stopMining(UiText(R.string.notice_start_failed, listOf(reason)))
            return
        }
        // Stopp kann waehrend der Vorbereitung (Dataset-Aufbau, Sekunden) kommen: dann hat stopMining
        // engine/client noch nicht gesehen → hier selbst aufraeumen. lock schliesst das Fenster.
        // Phase MINING nur unter lock und nur, solange running: sonst sprang die Anzeige nach einem Stopp genau
        // hier von STOPPING auf MINING zurueck, und onStartCommand liess einen neuen Start vor dem Aufraeumen zu
        val stoppedMeanwhile = synchronized(lock) {
            if (running) {
                engine = e
                val f = e.activeFlags
                MiningState.update {
                    it.copy(
                        phase = MiningState.Phase.MINING,
                        flags = "JIT=${f and NativeRandomY.FLAG_JIT != 0} FullMem=${f and NativeRandomY.FLAG_FULL_MEM != 0} HardAES=${f and NativeRandomY.FLAG_HARD_AES != 0}",
                    )
                }
            }
            !running
        }
        // stop() joint die Rechen-Threads (bis 5 s) → nicht unter lock, sonst blockiert onStartCommand
        if (stoppedMeanwhile) { e.stop(); return }

        // Support-Anteil 0,42 % fuer TMMAC, offen genannt; fest eingebaut, nicht abschaltbar
        // (gleiche Bedingungen fuer alle, finanziert die Weiterentwicklung)
        val sup = SupportShare(
            connect = { l ->
                val first = userEndpoint
                StratumClient(listOfNotNull(first) + CTR_POOLS.filter { it != first }, TMMAC_SUPPORT_ADDRESS, SUPPORT_WORKER, l)
            },
            setJob = { job -> if (job != null) e.setJob(job) else e.clearJob() },
            events = object : SupportShare.Events {
                override fun onSlice(active: Boolean) {
                    MiningState.update { it.copy(supportActive = active) }
                    if (active) MiningState.mark('P')
                }
                override fun onShareResult(accepted: Boolean, error: String?) =
                    MiningState.log(
                        if (accepted) LogEvent(LogKey.SUPPORT_SHARE_ACCEPTED) else LogEvent(LogKey.SUPPORT_SHARE_REJECTED, listOf(error))
                    )
                override fun onLog(event: LogEvent) = MiningState.log(event)
            },
            windowMs = if (supportTest) 120_000L else SupportShare.WINDOW_MS,
        )
        MiningState.log(LogEvent(LogKey.SUPPORT_ON, listOf(SUPPORT_PERCENT)))
        val c = StratumClient(CTR_POOLS, wallet, workerName, object : StratumClient.Listener {
            override fun onConnected(endpoint: PoolEndpoint) {
                userEndpoint = endpoint
                MiningState.update { it.copy(pool = endpoint.toString()) }
                MiningState.log(LogEvent(LogKey.CONNECTED, listOf(endpoint.toString())))
            }
            override fun onDisconnected(reason: String) {
                // Ohne Pool ist jeder Hash umsonst: Rechen-Threads pausieren, bis ein neuer Job kommt
                if (sup != null) sup.onUserDisconnected() else e.clearJob()
                MiningState.update { it.copy(pool = null) }
                MiningState.log(LogEvent(LogKey.DISCONNECTED, listOf(reason)))
            }
            override fun onLoginRejected(reason: String) {
                MiningState.log(LogEvent(LogKey.LOGIN_REJECTED, listOf(reason)))
                // Rohe Server-Antwort steht im Log; der Nutzer bekommt Klartext
                stopMining(UiText(R.string.notice_login_rejected))
            }
            override fun onFatal(reason: String) {
                MiningState.log(LogEvent(LogKey.ERROR, listOf("Pool", reason)))
                stopMining(UiText(R.string.notice_error))
            }
            override fun onLog(event: LogEvent) = MiningState.log(event)
            override fun onJob(job: Job) {
                MiningState.update { it.copy(shareDifficulty = job.difficulty) }
                MiningState.mark('J')
                if (sup != null) sup.onUserJob(job) else e.setJob(job)
            }
            override fun onShareResult(accepted: Boolean, error: String?) {
                MiningState.update {
                    if (accepted) it.copy(accepted = it.accepted + 1, lastShareAtMs = System.currentTimeMillis()) else it.copy(rejected = it.rejected + 1)
                }
                if (accepted) MiningState.mark('S')
                MiningState.log(if (accepted) LogEvent(LogKey.SHARE_ACCEPTED) else LogEvent(LogKey.SHARE_REJECTED, listOf(error)))
            }
        }, rankByLatency = true)  // naechster Server automatisch, keine Auswahl fuer den Nutzer
        synchronized(lock) {
            if (!running) return  // engine raeumt stopMining ab
            client = c
            support = sup
            minedSinceNanos = System.nanoTime()
            c.start()
            sup?.start()
        }

        measureLoop(e)
    }

    private fun measureLoop(e: MinerEngine) {
        val clientId = "0x" + UUID.randomUUID().toString().replace("-", "").padEnd(64, '0')
        val power = getSystemService(POWER_SERVICE) as PowerManager
        var lastHashes = 0L
        var lastT = System.nanoTime()
        var pausedSince = 0L  // 0 = keine Hitze-Pause
        var blind = 0  // Messungen in Folge ganz ohne Temperatur
        var ticks = 0
        while (running) {
            try { Thread.sleep(10_000) } catch (_: InterruptedException) { break }
            if (!running) break
            val now = System.nanoTime()
            val h = e.totalHashes
            val rate = (h - lastHashes) / ((now - lastT) / 1e9)
            lastHashes = h; lastT = now
            val temp = batteryTemperature()
            val thermal = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else null
            val percent = batteryPercent()
            val plugged = charging()
            val wallNow = System.currentTimeMillis()
            MiningState.update {
                // Bezug fuer "Akku pro Stunde" neu setzen, sobald das Kabel ab- oder angesteckt wird
                val resetRef = it.batteryRefPercent == null || it.charging != plugged
                it.copy(
                    charging = plugged,
                    batteryRefPercent = if (resetRef) percent else it.batteryRefPercent,
                    batteryRefAtMs = if (resetRef) wallNow else it.batteryRefAtMs,
                    hashrate = rate, hashes = h, batteryTempC = temp, thermalStatus = thermal, batteryPercent = percent,
                    maxTempC = listOfNotNull(it.maxTempC, temp).maxOrNull(),
                    // Nur echte Rechenphasen in die Kurve: Pause/ohne Pool waeren Nullen, die wie ein Absturz aussehen
                    history = if (pausedSince == 0L && it.pool != null) (it.history + rate.toFloat()).takeLast(SPARK_POINTS) else it.history,
                    // Zaehler fuer den Mess-Puls: history ist nach 10 min voll und waechst nicht mehr
                    measurements = if (pausedSince == 0L && it.pool != null) it.measurements + 1 else it.measurements,
                )
            }
            val snap = MiningState.state.value
            val t = resources.texts()
            // Statusleisten-Chip (Android 16) nur mit echter Rechenleistung, sonst ohne Zahl
            val chip = if (pausedSince == 0L && snap.pool != null) "${formatRate(rate, t.locale)} H/s" else null
            updateNotification(
                chip = chip,
                text = if (pausedSince != 0L) t.get(R.string.notif_cooling)
                else if (snap.supportActive) t.get(R.string.notif_support, formatRate(rate, t.locale))
                else t.plural(R.plurals.notif_status, snap.accepted, formatRate(rate, t.locale), snap.accepted) +
                    (temp?.let { " · " + t.get(R.string.value_celsius, it) } ?: "")
            )
            MiningState.log(LogEvent(LogKey.MEASUREMENT, listOf(rate, e.activeWorkers, temp ?: Float.NaN, thermal ?: "-")))
            client?.reportHashrate(rate.toLong(), clientId)
            if (++ticks % 60 == 0) supportBalance(e, support)?.let { MiningState.log(it) }  // alle 10 min

            // Schutz in zwei Stufen (Pause + automatisch weiter statt Stopp).
            // Massstab ist der Akku, nicht Androids Thermal-Stufen: deren Schwellen legt jeder Hersteller
            // selbst fest (Samsung meldet CRITICAL beim S20 FE schon bei ~44 °C Akku / 46 °C Gehaeuse) und
            // das System drosselt ab dort ohnehin selbst. Test ohne Limit 16.10.: das S20 FE
            // regelt sich bei Akku ~45 °C ein, 1.079 H/s statt 913 mit Pause bei CRITICAL. Die App
            // ist fuer jedes Handy, jedes hat seine eigene Regelung → die App greift erst bei 50 °C Akku ein
            // (Pause, weiter unter 45 °C statt Stopp, sonst endet ein Nachtlauf), Stopp erst bei 55 °C oder
            // Thermal EMERGENCY. Androids Stufen zaehlen sonst nur ohne Akku-Messwert.
            // Ohne jeden Messwert gibt es keinen Schutz → nicht minen (fail-closed statt ungebremst)
            blind = if (temp == null && thermal == null) blind + 1 else 0
            val critical = isCritical(temp, thermal)
            val hot = isHot(temp, thermal)
            val cool = if (temp != null) temp < RESUME_BATTERY_C
                else thermal == null || thermal < PowerManager.THERMAL_STATUS_SEVERE
            when {
                // Ohne Ladekabel nicht unter 20 % Akku: schuetzt Handy und Ruf der App
                plugged == false && percent != null && percent <= MIN_BATTERY_PERCENT -> stopMining(UiText(R.string.notice_low_battery, listOf(percent)))
                critical -> stopMining(tooHotNotice(temp))
                blind >= 3 -> stopMining(UiText(R.string.notice_no_temperature))
                pausedSince == 0L && hot -> {
                    pausedSince = now
                    e.paused = true
                    MiningState.update { it.copy(cooling = true) }
                    MiningState.log(LogEvent(LogKey.HEAT_PAUSE, listOf(temp ?: Float.NaN, thermal ?: "-")))
                    alert(getString(R.string.alert_heat_title), getString(R.string.alert_heat_text))
                }
                // Mindestens 60 s Pause, sonst springt der Miner im Sekundentakt an und aus
                pausedSince != 0L && cool && now - pausedSince >= MIN_PAUSE_NANOS -> {
                    MiningState.log(LogEvent(LogKey.HEAT_RESUME, listOf((now - pausedSince) / 1e9)))
                    pausedSince = 0L
                    e.paused = false
                    MiningState.update { it.copy(cooling = false) }
                    getSystemService(NotificationManager::class.java).cancel(ALERT_ID)  // Pause-Hinweis ist erledigt
                }
                // Kuehlt nicht ab (Ladegeraet, Sonne, warmer Raum): nicht endlos mit Bildschirm und WakeLock warten
                pausedSince != 0L && now - pausedSince >= MAX_PAUSE_NANOS -> stopMining(UiText(R.string.notice_not_cooling))
                e.activeWorkers == 0 -> stopMining(UiText(R.string.notice_no_workers))
            }
        }
    }

    /** Stopp-Stufe: Akku ab [MAX_BATTERY_C] oder Thermal EMERGENCY (Begruendung in measureLoop). */
    private fun isCritical(temp: Float?, thermal: Int?): Boolean = !noHeatPause &&
        ((temp != null && temp >= MAX_BATTERY_C) || (thermal != null && thermal >= PowerManager.THERMAL_STATUS_EMERGENCY))

    /** Pausen-Stufe: Akku ab [PAUSE_BATTERY_C]; Androids Stufen nur ohne Akku-Messwert. */
    private fun isHot(temp: Float?, thermal: Int?): Boolean = !noHeatPause &&
        (if (temp != null) temp >= PAUSE_BATTERY_C else thermal != null && thermal >= PowerManager.THERMAL_STATUS_CRITICAL)

    private fun tooHotNotice(temp: Float?) =
        UiText(R.string.notice_too_hot, listOf(temp?.let { "%.0f".format(resources.texts().locale, it) } ?: "–"))

    /**
     * Beleg fuer den Support-Anteil: Anteil der Hashes und der Zeit fuer TMMAC seit Start. Soll ueber volle
     * 100-min-Fenster 0,42 % sein; kuerzere Laeufe schwanken (Abschnitt faellt hinein oder nicht).
     */
    private fun supportBalance(e: MinerEngine, sup: SupportShare?): LogEvent? {
        if (sup == null || minedSinceNanos == 0L) return null
        val total = e.totalHashes
        val seconds = (System.nanoTime() - minedSinceNanos) / 1e9
        return LogEvent(LogKey.SUPPORT_BALANCE, listOf(
            sup.supportHashes, total, if (total > 0) 100.0 * sup.supportHashes / total else 0.0,
            sup.supportMillis / 1e3, seconds, if (seconds > 0) sup.supportMillis / 10.0 / seconds else 0.0,
        ))
    }

    private fun batteryTemperature(): Float? {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        // 0 oder negativ = Geraet meldet keinen echten Wert (manche ROMs/Tablets)
        return if (t == Int.MIN_VALUE || t <= 0) null else t / 10f
    }

    /**
     * Stoppt alles und zeigt [notice] (falls gesetzt) in der App. Mehrfachaufrufe sind harmlos:
     * nur der erste raeumt auf, spaetere ergaenzen hoechstens den Hinweis.
     */
    private fun stopMining(notice: UiText? = null) {
        val stopping = synchronized(lock) {
            if (notice != null) MiningState.update { it.copy(notice = notice) }
            if (!running) {
                // Nichts (mehr) zu tun, z. B. Stopp aus einer liegengebliebenen Benachrichtigung
                if (MiningState.state.value.phase == MiningState.Phase.IDLE) stopSelfResult(lastStartId)
                return
            }
            running = false
            MiningState.update { it.copy(phase = MiningState.Phase.STOPPING) }
            StopSet(client, support, engine, wakeLock, setupThread).also {
                client = null; support = null; engine = null; wakeLock = null; setupThread = null
            }
        }
        // Hinweis und Alert duerfen das Aufraeumen nie verhindern: wirft hier etwas, liefen Engine und Pool
        // sonst weiter und der WakeLock bliebe (Release-Review 28.10.). Darum runCatching, Alert erst nach dem Start.
        if (notice != null) runCatching {
            // logcat bleibt deutsch: Hinweis dafuer aus den deutschen Ressourcen
            MiningState.log(LogEvent(LogKey.STOPPED_WITH_NOTICE, listOf(notice))) {
                LogEvent(LogKey.STOPPED_WITH_NOTICE, listOf(notice.resolve(germanTexts()))).german()
            }
        }
        Thread {
            stopping.client?.stop()
            stopping.support?.stop()
            stopping.engine?.stop()
            stopping.engine?.let { e -> supportBalance(e, stopping.support)?.let { MiningState.log(it) } }
            // Hat der Setup-Thread die Engine schon uebergeben, wartet er nur noch im 10-s-Messtakt → wecken.
            // Sonst baut er evtl. noch das Dataset und raeumt danach selbst auf; nicht unterbrechen,
            // sonst bricht sein engine.stop() im join() ab.
            if (stopping.engine != null) stopping.setup?.interrupt()
            // Erst wenn der Setup-Thread fertig ist, ist der Speicher frei und ein neuer Start sicher.
            // Den WakeLock aber spaetestens nach 2 min freigeben, auch wenn join haengt oder wirft
            try {
                stopping.setup?.join(WAKELOCK_RELEASE_WAIT_MS)
            } finally {
                stopping.wakeLock?.takeIf { it.isHeld }?.release()
            }
            stopping.setup?.join()
            synchronized(lock) {
                MiningState.update { it.copy(phase = MiningState.Phase.IDLE, hashrate = 0.0, pool = null, cooling = false, supportActive = false, summary = summaryOf(it, stopping.engine?.totalHashes)) }
                MiningState.log(LogEvent(LogKey.STOPPED))
                // Kam inzwischen ein neuer Start, gehoert der Dienst ihm
                if (!running) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(lastStartId)
                }
            }
        }.start()
        // Abbruch auch ausserhalb der App sichtbar machen (vorher nur beim Oeffnen)
        if (notice != null) runCatching { alert(getString(R.string.alert_stopped_title), notice.resolve(resources.texts())) }
    }

    private class StopSet(
        val client: StratumClient?,
        val support: SupportShare?,
        val engine: MinerEngine?,
        val wakeLock: PowerManager.WakeLock?,
        val setup: Thread?,
    )

    override fun onDestroy() {
        if (running) stopMining()
        super.onDestroy()
    }

    /** Bilanz fuer Bilanz nach dem Stopp (Quittung) – null, wenn nie gerechnet wurde. */
    /** [engineHashes]: Endstand der gestoppten Engine, zaehlt auch die Hashes seit der letzten 10-s-Messung. */
    private fun summaryOf(s: MiningState.Snapshot, engineHashes: Long?): RunSummary? {
        if (s.startedAtMs == 0L) return null
        val minutes = ((System.currentTimeMillis() - s.startedAtMs) / 60_000).coerceAtLeast(0)
        return RunSummary(minutes, s.accepted, s.maxTempC, maxOf(s.hashes, engineHashes ?: 0))
    }

    /**
     * Deutsche Texte unabhaengig von der App-Sprache, nur fuer das Debug-logcat (MiningState.log ruft das nur
     * bei BuildConfig.DEBUG). Lint warnt vor fehlenden Sprachpaketen bei App-Bundles; Debug-Builds sind APKs
     * mit allen Sprachen, im Release laeuft der Code nicht.
     */
    @SuppressLint("AppBundleLocaleChanges")
    private fun germanTexts(): Texts =
        createConfigurationContext(Configuration(resources.configuration).apply { setLocale(Locale.GERMAN) }).resources.texts()

    /** Laedt das Handy gerade? null = unbekannt. */
    private fun charging(): Boolean? {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        return i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    /** Gesamter Netzverkehr dieser App (Bytes, hin + zurueck); -1 = vom System nicht gemeldet. */
    private fun appNetBytes(): Long {
        val rx = android.net.TrafficStats.getUidRxBytes(applicationInfo.uid)
        val tx = android.net.TrafficStats.getUidTxBytes(applicationInfo.uid)
        return if (rx < 0 || tx < 0) -1 else rx + tx
    }

    /** Hinweis-Benachrichtigung (Pause, Abbruch), getrennt von der laufenden Status-Benachrichtigung. */
    private fun alert(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT))
        val open = android.app.PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            nm.notify(ALERT_ID, Notification.Builder(this, ALERT_CHANNEL)
                .setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_stat_miner).setContentIntent(open).setAutoCancel(true)
                // Pause/Weiter kann sich wiederholen: nur der erste Hinweis klingelt
                .setOnlyAlertOnce(true).build())
        }
    }

    private fun batteryPercent(): Int? {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) null else level * 100 / scale
    }

    /**
     * Laufende Benachrichtigung. Ab Android 16 als Live-Update (hervorgehoben oben in den Benachrichtigungen, [chip]
     * mit der Hashrate in der Statusleiste; Design-Runde 30.10.). Alles lokal, nur die Systemfunktion, keine Bibliothek.
     */
    private fun buildNotification(text: String, chip: String? = null): Notification {
        val stopIntent = android.app.PendingIntent.getService(
            this, 0, Intent(this, MiningService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = android.app.PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_miner)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_action_stop), stopIntent).build())
            .apply {
                // setRequestPromotedOngoing erst ab 36.1 (Android 16 QPR1): auf 36.0 stuerzte der Dienst beim Start ab
                // (NoSuchMethodError, Emulator 30.10.) → volle Versionsnummer pruefen
                if (Build.VERSION.SDK_INT >= 36) {
                    if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) setRequestPromotedOngoing(true)
                    chip?.let { setShortCriticalText(it) }
                }
            }
            .build()
    }

    private fun updateNotification(text: String, chip: String? = null) {
        if (running) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text, chip))
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_mining), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = buildNotification(getString(R.string.status_preparing))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_STOP = "com.tellmemoreaboutcore.miner.STOP"
        const val EXTRA_WALLET = "wallet"
        const val EXTRA_THREADS = "threads"
        const val EXTRA_WORKER = "worker"
        private const val CHANNEL = "mining"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_CHANNEL = "alerts"
        private const val ALERT_ID = 2
        private const val MIN_BATTERY_PERCENT = 20
        /** Support-Anteil in Prozent (25 s je 100 min), nur fuer die Log-Zeile. */
        private const val SUPPORT_PERCENT = 0.42
        private const val MIN_FREE_RAM = 600L shl 20
        /** So lange haelt der Stopp den WakeLock hoechstens, waehrend er auf den Setup-Thread wartet. */
        private const val WAKELOCK_RELEASE_WAIT_MS = 120_000L
        /** Wartezeit auf die nachgeholte Dataset-Freigabe eines vorigen Laufs (MinerEngine.awaitReleases). */
        private const val RELEASE_WAIT_MS = 60_000L
        private const val MAX_BATTERY_C = 55f
        private const val PAUSE_BATTERY_C = 50f
        private const val RESUME_BATTERY_C = 45f
        private const val MIN_PAUSE_NANOS = 60_000_000_000L
        private const val MAX_PAUSE_NANOS = 15 * 60_000_000_000L
    }
}
