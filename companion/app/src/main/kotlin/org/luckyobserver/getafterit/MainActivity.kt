package org.luckyobserver.getafterit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import io.rebble.pebblekit2.client.DefaultPebbleInfoRetriever
import io.rebble.pebblekit2.client.PebbleInfoRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.io.File
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xml.sax.SAXException
import java.io.IOException
import java.time.format.DateTimeParseException
import org.luckyobserver.getafterit.cdm.CdmManager
import org.luckyobserver.getafterit.course.CourseStore
import org.luckyobserver.getafterit.hr.StrapStore
import org.luckyobserver.getafterit.ui.StrapState
import org.luckyobserver.getafterit.map.OfflineMap
import org.luckyobserver.getafterit.metrics.RunLibrary
import org.luckyobserver.getafterit.metrics.RunSummary
import org.luckyobserver.getafterit.ui.OfflineMapState
import org.luckyobserver.getafterit.settings.MODE_RUN
import org.luckyobserver.getafterit.settings.activityId
import org.luckyobserver.getafterit.settings.MODE_NAMES
import org.luckyobserver.getafterit.settings.SettingsStore
import org.luckyobserver.getafterit.ui.SettingsScreen
import org.luckyobserver.getafterit.pebble.PebbleListenerService
import org.luckyobserver.getafterit.pebble.PebbleMessenger
import org.luckyobserver.getafterit.pebble.RunSession
import org.luckyobserver.getafterit.pebble.LiveRun
import org.luckyobserver.getafterit.ui.HistoryScreen
import org.luckyobserver.getafterit.ui.ReadyScreen
import org.luckyobserver.getafterit.ui.RunningScreen
import org.luckyobserver.getafterit.ui.SetupItem
import org.luckyobserver.getafterit.ui.SetupScreen

/**
 * Single activity: the setup checklist while anything is missing, otherwise
 * the Ready screen, or the Running screen during a run; Settings via the gear.
 */
class MainActivity : ComponentActivity() {

    private var pebbleConnected by mutableStateOf(false)
    /** A run is in progress ([RunSession.active]); refreshed by a Compose tick. */
    private var runActive by mutableStateOf(false)
    private var runPaused by mutableStateOf(false)
    /** Mirrors [CdmManager.isPaired]; re-read on resume and after pairing. */
    private var paired by mutableStateOf(false)

    private var course by mutableStateOf<CourseStore.Saved?>(null)
    private var showSettings by mutableStateOf(false)
    /** The History screen's runs while it's open, else null. */
    private var history by mutableStateOf<List<RunSummary>?>(null)
    private var importStatus by mutableStateOf<String?>(null)

    // Bumped whenever permissions may have changed, so the setup list recomputes.
    private var permissionCheck by mutableStateOf(0)
    private var courseError by mutableStateOf<String?>(null)

    /**
     * Cached info retriever. PebbleKitAndroid2 binds lazily on first call.
     * Note: per the library README, [PebbleInfoRetriever] works only when the
     * app is in the foreground — fine for Home, which is foreground-only.
     */
    private val infoRetriever: PebbleInfoRetriever by lazy {
        DefaultPebbleInfoRetriever(this)
    }

    /**
     * Runtime permission request handle for the dangerous permissions we need:
     *
     *  - **BLUETOOTH_CONNECT** (API 31+): required by PebbleKitAndroid2 for
     *    its IPC, and — crucially — required by Android 14+'s
     *    `connectedDevice` foreground-service-type check. Without it,
     *    `startForeground(... FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)`
     *    throws `SecurityException` and crashes the process the moment
     *    a run promotes PebbleListenerService.
     *
     * POST_NOTIFICATIONS is deliberately not requested. See the manifest
     * comment near the FOREGROUND_SERVICE block — Android's FGS contract
     * needs a Notification object, not a permission to *display* it.
     */
    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        for ((perm, granted) in grants) {
            Log.d(TAG, "$perm granted=$granted")
        }
        permissionCheck++
    }

    // Android only grants background location in a request of its own, made
    // after foreground location; on 11+ it opens the "Allow all the time" page.
    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "ACCESS_BACKGROUND_LOCATION granted=$granted")
        permissionCheck++
    }

    private val importRunsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) importRuns(uris) }

    private val importFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { tree -> if (tree != null) importRuns(trackFilesIn(tree)) }

    /** The GPX, KML and KMZ files directly inside the picked folder. */
    private fun trackFilesIn(tree: Uri): List<Uri> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return contentResolver.query(children, columns, null, null, null)!!.use { c ->
            buildList {
                while (c.moveToNext()) {
                    if (c.getString(1).substringAfterLast('.').lowercase() in setOf("gpx", "kml", "kmz")) {
                        add(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)))
                    }
                }
            }
        }
    }

    // The files come from the user, so a bad one is reported on screen rather than crashing.
    private fun importRuns(uris: List<Uri>) {
        importStatus = "Importing…"
        lifecycleScope.launch {
            importStatus = withContext(Dispatchers.IO) {
                var added = 0
                var skipped = 0
                val problems = mutableListOf<String>()
                for (uri in uris) {
                    val name = CourseStore.displayName(this@MainActivity, uri)
                    try {
                        val result = contentResolver.openInputStream(uri)!!.use { RunLibrary.import(this@MainActivity, name, it) }
                        added += result.added
                        skipped += result.skipped
                    } catch (e: IllegalArgumentException) {
                        problems += "$name: ${e.message}"
                    } catch (e: IOException) {
                        problems += "$name: ${e.message}"
                    } catch (e: SAXException) {
                        problems += "$name isn't valid XML: ${e.message}"
                    } catch (e: DateTimeParseException) {
                        problems += "$name has a time it can't read: ${e.parsedString}"
                    }
                }
                (listOf("Imported $added, skipped $skipped already here.") + problems).joinToString("\n")
            }
            history = withContext(Dispatchers.IO) { RunLibrary.all(this@MainActivity) }
        }
    }

    private val openCourseLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) loadCourse(uri) }

    private var offlineMapName by mutableStateOf<String?>(null)
    private var offlineThemeName by mutableStateOf<String?>(null)
    private var offlineStatus by mutableStateOf<String?>(null)

    private val openMapLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importOffline("map") { OfflineMap.importMap(this, uri) } }

    private val openThemeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importOffline("style") { OfflineMap.importTheme(this, uri) } }

    // The files come from the user, so a bad one is reported on screen rather than crashing.
    private fun importOffline(what: String, import: () -> Unit) {
        offlineStatus = "Importing $what…"
        lifecycleScope.launch {
            offlineStatus = withContext(Dispatchers.IO) {
                try {
                    import()
                    "Imported."
                } catch (e: IllegalArgumentException) {
                    e.message
                } catch (e: IOException) {
                    "Couldn't import the $what: ${e.message}"
                }
            }
            refreshOfflineNames()
        }
    }

    private fun refreshOfflineNames() {
        offlineMapName = OfflineMap.mapName(this)
        offlineThemeName = OfflineMap.themeName(this)
    }

    /**
     * Receives the CDM pairing dialog's result. On RESULT_OK the
     * association exists and Android grants the background exemption for our
     * UID. Refresh [paired] so the Home screen updates.
     */
    private var strapName by mutableStateOf<String?>(null)

    private val strapPairingLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        // Android 13+ delivers the strap through the association callback instead.
        CdmManager.strapFromResult(result.data)?.let { (address, name) -> strapChosen(address, name) }
    }

    private fun strapChosen(address: String, name: String?) {
        StrapStore.save(this, address, name)
        runOnUiThread {
            strapName = StrapStore.name(this)
            settingsChanged()
        }
    }

    /** Tell the service to re-check the mode and strap after a change. */
    private fun settingsChanged() {
        startService(Intent(this, PebbleListenerService::class.java).setAction(PebbleListenerService.ACTION_SETTINGS_CHANGED))
    }

    private var mode by mutableStateOf(MODE_RUN)

    private fun onModePicked(picked: Int) {
        val saved = SettingsStore.get(this).copy(mode = picked)
        SettingsStore.save(this, saved)
        mode = picked
        lifecycleScope.launch { PebbleMessenger.send(this@MainActivity, saved.toWatch()) }
        settingsChanged()
    }

    private val pairingLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val ok = result.resultCode == RESULT_OK
        Log.d(TAG, "CDM pairing dialog result: ok=$ok")
        paired = CdmManager.isPaired(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk=35 forces edge-to-edge on Android 15+ (no opt-out via
        // setDecorFitsSystemWindows — that's a no-op when enforced). Insets
        // are consumed at the screen level via WindowInsets.safeDrawing in
        // HomeScreen / FirstLaunchScreen.
        enableEdgeToEdge()
        refreshRunState()
        paired = CdmManager.isPaired(this)
        course = CourseStore.get(this)
        strapName = StrapStore.name(this)
        refreshOfflineNames()
        handleCourseIntent(intent)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppContent()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRunState()
        paired = CdmManager.isPaired(this)
        permissionCheck++
        refreshPebbleConnection()
    }

    /**
     * What still needs doing, each asked for only when the user taps it
     * (Android's guidance: request in context, say why). Empty when ready.
     */
    private fun missingSetup(): List<SetupItem> {
        val bluetooth = Build.VERSION.SDK_INT < 31 || isGranted(Manifest.permission.BLUETOOTH_CONNECT)
        val location = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        return buildList {
            if (!bluetooth) add(SetupItem(
                "Allow Bluetooth", "Get After It talks to your watch through the Pebble app.", "Allow",
            ) { runtimePermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) })
            if (!location) add(SetupItem(
                "Allow location", "Records your run and shows where you are on the watch map.", "Allow",
            ) {
                runtimePermissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            })
            if (!isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) add(SetupItem(
                "Allow location all the time",
                "Your phone is usually locked while you use the watch. Choose \"Allow all the time\".",
                "Open setting", enabled = location,
            ) { backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) })
            // GrapheneOS lets the user switch network access off per app.
            if (!isGranted(Manifest.permission.INTERNET)) add(SetupItem(
                "Allow network", "Map tiles are downloaded from the internet. Turn on Network in the app's permissions.",
                "Open app settings",
            ) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            })
            if (!paired) add(SetupItem(
                "Pair the watch for background access",
                "Lets the watch start and stop runs while this app is closed.",
                "Pair", enabled = bluetooth,
            ) { requestPairing() })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCourseIntent(intent)
    }

    /** A GPX opened or shared from another app. */
    private fun handleCourseIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        loadCourse(uri)
    }

    // The file comes from the user, so a bad one is reported on screen rather than crashing.
    private fun loadCourse(uri: Uri) {
        try {
            CourseStore.load(this, uri)
            courseError = null
        } catch (e: IllegalArgumentException) {
            courseError = e.message
        } catch (e: IOException) {
            courseError = e.message
        } catch (e: SAXException) {
            courseError = "Not a GPX file: ${e.message}"
        }
        course = CourseStore.get(this)
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Probe [PebbleInfoRetriever.getConnectedWatches] for the current
     * connection state. The retriever returns a [kotlinx.coroutines.flow.Flow];
     * we take the first emission and update [pebbleConnected]. WorkerThread
     * annotation requires us to call from a background dispatcher.
     */
    private fun refreshPebbleConnection() {
        lifecycleScope.launch {
            val watches = try {
                withContext(Dispatchers.IO) {
                    infoRetriever.getConnectedWatches().firstOrNull().orEmpty()
                }
            } catch (e: Exception) {
                Log.d(TAG, "getConnectedWatches failed (Pebble app not reachable?)", e)
                emptyList()
            }
            pebbleConnected = watches.isNotEmpty()
        }
    }

    /** Driven by the Home screen "Pair Pebble for background access" button. */
    private fun requestPairing() {
        val dispatched = CdmManager.requestPairing(this, pairingLauncher)
        if (!dispatched) {
            // The bonded Pebble wasn't found — usually because the user
            // hasn't completed the Pebble Android app's pairing flow yet.
            // The logcat warning from CdmManager carries the cause.
            Log.w(TAG, "CDM pairing not dispatched (Pebble not bonded yet?)")
        }
    }

    @Composable
    private fun AppContent() {
        val stats by LiveRun.stats.collectAsState()
        val remainingM by LiveRun.remainingM.collectAsState()
        val trail by LiveRun.trail.collectAsState()
        var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

        // 1 Hz tick to keep [runActive] in sync with [RunSession] while
        // MainActivity is foreground. RunSession is a @Volatile singleton and
        // not Compose-observable on its own; this is the cheapest way to flip
        // between the Ready and Running screens when the watch starts or stops a run.
        LaunchedEffect(Unit) {
            var tick = 0
            while (true) {
                delay(1000)
                nowMs = System.currentTimeMillis()
                refreshRunState()
                mode = SettingsStore.get(this@MainActivity).mode  // the watch can change it too
                if (++tick % 5 == 0) refreshPebbleConnection()
            }
        }

        permissionCheck  // read so the setup list recomputes when it changes
        val missing = missingSetup()
        val miles = SettingsStore.get(this).miles

        if (showSettings) {
            BackHandler { showSettings = false }
            SettingsScreen(
                initial = SettingsStore.get(this),
                offline = OfflineMapState(
                    mapName = offlineMapName,
                    themeName = offlineThemeName,
                    status = offlineStatus,
                    onPickMap = { openMapLauncher.launch(arrayOf("*/*")) },
                    onPickTheme = { openThemeLauncher.launch(arrayOf("*/*")) },
                    onClearTheme = {
                        OfflineMap.clearTheme(this)
                        refreshOfflineNames()
                    },
                    onOpenPage = { startActivity(Intent(Intent.ACTION_VIEW, it.toUri())) },
                ),
                strap = StrapState(
                    name = strapName,
                    onPair = { CdmManager.requestStrapPairing(this, strapPairingLauncher, ::strapChosen) },
                    onForget = {
                        StrapStore.clear(this)
                        strapName = null
                        settingsChanged()
                    },
                ),
                onSave = { saved ->
                    SettingsStore.save(this, saved)
                    lifecycleScope.launch { PebbleMessenger.send(this@MainActivity, saved.toWatch()) }
                    settingsChanged()
                    showSettings = false
                },
                onCancel = { showSettings = false },
            )
        } else if (history != null) {
            BackHandler { history = null }
            HistoryScreen(
                runs = history.orEmpty(),
                miles = miles,
                importStatus = importStatus,
                onImportFiles = { importRunsLauncher.launch(arrayOf("*/*")) },
                onImportFolder = { importFolderLauncher.launch(null) },
                onOpenRun = ::shareRun,
                onBack = { history = null },
            )
        } else if (runActive) {
            RunningScreen(
                stats = stats,
                remainingM = remainingM,
                course = course,
                trail = trail,
                nowMs = nowMs,
                miles = miles,
                paused = runPaused,
                hiking = activityId(mode) == "hiking",
                onStop = ::onStopTapped,
                onSettings = { showSettings = true },
            )
        } else if (missing.isNotEmpty()) {
            SetupScreen(missing)
        } else {
            ReadyScreen(
                mode = mode,
                enabledModes = MODE_NAMES.indices.filter { SettingsStore.get(this).modeEnabled(it) },
                onModePicked = ::onModePicked,
                watchConnected = pebbleConnected,
                course = course,
                courseError = courseError,
                miles = miles,
                onLoadCourse = { openCourseLauncher.launch(arrayOf("*/*")) },
                onClearCourse = {
                    CourseStore.clear(this)
                    course = null
                    courseError = null
                },
                savedCourses = { CourseStore.library(this) },
                onPickCourse = {
                    CourseStore.pick(this, it)
                    course = CourseStore.get(this)
                    courseError = null
                },
                onHistory = {
                    importStatus = null
                    lifecycleScope.launch { history = withContext(Dispatchers.IO) { RunLibrary.all(this@MainActivity) } }
                },
                onStart = ::onStartTapped,
                onSettings = { showSettings = true },
            )
        }
    }

    /** Opens the watchapp and starts the run through the service, as the watch's hold does. */
    private fun onStartTapped() {
        lifecycleScope.launch { PebbleMessenger.startWatchapp(this@MainActivity) }
        startService(Intent(this, PebbleListenerService::class.java)
            .setAction(PebbleListenerService.ACTION_START))
    }

    /** Offers a saved run's GPX to other apps (Strava, Files...). */
    private fun shareRun(run: RunSummary) {
        val file = File(RunLibrary.dir(this), run.file)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/gpx+xml")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, run.title))
    }

    /** Stops through the service so it also leaves the foreground and forgets the run. */
    private fun onStopTapped() {
        Log.d(TAG, "Stop Run → PebbleListenerService.ACTION_STOP")
        startService(Intent(this, PebbleListenerService::class.java)
            .setAction(PebbleListenerService.ACTION_STOP))
    }

    private fun refreshRunState() {
        runActive = RunSession.active(this)
        runPaused = RunSession.paused(this)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
