// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 TMMAC (tellmemoreaboutcore.com) — Zusätze nach GPL §7: NOTICE.md

package com.tellmemoreaboutcore.miner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.tellmemoreaboutcore.miner.core.Consent
import com.tellmemoreaboutcore.miner.core.CpuShare
import com.tellmemoreaboutcore.miner.core.Ican
import com.tellmemoreaboutcore.miner.core.WorkerName
import kotlinx.coroutines.delay

/** Obergrenze fuer das Halten des Startbildschirms (Puls 600 ms) und Dauer des Ausblendens. */
private const val SPLASH_MAX_HOLD_MS = 600L
private const val SPLASH_FADE_MS = 150L

/**
 * Eine Activity: Einstieg (erstes Mal), Startseite, Laufansicht, Einstellungen. Alles wird gespeichert;
 * beim naechsten Oeffnen genuegt "Mining starten".
 */
class MainActivity : ComponentActivity() {

    /** Start, der auf die Antwort zur Benachrichtigungs-Erlaubnis wartet (null nach Activity-Neuaufbau → aus Prefs starten). */
    private var pendingStart: (() -> Unit)? = null

    // Benachrichtigungen erst beim ersten Start erfragen, nicht beim Oeffnen: dann ist klar, wofuer.
    // Ablehnen ist ok — gemint wird trotzdem, nur der Stopp-Knopf in der Benachrichtigung fehlt.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            val start = pendingStart
            pendingStart = null
            // Gedreht waehrend des Dialogs: Lambda ist weg, die Angaben liegen aber schon in den Prefs
            if (start != null) start() else startFromPrefs()
        }

    // Bis Android 12 setzt die App die gewaehlte Sprache selbst ein (ab 13 das System, AppLanguage)
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    private fun startFromPrefs() {
        val prefs = getSharedPreferences("miner", MODE_PRIVATE)
        val wallet = prefs.getString("wallet", null) ?: return
        val worker = prefs.getString("worker", null) ?: return
        val share = runCatching { CpuShare.valueOf(prefs.getString("cpuShare", "") ?: "") }.getOrNull() ?: return
        startMining(Setup(wallet, worker, share, prefs.getBoolean("keepScreenOn", true)), share.threadsFor(Runtime.getRuntime().availableProcessors()))
    }

    /**
     * Bildschirm an lassen und ggf. abdunkeln. Warum: Samsung One UI setzt den Prozess bei
     * ausgeschaltetem Bildschirm auf drei kleine Kerne (Handy-Test S20 FE: ~1.285 → ~300 H/s),
     * trotz Vordergrund-Dienst. Wirkt nur, solange diese Activity sichtbar ist.
     */
    private fun applyScreen(keepOn: Boolean, dim: Boolean) {
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = if (dim) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    /**
     * Startbildschirm erst nach dem Puls des Symbols ausblenden (splash_icon.xml, 600 ms). Ohne das nimmt Android ihn
     * weg, sobald die App ihr erstes Bild hat: auf dem S20 FE nach ~250 ms, mitten im Wachsen (Messung 30.10.).
     * Hoechstens so lange, wie die Animation noch laeuft; mit ausgeschalteten Animationen skaliert Android Pause
     * und Ausblenden auf 0.
     */
    @RequiresApi(31)
    private fun holdSplashForPulse() {
        splashScreen.setOnExitAnimationListener { view ->
            val start = view.iconAnimationStart
            val length = view.iconAnimationDuration
            val left = if (start == null || length == null) 0L
            else (start.toEpochMilli() + length.toMillis() - System.currentTimeMillis()).coerceIn(0L, SPLASH_MAX_HOLD_MS)
            view.animate().alpha(0f).setStartDelay(left).setDuration(SPLASH_FADE_MS).withEndAction { view.remove() }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 31) holdSplashForPulse()
        val prefs = getSharedPreferences("miner", MODE_PRIVATE)
        val saved = Setup(
            wallet = prefs.getString("wallet", "") ?: "",
            // Frueher war der Vorschlag das Geraetemodell (oeffentlich bei CTR sichtbar): einmalig neutral ersetzen
            worker = prefs.getString("worker", null)?.takeIf { it != WorkerName.sanitize(Build.MODEL).trim('-') }
                ?: WorkerName.suggest(),
            share = runCatching { CpuShare.valueOf(prefs.getString("cpuShare", "") ?: "") }.getOrDefault(CpuShare.HALF),
            keepScreenOn = prefs.getBoolean("keepScreenOn", true),
        )
        val clipboard = getSystemService(ClipboardManager::class.java)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Brand.canvas, surface = Brand.canvas, onSurface = Brand.ink,
                    primary = Brand.greenPrimary, onPrimary = Brand.onGreen,
                ),
            ) {
                Surface(Modifier.fillMaxSize(), color = Brand.canvas) {
                    MinerScreen(
                        saved = saved,
                        cores = Runtime.getRuntime().availableProcessors(),
                        // Angenommene Textversion + Zeitpunkt, nur lokal (kein Backup, data_extraction_rules.xml).
                        // Der fruehere Schluessel "supportNoticeSeen" zaehlt nicht: alle stimmen einmal neu zu (28.10.)
                        consentNeeded = Consent.needed(prefs.getInt(Consent.PREF_VERSION, 0)),
                        onConsent = {
                            prefs.edit()
                                .putInt(Consent.PREF_VERSION, Consent.VERSION)
                                .putLong(Consent.PREF_AT, System.currentTimeMillis())
                                .remove("supportNoticeSeen") // Schluessel des frueheren Hinweises
                                .apply()
                        },
                        // Gueltige Eingaben sofort sichern, nicht erst beim Start: sonst verwirft ein App-Neustart
                        // einen geaenderten Namen
                        saveDraft = { key, value ->
                            if (value is Boolean) prefs.edit().putBoolean(key, value).apply()
                            else prefs.edit().putString(key, value.toString()).apply()
                        },
                        readClipboard = { clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() },
                        onStart = { setup, threads ->
                            prefs.edit()
                                .putString("wallet", setup.wallet)
                                .putString("worker", setup.worker)
                                .putString("cpuShare", setup.share.name)
                                .putBoolean("keepScreenOn", setup.keepScreenOn)
                                .apply()
                            if (Build.VERSION.SDK_INT >= 33 &&
                                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                pendingStart = { startMining(setup, threads) }
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                startMining(setup, threads)
                            }
                        },
                        onStop = {
                            startService(Intent(this, MiningService::class.java).setAction(MiningService.ACTION_STOP))
                        },
                        onCopyLog = { text -> clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.details_log_clip_label), text)) },
                        applyScreen = ::applyScreen,
                        onLanguage = { tag -> AppLanguage.set(this, tag) },
                    )
                }
            }
        }
    }

    private fun startMining(setup: Setup, threads: Int) {
        startForegroundService(
            Intent(this, MiningService::class.java)
                .putExtra(MiningService.EXTRA_WALLET, setup.wallet)
                .putExtra(MiningService.EXTRA_WORKER, setup.worker)
                .putExtra(MiningService.EXTRA_THREADS, threads)
        )
    }
}

internal data class Setup(
    val wallet: String, val worker: String, val share: CpuShare, val keepScreenOn: Boolean,
)

/** Hinweis unter dem Adressfeld; nur eine Mainnet-Adresse (cb…) kann Auszahlungen vom Pool bekommen. */
internal fun walletHint(wallet: String, check: Ican.Result, t: Texts): String = when (check) {
    is Ican.Result.Valid ->
        if (check.network == Ican.Network.MAINNET) t.get(R.string.address_valid_payout_to_address, grouped(check.address))
        else t.get(R.string.address_hint_testnet)
    // Die Adresse traegt eine Pruefziffer: ein falsches Zeichen faellt auf, bevor der Pool es tut
    Ican.Result.WrongChecksum -> t.get(R.string.address_hint_wrong_checksum)
    Ican.Result.WrongFormat -> t.get(if (wallet.isBlank()) R.string.address_hint_empty else R.string.address_hint_wrong_format)
}

/** Ergebnis eines QR-Scans: Adresse oder der Text (String-Ressource), warum nicht. */
internal fun fromQr(text: String): Pair<String?, Int?> {
    val found = Ican.findAll(text)
    val mainnet = found.filter { it.network == Ican.Network.MAINNET }
    return when {
        mainnet.size == 1 -> mainnet[0].address to null
        mainnet.size > 1 -> null to R.string.scan_multiple_addresses
        found.isNotEmpty() -> null to R.string.address_hint_testnet
        else -> null to R.string.scan_no_address
    }
}

/** Adresse in 4er-Gruppen, damit man sie vollstaendig mit der Wallet vergleichen kann. */
internal fun grouped(address: String) = address.chunked(4).joinToString(" ")

/** Pool-Statistik dieser Adresse (Routen aus catchthatrabbit/ctr-web: /coreid/:walletAddress/:pool, Region DE/FI/SG/HK). */
internal fun poolPage(address: String, pool: String?): Uri {
    val region = pool?.substringBefore('.')?.uppercase()?.takeIf { it in setOf("DE", "FI", "SG", "HK") } ?: "DE"
    return Uri.parse("https://catchthatrabbit.com/coreid/$address/$region")
}

// Hex-Adresse und Worker-Name: keine Autokorrektur, keine Grossschreibung, die das Tippen stoert
internal val plainKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Ascii,
)

/** Seiten der App; der Einstieg liegt als eigener Zustand darueber. */
/** [depth]: wie tief die Seite liegt, bestimmt die Richtung des Uebergangs. */
internal enum class Page(val depth: Int) { HOME(0), SETTINGS(1), ADDRESS(2), ABOUT(2) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MinerScreen(
    saved: Setup,
    cores: Int,
    consentNeeded: Boolean,
    onConsent: () -> Unit,
    saveDraft: (key: String, value: Any) -> Unit,
    readClipboard: () -> String?,
    onStart: (Setup, Int) -> Unit,
    onStop: () -> Unit,
    onCopyLog: (String) -> Unit,
    applyScreen: (keepOn: Boolean, dim: Boolean) -> Unit,
    onLanguage: (String) -> Unit,
) {
    val s by MiningState.state.collectAsStateWithLifecycle()
    var wallet by rememberSaveable { mutableStateOf(saved.wallet) }
    var worker by rememberSaveable { mutableStateOf(saved.worker) }
    var share by rememberSaveable { mutableStateOf(saved.share) }
    var keepScreenOn by rememberSaveable { mutableStateOf(saved.keepScreenOn) }
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    /** Wohin "Zurueck" von der Adress-Seite fuehrt (Startseite oder Einstellungen). */
    var addressFrom by rememberSaveable { mutableStateOf(Page.HOME) }
    // Erklaerungen der Laufansicht; hier, damit der Zustand das Abdunkeln uebersteht. Beim naechsten App-Start eingeklappt.
    var explain by rememberSaveable { mutableStateOf(false) }
    var scanMessage by rememberSaveable { mutableStateOf<Int?>(null) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult  // abgebrochen
        val (address, message) = fromQr(text)
        if (address != null) wallet = address
        scanMessage = message
    }
    // Kamera-Recht selbst abfragen: lehnt man im Scanner der Bibliothek ab, zeigt sie nur einen
    // irrefuehrenden englischen Kamera-Fehler (Emulator-Test 15.10.)
    val scanOptions = ScanOptions()
        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        .setPrompt(stringResource(R.string.scan_prompt))
        .setBeepEnabled(false)
        .setOrientationLocked(false)
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scan.launch(scanOptions)
        else scanMessage = R.string.scan_no_camera_permission
    }
    val context = LocalContext.current
    var dimmed by rememberSaveable { mutableStateOf(false) }
    var consentOk by rememberSaveable { mutableStateOf(!consentNeeded) }
    // Einstieg beim ersten Start und nach jeder neuen Textversion; -1 = nicht (mehr) offen
    var introStep by rememberSaveable { mutableStateOf(if (consentNeeded) 0 else -1) }
    var showLanguage by rememberSaveable { mutableStateOf(false) }

    val check = Ican.parse(wallet)
    val mainnet = (check as? Ican.Result.Valid)?.takeIf { it.network == Ican.Network.MAINNET }
    LaunchedEffect(worker) { if (WorkerName.isValid(worker)) saveDraft("worker", worker) }
    // Zuletzt gueltige Adresse: steht in den Prefs und gilt weiter, wenn die Adress-Seite mit einem leeren oder
    // halben Feld verlassen wird (sonst zeigte die Startseite bis zum Neustart "Core-Adresse eintragen")
    var lastValidWallet by rememberSaveable { mutableStateOf(saved.wallet) }
    LaunchedEffect(mainnet?.address) { mainnet?.let { saveDraft("wallet", it.address); lastValidWallet = it.address } }
    val idle = s.phase == MiningState.Phase.IDLE
    // Ohne Pool rechnet nichts: nach 5 min Warten den Bildschirm freigeben statt ihn endlos anzulassen
    val waitingForPool = s.phase == MiningState.Phase.MINING && s.pool == null && !s.cooling
    var poolWaitLong by remember { mutableStateOf(false) }
    LaunchedEffect(waitingForPool) {
        poolWaitLong = false
        if (waitingForPool) { delay(5 * 60_000); poolWaitLong = true }
    }
    val screenMode = keepScreenOn && !idle && !poolWaitLong

    // Bildschirm-Modus: an lassen, nach 30 s ohne Beruehrung abdunkeln; beim Stopp alles zuruecksetzen.
    // Jede Beruehrung (auch Scrollen) startet die 30 s neu, sonst wird es mitten im Lesen schwarz. Mit TalkBack
    // nie automatisch: der Fokus ginge alle 30 s verloren (ui-critic 30.10., WCAG 2.2.1). Von Hand geht es immer
    // ("Abdunkeln" neben "Stoppen").
    // Zeitstempel bewusst kein Compose-State: jede Fingerbewegung wuerde sonst den ganzen Bildschirm neu aufbauen
    val lastTouch = remember { longArrayOf(System.currentTimeMillis()) }
    val talkBack = rememberTouchExploration()
    DisposableEffect(screenMode, dimmed) {
        applyScreen(screenMode, screenMode && dimmed)
        onDispose { applyScreen(false, false) }
    }
    LaunchedEffect(screenMode, dimmed, talkBack, page) {
        if (!screenMode) { dimmed = false; return@LaunchedEffect }
        // Nur die Laufansicht dunkelt von selbst ab: in den Einstellungen liest oder tippt man gerade
        if (dimmed || talkBack || page != Page.HOME) return@LaunchedEffect
        lastTouch[0] = System.currentTimeMillis()
        while (true) {
            val left = 30_000 - (System.currentTimeMillis() - lastTouch[0])
            if (left <= 0) { dimmed = true; break }
            delay(left)
        }
    }
    // Vor dem Dimm-Return: sonst bricht der 10-min-Abruf ab, sobald der Bildschirm dunkel wird (Review 16.10.)
    // Erst nach der Zustimmung (Release-Review 28.10.) und nur waehrend eines Laufs und fuer die Bilanz direkt danach:
    // beim blossen Oeffnen zeigt die App kein Guthaben, also fragt sie auch nicht (Release-Review 30.10.: der
    // Zustimmungstext nennt Verbindungen nur beim Minen)
    val poolLoad = rememberPoolLoad(
        if (idle) null else s.pool, mainnet?.address?.takeIf { consentOk && (!idle || s.summary != null) }, live = !idle,
    )
    if (screenMode && dimmed) {
        DimmedView(s) { dimmed = false }
        return
    }

    val openPage: (Uri) -> Unit = { uri -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) } }
    val threads = share.threadsFor(cores)
    val start = { mainnet?.let { onStart(Setup(it.address, worker, share, keepScreenOn), threads) } }
    val onScan = {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scan.launch(scanOptions)
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    val address = AddressInput(
        wallet = wallet, check = check, mainnet = mainnet, scanMessage = scanMessage,
        onWallet = { wallet = it.trim(); scanMessage = null },
        onPaste = { readClipboard()?.let { wallet = it.trim(); scanMessage = null } },
        onScan = onScan, openPage = openPage,
    )

    if (showLanguage) {
        LanguagePicker(
            current = remember(showLanguage) { AppLanguage.current(context) },
            onPick = { tag -> showLanguage = false; if (tag != AppLanguage.current(context)) onLanguage(tag) },
            onDismiss = { showLanguage = false },
        )
    }

    val touchClock = Modifier.pointerInput(Unit) {
        awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); lastTouch[0] = System.currentTimeMillis() } }
    }
    if (introStep >= 0) {
        Onboarding(
            step = introStep, onStep = { introStep = it }, address = address, openPage = openPage,
            onLanguage = { showLanguage = true },
            onAgree = { consentOk = true; onConsent() },
            onFinish = { introStep = -1; page = Page.HOME },
        )
        return
    }
    // Seitenwechsel mit Richtung: tiefer = von rechts, zurueck = nach rechts (wie die System-Geste). Kurz und nur
    // ein Zehntel der Breite, damit es ordnet statt aufzuhalten; mit "Animationen entfernen" hart wie bisher.
    val still = reduceMotion()
    AnimatedContent(
        targetState = page, label = "page",
        transitionSpec = {
            if (still) EnterTransition.None togetherWith ExitTransition.None
            else {
                val dir = if (targetState.depth >= initialState.depth) 1 else -1
                (fadeIn(tween(180, delayMillis = 40)) + slideInHorizontally(tween(220)) { dir * it / 10 }) togetherWith
                    (fadeOut(tween(90)) + slideOutHorizontally(tween(220)) { -dir * it / 10 })
            }
        },
    ) { shown ->
    when (shown) {
        Page.ABOUT -> AboutScreen(openPage = openPage, onBack = { page = Page.SETTINGS }, onLanguage = { showLanguage = true })
        Page.ADDRESS -> AddressScreen(address, locked = !idle, onBack = {
            if (mainnet == null && lastValidWallet.isNotEmpty()) { wallet = lastValidWallet; scanMessage = null }
            page = addressFrom
        })
        Page.SETTINGS -> SettingsScreen(
            s = s, address = address, locked = !idle,
            onAddress = { addressFrom = Page.SETTINGS; page = Page.ADDRESS },
            worker = worker, onWorker = { worker = it },
            share = share, onShare = { share = it; saveDraft("cpuShare", it.name) }, cores = cores,
            keepScreenOn = keepScreenOn, onKeepScreenOn = { keepScreenOn = !keepScreenOn; saveDraft("keepScreenOn", keepScreenOn) },
            onLanguage = { showLanguage = true }, onAbout = { page = Page.ABOUT },
            onCopyLog = onCopyLog, onBack = { page = Page.HOME }, modifier = touchClock,
        )
        Page.HOME -> HomeScreen(
            s = s, idle = idle, mainnet = mainnet?.address, worker = worker, share = share, threads = threads, cores = cores,
            poolLoad = poolLoad, explain = explain, onExplain = { explain = !explain },
            openPool = { mainnet?.let { openPage(poolPage(it.address, s.pool)) } },
            onSettings = { page = Page.SETTINGS },
            onAddress = { addressFrom = Page.HOME; page = Page.ADDRESS },
            // Zustimmung fehlt nur, wenn der Einstieg uebersprungen wurde (z. B. neue Textversion mitten im Betrieb)
            onStart = { if (consentOk) start() else introStep = 0 },
            onStop = onStop,
            onDim = if (screenMode) ({ dimmed = true }) else null,
            modifier = touchClock,
        )
    }
    }
}

/**
 * Ruhemodus waehrend des Minings (Design-Runde 30.10., Punkt 5): ein Bildschirm, den man nachts neben dem Bett liegen
 * lassen mag. Schwarz, das Core-Zeichen kaum sichtbar, darunter die Hashrate gross und sehr dunkel; Signal-Gruen nur im
 * Quadrat, wenn ein Share angenommen wird. Gegen Einbrennen wandert alles langsam: alle 30 s ein kleiner Schritt in
 * eine zufaellige Richtung, 8 s weich gegleitet. Keine Dauerbewegung: die zeichnete jedes Bild neu und kostete Akku,
 * waehrend das Handy ohnehin rechnet.
 */
@Composable
private fun DimmedView(s: MiningState.Snapshot, onWake: () -> Unit) {
    // Zurueck weckt die Ansicht, statt die Activity zu beenden (dann liefe der Miner ohne Bildschirm gedrosselt)
    BackHandler(onBack = onWake)
    var bx by rememberSaveable { mutableStateOf(0f) }
    var by by rememberSaveable { mutableStateOf(-0.1f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            // Kleiner Schritt, an den Raendern zurueckgehalten: ueber Stunden kommt jede Stelle mal dran
            val angle = kotlin.random.Random.nextDouble(0.0, 2 * Math.PI)
            bx = (bx + 0.3f * kotlin.math.cos(angle).toFloat()).coerceIn(-0.75f, 0.75f)
            by = (by + 0.25f * kotlin.math.sin(angle).toFloat()).coerceIn(-0.65f, 0.65f)
        }
    }
    val still = reduceMotion()
    val glide = if (still) snap<Float>() else tween(8_000, easing = MotionEase)
    val x by animateFloatAsState(bx, glide, label = "x")
    val y by animateFloatAsState(by, glide, label = "y")
    val locale = rememberTexts().locale
    val dimmedState = stringResource(R.string.dimmed_state_a11y)
    Box(
        Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(24.dp)
            .clickable(onClickLabel = stringResource(R.string.dimmed_wake_action), onClick = onWake)
            .semantics { stateDescription = dimmedState },
        contentAlignment = BiasAlignment(x, y),
    ) {
        val computing = s.phase == MiningState.Phase.MINING && s.pool != null && !s.cooling
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Core-Zeichen aus tmmac-core/logos (core_mark.xml), nur als Schatten
            Image(
                painterResource(R.drawable.core_mark), contentDescription = null,
                colorFilter = ColorFilter.tint(Brand.surface4), modifier = Modifier.size(56.dp),
            )
            if (s.cooling) {
                Text(
                    stringResource(R.string.dimmed_cooling), style = Type.display(Scale.title, Brand.borderStrong),
                    modifier = Modifier.padding(top = 24.dp),
                )
            } else {
                // Sehr dunkel (borderStrong, ~3,4:1 auf Schwarz): bei 1 % Helligkeit im dunklen Raum lesbar, blendet nicht
                Text(
                    if (computing) formatRate(s.hashrate, locale) else "–",
                    style = Type.display(Scale.hero, Brand.borderStrong).copy(lineHeight = 0.92.em), maxLines = 1,
                    autoSize = TextAutoSize.StepBased(maxFontSize = Scale.hero),
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
            // "H/s · 3 Shares"; das Quadrat davor blitzt bei jedem angenommenen Share gruen
            Row(
                Modifier.padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Square(beat = s.accepted, tick = s.measurements)
                Text(pluralStringResource(R.plurals.dimmed_status, s.accepted, "H/s", s.accepted), style = Type.data(Scale.body, Brand.inkQuiet))
            }
            // Versalien erst hier: in der Ressource normal geschrieben, damit TalkBack nicht buchstabiert
            val hint = stringResource(R.string.dimmed_hint)
            Text(
                // ink-quiet (~5:1 auf Schwarz): der Hinweis ist die einzige Anleitung zum Aufwecken, gegen Blenden hilft die Helligkeit
                hint.caps(), style = Type.kick.copy(color = Brand.inkQuiet), textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 28.dp).semantics { contentDescription = hint },
            )
        }
    }
}

/** TalkBack (Touch-Erkundung) an? Folgt dem Ein-/Ausschalten, ohne die App neu zu starten. */
@Composable
private fun rememberTouchExploration(): Boolean {
    val am = LocalContext.current.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
    var on by remember { mutableStateOf(am.isTouchExplorationEnabled) }
    DisposableEffect(am) {
        val listener = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        am.addTouchExplorationStateChangeListener(listener)
        onDispose { am.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}


