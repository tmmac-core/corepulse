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
import androidx.annotation.RequiresApi
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
 * Ein Bildschirm, drei Angaben, ein Start: Wallet → Worker-Name → CPU-Anteil → Start.
 * Alles wird gespeichert; beim naechsten Oeffnen genuegt "Start".
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
                        saveDraft = { key, value -> prefs.edit().putString(key, value).apply() },
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

private data class Setup(
    val wallet: String, val worker: String, val share: CpuShare, val keepScreenOn: Boolean,
)

/** Hinweis unter dem Adressfeld; nur eine Mainnet-Adresse (cb…) kann Auszahlungen vom Pool bekommen. */
private fun walletHint(wallet: String, check: Ican.Result, t: Texts): String = when (check) {
    is Ican.Result.Valid ->
        if (check.network == Ican.Network.MAINNET) t.get(R.string.address_valid_payout_to_address, grouped(check.address))
        else t.get(R.string.address_hint_testnet)
    // Die Adresse traegt eine Pruefziffer: ein falsches Zeichen faellt auf, bevor der Pool es tut
    Ican.Result.WrongChecksum -> t.get(R.string.address_hint_wrong_checksum)
    Ican.Result.WrongFormat -> t.get(if (wallet.isBlank()) R.string.address_hint_empty else R.string.address_hint_wrong_format)
}

/** Ergebnis eines QR-Scans: Adresse oder der Text (String-Ressource), warum nicht. */
private fun fromQr(text: String): Pair<String?, Int?> {
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
private fun grouped(address: String) = address.chunked(4).joinToString(" ")

/** Pool-Statistik dieser Adresse (Routen aus catchthatrabbit/ctr-web: /coreid/:walletAddress/:pool, Region DE/FI/SG/HK). */
private fun poolPage(address: String, pool: String?): Uri {
    val region = pool?.substringBefore('.')?.uppercase()?.takeIf { it in setOf("DE", "FI", "SG", "HK") } ?: "DE"
    return Uri.parse("https://catchthatrabbit.com/coreid/$address/$region")
}

// Hex-Adresse und Worker-Name: keine Autokorrektur, keine Grossschreibung, die das Tippen stoert
private val plainKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Ascii,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MinerScreen(
    saved: Setup,
    cores: Int,
    consentNeeded: Boolean,
    onConsent: () -> Unit,
    saveDraft: (key: String, value: String) -> Unit,
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
    var showDetails by rememberSaveable { mutableStateOf(false) }
    // Erklaerungen der Laufansicht (uebersichtlicher); hier statt in RunningContent, damit der
    // Zustand das Abdunkeln uebersteht. Beim naechsten App-Start wieder eingeklappt.
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
    var keepScreenOn by rememberSaveable { mutableStateOf(saved.keepScreenOn) }
    var dimmed by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var consentOk by rememberSaveable { mutableStateOf(!consentNeeded) }
    var showNotice by rememberSaveable { mutableStateOf(false) }
    var showLanguage by rememberSaveable { mutableStateOf(false) }

    // Wiederkehrer: Adresse gespeichert und Hinweise bestaetigt → kompakte Ansicht, kurze Einleitung (Design-Runde 30.10.)
    val returning = remember { !consentNeeded && (Ican.parse(saved.wallet) as? Ican.Result.Valid)?.network == Ican.Network.MAINNET }
    val check = Ican.parse(wallet)
    val mainnet = (check as? Ican.Result.Valid)?.takeIf { it.network == Ican.Network.MAINNET }
    val workerOk = WorkerName.isValid(worker)
    LaunchedEffect(worker, workerOk) { if (workerOk) saveDraft("worker", worker) }
    LaunchedEffect(mainnet?.address) { mainnet?.let { saveDraft("wallet", it.address) } }
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
    // nie automatisch: der Fokus ginge alle 30 s verloren (ui-critic 30.10., WCAG 2.2.1)
    // Zeitstempel bewusst kein Compose-State: jede Fingerbewegung wuerde sonst den ganzen Bildschirm neu aufbauen
    val lastTouch = remember { longArrayOf(System.currentTimeMillis()) }
    val talkBack = rememberTouchExploration()
    DisposableEffect(screenMode, dimmed) {
        applyScreen(screenMode, screenMode && dimmed)
        onDispose { applyScreen(false, false) }
    }
    LaunchedEffect(screenMode, dimmed, talkBack) {
        if (!screenMode) { dimmed = false; return@LaunchedEffect }
        if (dimmed || talkBack) return@LaunchedEffect
        lastTouch[0] = System.currentTimeMillis()
        while (true) {
            val left = 30_000 - (System.currentTimeMillis() - lastTouch[0])
            if (left <= 0) { dimmed = true; break }
            delay(left)
        }
    }
    // Vor dem Dimm-Return: sonst bricht der 10-min-Abruf ab, sobald der Bildschirm dunkel wird (Review 16.10.)
    // Erst nach der Zustimmung: der Hinweistext nennt Netzverkehr nur beim Minen, die Adresse geht sonst schon
    // beim Eintippen an die CTR-API (Release-Review 28.10.; gleiche Pruefung wie der Start-Knopf)
    // Nur waehrend eines Laufs und fuer die Bilanz direkt danach: beim blossen Oeffnen zeigt die App kein Guthaben,
    // also fragt sie auch nicht (Release-Review 30.10.: der Zustimmungstext nennt Verbindungen nur beim Minen)
    val poolLoad = rememberPoolLoad(
        if (idle) null else s.pool, mainnet?.address?.takeIf { consentOk && (!idle || s.summary != null) }, live = !idle,
    )
    if (screenMode && dimmed) {
        DimmedView(s) { dimmed = false }
        return
    }

    val openPage: (Uri) -> Unit = { uri -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) } }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val start = { mainnet?.let { onStart(Setup(it.address, worker, share, keepScreenOn), share.threadsFor(cores)) } }

    if (showLanguage) {
        LanguagePicker(
            current = remember(showLanguage) { AppLanguage.current(context) },
            onPick = { tag -> showLanguage = false; if (tag != AppLanguage.current(context)) onLanguage(tag) },
            onDismiss = { showLanguage = false },
        )
    }
    if (showAbout) {
        AboutScreen(openPage = openPage, onBack = { showAbout = false }, onLanguage = { showLanguage = true })
        return
    }
    if (showNotice) {
        ConsentNotice(
            openPage = openPage,
            onAgree = {
                consentOk = true; showNotice = false
                onConsent()
                start()
            },
            onDismiss = { showNotice = false },
        )
    }
    val needsNotificationPermission = Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    val onScan = {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scan.launch(scanOptions)
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    // Edge-to-Edge (Pflicht ab Android 15): Status- und Navigationsleiste selbst freihalten.
    // Inhalt scrollt, der Start-/Stopp-Knopf bleibt unten immer sichtbar. Pine-Licht oben wie der Website-Hero.
    Column(
        Modifier.fillMaxSize().pineGlow(centerY = if (idle) 0f else 0.28f, strength = if (idle) 0.4f else 0.5f).safeDrawingPadding()
            .pointerInput(Unit) {
                awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); lastTouch[0] = System.currentTimeMillis() } }
            },
    ) {
        // Inhalt blendet unten weich aus, statt hart an der Knopfleiste abgeschnitten zu sein
        // Beim Wechsel Einrichtung ↔ Lauf nach oben: sonst steht nach dem Stopp die Bilanz ausserhalb des Bildschirms
        val scroll = rememberScrollState()
        LaunchedEffect(idle) { scroll.scrollTo(0) }
        Box(Modifier.weight(1f)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (idle) {
                SetupContent(
                    s = s, wallet = wallet, onWallet = { wallet = it.trim(); scanMessage = null }, check = check, mainnet = mainnet,
                    scanMessage = scanMessage, readClipboard = { readClipboard()?.let { wallet = it.trim(); scanMessage = null } },
                    onScan = onScan, worker = worker, onWorker = { worker = WorkerName.sanitize(it) }, workerOk = workerOk,
                    share = share, onShare = { share = it }, cores = cores,
                    keepScreenOn = keepScreenOn, onKeepScreenOn = { keepScreenOn = !keepScreenOn },
                    poolLoad = poolLoad, openPage = openPage, onLanguage = { showLanguage = true },
                    returning = returning || s.summary != null,
                )
            } else {
                RunningContent(s, worker = worker, poolLoad = poolLoad, explain = explain, onExplain = { explain = !explain })
            }

            // Linkliste statt loser Textlinks (Design-Review 30.10.); Details fuer Tests: Messwerte und Log zum Kopieren,
            // standardmaessig eingeklappt
            Panel(Modifier.padding(top = 8.dp), spacing = 0.dp) {
                if (!idle && mainnet != null) {
                    LinkRow(stringResource(R.string.setup_pool_link), mark = "↗") { openPage(poolPage(mainnet.address, s.pool)) }
                    Hairline()
                }
                LinkRow(stringResource(R.string.about_title)) { showAbout = true }
                Hairline()
                LinkRow(stringResource(R.string.details_show), mark = if (showDetails) "−" else "+", expanded = showDetails) { showDetails = !showDetails }
                if (showDetails) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.details_stats, s.shareDifficulty, s.thermalStatus?.toString() ?: "-", s.flags),
                        style = Type.data(12.sp, Brand.inkMuted),
                    )
                    // Erst hier in Text: in der aktuellen App-Sprache, auch fuer Eintraege von vor einem Sprachwechsel
                    val logTexts = rememberTexts()
                    val logLines = remember(s.log, logTexts) { s.log.map { it.text(logTexts) } }
                    QuietButton(stringResource(R.string.details_copy_log)) { onCopyLog(logLines.joinToString("\n")) }
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 320.dp).background(Brand.surface1, Brand.card)
                            .verticalScroll(rememberScrollState(), reverseScrolling = true).padding(12.dp),
                    ) {
                        logLines.forEach { Text(it, style = Type.data(11.sp, Brand.inkMuted)) }
                    }
                }
            }
        }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Brand.canvas.copy(alpha = 0.9f)))),
        )
        }
        // Bei offener Tastatur nur der Knopf: Hinweis und Fusszeile wuerden sonst das Feld verdecken (ui-critic 30.10.)
        val imeOpen = WindowInsets.isImeVisible
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (idle) {
                val reason = when {
                    mainnet == null -> stringResource(R.string.start_reason_no_address)
                    !workerOk -> stringResource(R.string.start_reason_no_name)
                    needsNotificationPermission -> stringResource(R.string.start_reason_notifications)
                    else -> null
                }
                if (!imeOpen) reason?.let { Text(it, style = Type.body(13.sp)) }
                // Der eine gefuellte gruene Knopf des Bildschirms (Kanon-Regel 1a)
                Button(
                    // Vor dem ersten Start (und nach neuer Textversion) die Hinweise bestaetigen lassen
                    // Tastatur zu: sonst verdeckt sie im Dialog Haekchen und Knopf (Emulator 28.10.)
                    onClick = { if (consentOk) start() else { focusManager.clearFocus(force = true); keyboard?.hide(); showNotice = true } },
                    enabled = mainnet != null && workerOk,
                    shape = Brand.button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Brand.greenPrimary, contentColor = Brand.onGreen,
                        disabledContainerColor = Brand.surface3, disabledContentColor = Brand.inkQuiet,
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.start_button), style = Type.body(17.sp, LocalContentColor.current, FontWeight.SemiBold)) }
                if (!imeOpen) Text(
                    stringResource(R.string.start_footer),
                    style = Type.body(12.sp, Brand.inkQuiet), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedButton(
                    onClick = onStop,
                    enabled = s.phase != MiningState.Phase.STOPPING,
                    shape = Brand.button,
                    border = BorderStroke(1.dp, Brand.borderStrong),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text(stringResource(if (s.phase == MiningState.Phase.STOPPING) R.string.status_stopping else R.string.stop_button), style = Type.body(17.sp, Brand.ink, FontWeight.Medium)) }
            }
        }
    }
}

@Composable
private fun SetupContent(
    s: MiningState.Snapshot,
    wallet: String, onWallet: (String) -> Unit, check: Ican.Result, mainnet: Ican.Result.Valid?,
    @StringRes scanMessage: Int?, readClipboard: () -> Unit, onScan: () -> Unit,
    worker: String, onWorker: (String) -> Unit, workerOk: Boolean,
    share: CpuShare, onShare: (CpuShare) -> Unit, cores: Int,
    keepScreenOn: Boolean, onKeepScreenOn: () -> Unit,
    poolLoad: PoolLoad, openPage: (Uri) -> Unit, onLanguage: () -> Unit, returning: Boolean,
) {
    // Gueltige gespeicherte Adresse als eine Zeile; "Anpassen" oeffnet das Feld wieder
    var editAddress by rememberSaveable { mutableStateOf(!returning) }
    var editWorker by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    // Gespeichert ist ein gueltiger Name schon (MinerScreen); "Speichern" bzw. Enter schliesst nur das Feld
    // Erst Fokus weg, dann Feld schliessen: sonst springt der Fokus ins Adressfeld und die Tastatur bleibt offen
    val closeWorker = { if (workerOk) { focus.clearFocus(force = true); editWorker = false } }
    val t = rememberTexts()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Kick("TMMAC · CorePulse", Modifier.weight(1f))
            LanguageButton(onLanguage)
        }
        // XCB statt "Core": gemint wird die Coin, nicht die Marke
        Text(stringResource(R.string.setup_title), style = Type.display(48.sp), modifier = Modifier.padding(top = 10.dp).semantics { heading() })
        // Beim ersten Mal die ganze Erklaerung, danach ein Satz: wer wiederkommt, will starten
        Text(stringResource(if (returning) R.string.setup_intro_short else R.string.setup_intro), style = Type.body(15.sp))
    }

    s.notice?.let { Notice(it.resolve(t)) }
    // Quittung auch nach einem Abbruch: der Hinweis sagt warum, die Quittung was der Lauf gebracht hat
    s.summary?.let { summary ->
        Receipt(summary, poolLoad) {
            if (mainnet != null) LinkRow(stringResource(R.string.setup_pool_link), mark = "↗") { openPage(poolPage(mainnet.address, null)) }
        }
    }

    // Aktion ("Einfuegen", "Anpassen") neben dem Feld statt in der Kopfzeile: sonst erbt die Kopfzeile die 48-dp-
    // Tippflaeche und zwischen Eyebrow und Feld klafft ein Loch (Design-Review 30.10.)
    val addressError = when {
        scanMessage != null -> stringResource(scanMessage)
        mainnet == null && wallet.isNotBlank() -> walletHint(wallet, check, t)
        else -> null
    }
    if (mainnet != null && !editAddress && scanMessage == null) Panel {
        Kick(stringResource(R.string.address_kick))
        val a11y = stringResource(R.string.address_compact_a11y, grouped(mainnet.address))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.weight(1f).clearAndSetSemantics { contentDescription = a11y },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(6.dp).background(Brand.greenFloor, RoundedCornerShape(3.dp)))
                Text(
                    stringResource(R.string.address_compact, mainnet.address.take(4) + " … " + mainnet.address.takeLast(4)),
                    style = Type.data(14.sp, Brand.ink),
                )
            }
            TextLink(stringResource(R.string.worker_edit)) { editAddress = true }
        }
    } else Panel {
        Kick(stringResource(R.string.address_kick))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Field(wallet, onWallet, label = stringResource(R.string.address_kick), placeholder = "cb…", next = true, error = addressError) }
            TextLink(stringResource(R.string.address_paste), onClick = readClipboard)
        }
        when {
            // Scan-Ergebnis kommt ohne Tippen: TalkBack liest es vor (Live-Region); Tipp-Hinweise nicht, sonst redet er bei jedem Zeichen
            scanMessage != null -> Text(stringResource(scanMessage), style = Type.body(13.sp, Brand.coral), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            mainnet != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.padding(top = 6.dp).size(6.dp).background(Brand.greenFloor, RoundedCornerShape(3.dp)))
                Column {
                    Text(stringResource(R.string.address_valid_payout_to), style = Type.body(13.sp))
                    Text(grouped(mainnet.address), style = Type.data(13.sp, Brand.ink))
                }
            }
            else -> Text(walletHint(wallet, check, t), style = Type.body(13.sp, if (wallet.isBlank()) Brand.inkMuted else Brand.coral))
        }
        if (wallet.isBlank()) {
            QuietButton(stringResource(R.string.address_scan_button), onClick = onScan)
            Text(stringResource(R.string.address_scan_privacy), style = Type.body(12.sp, Brand.inkQuiet))
            // Gleich sichtbar statt hinter einem Link: ohne Adresse kommt niemand weiter (Laien-Review 16.10.)
            Hairline(Modifier.padding(top = 4.dp))
            Text(stringResource(R.string.address_none_title), style = Type.body(15.sp, Brand.ink, FontWeight.Medium), modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.address_none_body), style = Type.body(14.sp))
            LinkRow(stringResource(R.string.address_corepass_store_link), mark = "↗") { openPage(Uri.parse("https://play.google.com/store/apps/details?id=net.corepass.app")) }
        }
    }

    // Der Name ist vorbelegt; die meisten muessen ihn nie anfassen — darum eingeklappt
    Panel {
        Kick(stringResource(R.string.worker_kick))
        val editing = editWorker || !workerOk
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                if (editing) Field(
                    worker, onWorker, label = stringResource(R.string.worker_kick), placeholder = "phone-1234", next = false, onDone = closeWorker,
                    error = if (workerOk) null else stringResource(R.string.worker_invalid),
                )
                else Text(worker, style = Type.data(16.sp, Brand.ink))
            }
            if (workerOk && !editWorker) TextLink(stringResource(R.string.worker_edit)) { editWorker = true }
            if (workerOk && editWorker) TextLink(stringResource(R.string.worker_save), onClick = closeWorker)
        }
        if (editing) {
            Text(
                stringResource(if (workerOk) R.string.worker_public_warning else R.string.worker_invalid),
                style = Type.body(13.sp, if (workerOk) Brand.inkMuted else Brand.coral),
            )
        } else {
            Text(stringResource(R.string.worker_explainer), style = Type.body(13.sp))
        }
    }

    Panel {
        Kick(stringResource(R.string.power_kick))
        // Bei grosser Schrift 2 × 2 statt 4 nebeneinander: sonst bricht "25 %" mitten im Wert um (ui-critic 30.10.)
        val rows = if (LocalDensity.current.fontScale > 1.3f) CpuShare.entries.chunked(2) else listOf(CpuShare.entries)
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { option ->
                        ShareOption(
                            label = if (option == CpuShare.ALL) stringResource(R.string.power_option_all)
                                else stringResource(R.string.value_percent, option.percent),
                            detail = pluralStringResource(R.plurals.power_option_cores, cores, option.threadsFor(cores), cores),
                            selected = option == share,
                            onClick = { onShare(option) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        Text(stringResource(R.string.power_hint), style = Type.body(13.sp))
        Hairline(Modifier.padding(vertical = 4.dp))
        // Viele Handys (z. B. Samsung) bremsen den Miner, sobald der Bildschirm ausgeht
        Toggle(
            stringResource(R.string.toggle_screen_title),
            stringResource(R.string.toggle_screen_detail),
            keepScreenOn, onKeepScreenOn,
        )
        Hairline(Modifier.padding(vertical = 4.dp))
        // Fest eingebaut (19.10.): kein Schalter, aber weiter offen genannt
        Info(stringResource(R.string.running_kick_support), stringResource(R.string.support_text))
    }
}

@Composable
private fun RunningContent(
    s: MiningState.Snapshot, worker: String, poolLoad: PoolLoad,
    explain: Boolean, onExplain: () -> Unit,
) {
    val pool = s.pool.takeIf { s.phase == MiningState.Phase.MINING }
    val computing = pool != null && !s.cooling
    val server = pool?.substringBefore('.')?.uppercase()

    // Leichter Impuls bei jedem angenommenen Share: das Handy meldet sich, ohne dass man hinsieht
    val view = LocalView.current
    LaunchedEffect(s.accepted) {
        if (s.accepted > 0) view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
        )
    }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    val uid = LocalContext.current.applicationInfo.uid
    val t = rememberTexts()
    val locale = t.locale

    // XCB steht in der Eyebrow (gemint wird die Coin), die Einheit unter der Zahl ist nur H/s (Miner-Review 16.10.)
    // Startschritte, bis der erste Job vom Pool da ist (Design-Runde 30.10.: zeigen, was wirklich passiert)
    val starting = s.events == 0 && !s.cooling && (s.phase == MiningState.Phase.PREPARING || s.phase == MiningState.Phase.MINING)
    val (kick, sentence) = when {
        starting -> stringResource(R.string.running_kick_preparing) to stringResource(R.string.running_preparing)
        s.phase == MiningState.Phase.STOPPING -> stringResource(R.string.running_kick_stopping) to stringResource(R.string.status_stopping)
        s.cooling -> stringResource(R.string.running_kick_cooling) to stringResource(R.string.running_cooling)
        s.supportActive -> stringResource(R.string.running_kick_support) to stringResource(R.string.running_support)
        pool == null -> stringResource(R.string.running_kick_waiting) to stringResource(R.string.running_waiting)
        (s.batteryTempC ?: 0f) >= 45f -> stringResource(R.string.running_kick_mining, server ?: "") to
            stringResource(R.string.running_hot, s.batteryTempC ?: 0f)
        (s.batteryTempC ?: 0f) >= 40f -> stringResource(R.string.running_kick_mining, server ?: "") to
            stringResource(R.string.running_warm, s.batteryTempC ?: 0f)
        else -> stringResource(R.string.running_kick_mining, server ?: "") to stringResource(R.string.running_ok)
    }

    val elapsedA11y = stringResource(R.string.running_elapsed_a11y, elapsed(now - s.startedAtMs))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        // Das Quadrat blitzt bei jedem angenommenen Share gruen (Website: neuer Block)
        // ... und schlaegt leise bei jeder Messung (alle 10 s), damit der Puls von Anfang an sichtbar ist
        // Live-Region auf der Phase (Minen, Abkuehlen, Support …), nicht auf dem Satz mit der Temperatur: sonst sagt
        // TalkBack bei warmem Handy alle 10 s den ganzen Satz an (Skeptiker 30.10.)
        Kick(
            kick, Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            beat = s.accepted, tick = s.measurements.takeIf { computing },
        )
        if (s.startedAtMs > 0) Text(
            elapsed(now - s.startedAtMs), style = Type.kick.copy(color = Brand.inkQuiet),
            modifier = Modifier.semantics { contentDescription = elapsedA11y },
        )
    }

    val rateA11y = if (computing) stringResource(R.string.running_rate_a11y, formatRate(s.hashrate, locale))
        else stringResource(R.string.running_not_computing_a11y)
    Column(Modifier.fillMaxWidth().padding(top = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Ohne Pool rechnet nichts; eine alte Zahl wuerde Leistung vortaeuschen.
        // autoSize: bei grosser Schrift (Android ≤13 skaliert linear) kleiner statt abgeschnitten — sonst stuende
        // eine falsche Zahl da. Zeilenhoehe in em, damit sie mitschrumpft.
        // Neue Messung gleitet ein (180 ms, --motion-base) statt hart zu springen; ohne Bewegung (Bedienungshilfen) sofort
        val still = reduceMotion()
        // Erste Messung zaehlt von 0 hoch (900 ms): der Moment, in dem das Handy loslegt. Danach gleitet jede Messung ein
        val countUp = remember { Animatable(0f) }
        var counted by remember { mutableStateOf(false) }
        val firstRate = computing && s.measurements == 1 && !still
        LaunchedEffect(firstRate) {
            if (!firstRate) return@LaunchedEffect
            countUp.snapTo(0f)
            countUp.animateTo(s.hashrate.toFloat(), tween(900, easing = MotionEase))
            counted = true
        }
        if (firstRate && !counted) Text(
            formatRate(countUp.value.toDouble(), locale), style = Type.display(96.sp).copy(lineHeight = 0.92.em), maxLines = 1,
            autoSize = TextAutoSize.StepBased(maxFontSize = 96.sp), modifier = Modifier.clearAndSetSemantics { contentDescription = rateA11y },
        ) else AnimatedContent(
            targetState = if (computing) formatRate(s.hashrate, locale) else "–",
            transitionSpec = {
                if (still) EnterTransition.None togetherWith ExitTransition.None
                else (slideInVertically(tween(180, easing = MotionEase)) { it / 3 } + fadeIn(tween(180))) togetherWith fadeOut(tween(120))
            },
            label = "hashrate",
            // Ein Knoten fuer TalkBack, auch waehrend der Ueberblendung zwei Zahlen stehen
            modifier = Modifier.clearAndSetSemantics { contentDescription = rateA11y },
        ) { value ->
            Text(
                value, style = Type.display(96.sp).copy(lineHeight = 0.92.em), maxLines = 1,
                autoSize = TextAutoSize.StepBased(maxFontSize = 96.sp),
            )
        }
        Kick(stringResource(R.string.running_rate_unit), Modifier.padding(top = 12.dp), color = Brand.inkQuiet)
        // Fachbegriff nur als Zusatz: Laien lesen "Rechenleistung" (Skeptiker-Review 17.10.)
        Text(stringResource(R.string.running_rate_detail), style = Type.data(11.sp, Brand.inkQuiet), modifier = Modifier.padding(top = 4.dp))
        // Der beruhigende Satz gehoert zur Zahl, nicht zwischen Kennzahlen und Ertrag (Design-Review 30.10.)
        Text(
            sentence, style = Type.body(15.sp, Brand.ink), textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
    }

    if (starting) {
        val steps = listOf(
            stringResource(R.string.start_step_memory), stringResource(R.string.start_step_build),
            stringResource(R.string.start_step_server), stringResource(R.string.start_step_job),
        )
        val step = when {
            s.phase == MiningState.Phase.PREPARING -> s.prepStep.coerceIn(0, 1)
            s.pool == null -> 2
            else -> 3
        }
        StartSteps(steps, step, Modifier.padding(top = 28.dp, bottom = 8.dp))
    } else {
        // Puls-Spur in der Mitte (vorher unter "Details"): jeder Job vom Pool laeuft als Strich ein, ein Share hoch
        val (jobs, shares) = trailCounts(s.trail)
        Column(Modifier.padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Kick(stringResource(R.string.details_trail_title), color = Brand.inkQuiet)
            Blockspur(s.trail, s.events, description = stringResource(R.string.running_trail_a11y, jobs, shares))
            Text(stringResource(R.string.details_trail_legend), style = Type.body(12.sp, Brand.inkQuiet))
        }
        RateCurve(s, t)
    }

    val perShare = secondsPerShare(s.shareDifficulty, s.hashrate).takeIf { computing }
    val shareNote = when {
        s.rejected > 0 -> pluralStringResource(R.plurals.share_note_rejected, s.rejected, s.rejected)
        s.lastShareAtMs > 0 -> stringResource(R.string.share_note_last, ago(now - s.lastShareAtMs, t))
        perShare != null -> stringResource(R.string.share_note_first, roughDuration(perShare, t))
        else -> null
    }
    val drain = batteryPerHour(s, now)
    val batteryNote = when {
        s.charging == true -> stringResource(R.string.battery_note_charging)
        drain != null -> stringResource(R.string.battery_note_drain, drain.coerceAtLeast(0.0))
        else -> null
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
        Figure(stringResource(R.string.figure_shares), "${s.accepted}", shareNote, Modifier.weight(1f))
        Figure(
            stringResource(R.string.figure_battery), s.batteryPercent?.let { stringResource(R.string.value_percent, it) } ?: "–",
            batteryNote, Modifier.weight(1f),
        )
        Figure(
            stringResource(R.string.figure_temp), s.batteryTempC?.let { stringResource(R.string.value_celsius, it) } ?: "–",
            s.batteryTempC?.let { stringResource(tempLabel(it)) }, Modifier.weight(1f),
        )
    }

    // Immer sichtbar: Zahlen, Gebuehr/Support im Ertrag, Regionen-Kurzzeile. Eingeklappt: Erklaertexte
    EarningsPanel(poolLoad, s.hashrate, computing, explain)
    LinkRow(stringResource(R.string.how_it_works_show), mark = if (explain) "−" else "+", expanded = explain, onClick = onExplain)

    if (explain) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (perShare != null) Text(
            stringResource(R.string.running_share_interval, roughDuration(perShare, t)),
            style = Type.body(13.sp),
        )
        Text(stringResource(R.string.running_worker_name, worker), style = Type.data(13.sp, Brand.inkMuted))
        dataSinceStart(s.netBytesAtStart, appNetBytes(uid), locale)?.let {
            Text(stringResource(R.string.running_data_since_start, it), style = Type.data(13.sp, Brand.inkMuted))
        }
        // Begriffe, die auf dem Bildschirm stehen, in Laiensprache (Skeptiker-Review 17.10.)
        Kick(stringResource(R.string.glossary_kick), Modifier.padding(top = 10.dp))
        Term(stringResource(R.string.running_rate_unit), stringResource(R.string.glossary_rate))
        Term(stringResource(R.string.figure_shares), stringResource(R.string.running_share_explainer))
        Term(stringResource(R.string.glossary_pool_term), stringResource(R.string.glossary_pool))
        Term(stringResource(R.string.glossary_unconfirmed_term), stringResource(R.string.glossary_unconfirmed))
        Term(stringResource(R.string.glossary_regions_term), stringResource(R.string.glossary_regions))
        Term(stringResource(R.string.running_kick_support), stringResource(R.string.support_text))
    }
}

/** Kurve: links, wie weit sie zurueckreicht (bis die 10 min voll sind), rechts "jetzt". */
@Composable
private fun RateCurve(s: MiningState.Snapshot, t: Texts) {
    val locale = t.locale
    val span = (s.history.size - 1).coerceAtLeast(0) * 10
    val curveA11y = if (s.history.size >= 2) stringResource(
        R.string.running_curve_a11y,
        formatRate(s.history.min().toDouble(), locale), formatRate(s.history.max().toDouble(), locale), formatRate(s.hashrate, locale),
    ) else stringResource(R.string.running_curve_filling)
    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Sparkline(s.history, description = curveA11y)
        Row(Modifier.clearAndSetSemantics {}) {
            Text(
                when {
                    span < 60 -> stringResource(R.string.running_curve_filling)
                    s.history.size >= SPARK_POINTS -> stringResource(R.string.running_curve_start)
                    else -> t.get(R.string.ago_minutes, span / 60)
                },
                style = Type.data(11.sp, Brand.inkQuiet), modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.running_curve_now), style = Type.data(11.sp, Brand.inkQuiet))
        }
    }
}

/** Ein Eintrag der Begriffsliste: Wort hell, Erklaerung gedaempft. */
@Composable
private fun Term(term: String, text: String) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(term, style = Type.body(13.sp, Brand.ink, FontWeight.SemiBold))
        Text(text, style = Type.body(13.sp))
    }
}

/** Netzverkehr dieser App (Bytes hin + zurueck), -1 = nicht gemeldet. */
private fun appNetBytes(uid: Int): Long {
    val rx = android.net.TrafficStats.getUidRxBytes(uid)
    val tx = android.net.TrafficStats.getUidTxBytes(uid)
    return if (rx < 0 || tx < 0) -1 else rx + tx
}

/** Laufzeit als 00:17:42. */
private fun elapsed(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60)
}

/**
 * Eingabefeld im Panel: Mono, ohne Material-Rahmen; Fokus zeigt eine --green-rule-Linie (Rahmen, nie Flaeche).
 * [label] liest TalkBack vor (die sichtbare Eyebrow steht ausserhalb des Felds); Unterlinie borderStrong fuer ≥3:1.
 */
@Composable
private fun Field(
    value: String, onValue: (String) -> Unit, label: String, placeholder: String, next: Boolean,
    onDone: (() -> Unit)? = null, error: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        textStyle = Type.data(16.sp, Brand.ink),
        cursorBrush = SolidColor(Brand.greenPrimary),
        keyboardOptions = plainKeyboard.copy(imeAction = if (next) ImeAction.Next else ImeAction.Done),
        keyboardActions = if (onDone != null) KeyboardActions(onDone = { onDone() }) else KeyboardActions.Default,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).onFocusChanged { focused = it.isFocused }
            // Fehler am Feld: TalkBack liest ihn beim Feld vor, nicht nur als Text darunter (WCAG 3.3.1)
            .semantics { contentDescription = label; if (error != null) error(error) },
        decorationBox = { inner ->
            // Unterlinie am unteren Rand der 48-dp-Flaeche, Text direkt darueber
            Column(Modifier.heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    if (value.isEmpty()) Text(placeholder, style = Type.data(16.sp, Brand.inkQuiet))
                    inner()
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(if (focused) Brand.greenRule else Brand.borderStrong))
            }
        },
    )
}

/** Text-Knopf mit 48-dp-Tippflaeche (Material-Mindestmass), auch wenn die Schrift kleiner ist. */
@Composable
internal fun TextLink(text: String, color: Color = Brand.ink, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(end = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, style = Type.body(14.sp, color, FontWeight.Medium)) }
}

@Composable
private fun QuietButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, shape = Brand.button, border = BorderStroke(1.dp, Brand.borderStrong),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(text, style = Type.body(15.sp, Brand.ink, FontWeight.Medium)) }
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
                    stringResource(R.string.dimmed_cooling), style = Type.display(40.sp, Brand.borderStrong),
                    modifier = Modifier.padding(top = 24.dp),
                )
            } else {
                // Sehr dunkel (borderStrong, ~3,4:1 auf Schwarz): bei 1 % Helligkeit im dunklen Raum lesbar, blendet nicht
                Text(
                    if (computing) formatRate(s.hashrate, locale) else "–",
                    style = Type.display(112.sp, Brand.borderStrong).copy(lineHeight = 0.92.em), maxLines = 1,
                    autoSize = TextAutoSize.StepBased(maxFontSize = 112.sp),
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
            // "H/s · 3 Shares"; das Quadrat davor blitzt bei jedem angenommenen Share gruen
            Row(
                Modifier.padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Square(beat = s.accepted, tick = s.measurements)
                Text(pluralStringResource(R.plurals.dimmed_status, s.accepted, "H/s", s.accepted), style = Type.data(15.sp, Brand.borderStrong))
            }
            // Versalien erst hier: in der Ressource normal geschrieben, damit TalkBack nicht buchstabiert
            val hint = stringResource(R.string.dimmed_hint)
            Text(
                hint.uppercase(), style = Type.kick.copy(color = Brand.borderMid), textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 28.dp).semantics { contentDescription = hint },
            )
        }
    }
}

/** Abbruchgrund: Panel mit Fehler-Rahmen (Coral ist fuer Fehler reserviert). */
@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = Type.body(15.sp, Brand.ink),
        // Abbruchgrund erscheint ohne Zutun: TalkBack sagt ihn sofort an (WCAG 4.1.3)
        modifier = Modifier.fillMaxWidth().background(Brand.surface1, Brand.card)
            .border(1.dp, Brand.coral, Brand.card).padding(16.dp).semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

@Composable
private fun ShareOption(label: String, detail: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .background(if (selected) Brand.surface3 else Brand.canvas, Brand.button)
            // Auswahl nur ueber den Rahmen (--green-rule ist border-only), Flaeche bleibt neutral
            // Ungewaehlt mit sichtbarem Rand (≥3:1, WCAG 1.4.11), gewaehlt in --green-rule
            .border(1.dp, if (selected) Brand.greenRule else Brand.borderMid, Brand.button)
            // selectable statt clickable: Screenreader sagt "ausgewählt" / "nicht ausgewählt"
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = Type.data(17.sp, if (selected) Brand.ink else Brand.inkMuted).copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(2.dp))
        Text(detail, style = Type.body(11.sp, Brand.inkQuiet), textAlign = TextAlign.Center)
    }
}

/**
 * Einordnung der Akku-Temperatur fuer Laien; Text als String-Ressource. "heiss" ab 45 °C, obwohl die App erst ab
 * 50 °C pausiert (weiter unter 45 °C, Stopp ab 55 °C): darueber altert der Akku schneller (Skeptiker-Review 17.10.).
 */
@StringRes
private fun tempLabel(c: Float): Int = when {
    c < 40f -> R.string.temp_normal
    c < 45f -> R.string.temp_warm
    else -> R.string.temp_hot
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


