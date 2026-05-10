import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.event.EventHandler
import javafx.scene.Scene
import javafx.concurrent.Worker
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.w3c.dom.Document
import java.awt.KeyboardFocusManager
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors
import java.util.prefs.Preferences
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

const val DEFAULT_MAP_LAT = 51.78962
const val DEFAULT_MAP_LON = 6.14120
const val IP_GEOLOCATION_URL = "https://ipapi.co/json/"
const val MAX_MAP_TRACK_POINTS = 3000

var firstIntentSent = false
var lastIntentSentTime = 0L
var currentIndex = 0
var refinedTrackPoints = listOf<Pair<Double, Double>>()
var adbPath = "/usr/bin/adb"

private var playbackTimer: Timer? = null

private var currentVehicleLatLon: Pair<Double, Double>? = null
private var currentVehicleHeadingDeg = 0.0

private val initialMapCenterRequested = AtomicBoolean(false)
private val approximateUserLocation = AtomicReference<Pair<Double, Double>?>(null)

private val mapBridge: MapStateServer by lazy { MapStateServer().also { it.start() } }

enum class DriveMode {
    GPX,
    FREE_DRIVE
}

private val prefs = Preferences.userRoot().node("gpxplayer")

fun getSavedAdbPath(): String? = prefs.get("adb_path", null)

fun saveAdbPath(path: String) {
    prefs.put("adb_path", path)
}

private class MapStateServer {
    private val lock = Any()
    private lateinit var server: HttpServer

    var port: Int = 0
        private set

    private var mapTrack: List<Pair<Double, Double>> = emptyList()
    private var trackRevision = 0L
    private var vehicleRevision = 0L
    private var vehicleVisible = false
    private var vehicleLat = DEFAULT_MAP_LAT
    private var vehicleLon = DEFAULT_MAP_LON
    private var vehicleHeading = 0.0
    private var vehicleHeadingUp = false
    private var vehicleFollow = false
    private var centerCrossRevision = 0L
    private var centerCrossVisible = false
    private var resetRevision = 0L
    private var zoomRevision = 0L
    private var zoomDelta = 0
    private var targetCenterRevision = 0L
    private var targetCenterLat = DEFAULT_MAP_LAT
    private var targetCenterLon = DEFAULT_MAP_LON
    private var lastMapCenter = Pair(DEFAULT_MAP_LAT, DEFAULT_MAP_LON)

    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/state") { exchange -> handleState(exchange) }
        server.executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "map-state-server").apply { isDaemon = true }
        }
        server.start()
        port = server.address.port
        Runtime.getRuntime().addShutdownHook(Thread { server.stop(0) })
        println("Map state server listening on 127.0.0.1:$port")
    }

    fun setTrack(points: List<Pair<Double, Double>>) = synchronized(lock) {
        mapTrack = points
        trackRevision++
    }

    fun updateVehicle(lat: Double, lon: Double, heading: Double, headingUp: Boolean, follow: Boolean) = synchronized(lock) {
        vehicleVisible = true
        vehicleLat = lat
        vehicleLon = lon
        vehicleHeading = heading
        vehicleHeadingUp = headingUp
        vehicleFollow = follow
        vehicleRevision++
    }

    fun hideVehicle() = synchronized(lock) {
        vehicleVisible = false
        vehicleRevision++
    }

    fun showCenterCross(show: Boolean) = synchronized(lock) {
        centerCrossVisible = show
        centerCrossRevision++
    }

    fun resetFrame() = synchronized(lock) {
        resetRevision++
    }

    fun zoom(delta: Int) = synchronized(lock) {
        zoomDelta = delta
        zoomRevision++
    }

    fun setTargetCenter(lat: Double, lon: Double) = synchronized(lock) {
        targetCenterLat = lat
        targetCenterLon = lon
        targetCenterRevision++
    }

    fun lastCenter(): Pair<Double, Double> = synchronized(lock) { lastMapCenter }

    private fun handleState(exchange: HttpExchange) {
        try {
            val query = parseQuery(exchange.requestURI.rawQuery.orEmpty())
            val lat = query["lat"]?.toDoubleOrNull()
            val lon = query["lon"]?.toDoubleOrNull()
            if (lat != null && lon != null) {
                synchronized(lock) {
                    lastMapCenter = Pair(lat, lon)
                }
            }

            val clientTrackRevision = query["trackRevision"]?.toLongOrNull() ?: -1L
            val response = synchronized(lock) {
                buildJsonState(includeTrack = clientTrackRevision != trackRevision)
            }
            val bytes = response.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
            exchange.responseHeaders.add("Cache-Control", "no-store")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (e: Exception) {
            val bytes = """{"error":"state unavailable"}""".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(500, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } finally {
            exchange.close()
        }
    }

    private fun buildJsonState(includeTrack: Boolean): String = buildString {
        append('{')
        append("\"trackRevision\":").append(trackRevision)
        if (includeTrack) {
            append(",\"track\":[")
            mapTrack.forEachIndexed { index, point ->
                if (index > 0) append(',')
                append('[').append(point.first).append(',').append(point.second).append(']')
            }
            append(']')
        }
        append(",\"vehicleRevision\":").append(vehicleRevision)
        append(",\"vehicle\":{")
        append("\"visible\":").append(vehicleVisible)
        append(",\"lat\":").append(vehicleLat)
        append(",\"lon\":").append(vehicleLon)
        append(",\"heading\":").append(vehicleHeading)
        append(",\"headingUp\":").append(vehicleHeadingUp)
        append(",\"follow\":").append(vehicleFollow)
        append('}')
        append(",\"centerCrossRevision\":").append(centerCrossRevision)
        append(",\"centerCrossVisible\":").append(centerCrossVisible)
        append(",\"resetRevision\":").append(resetRevision)
        append(",\"zoomRevision\":").append(zoomRevision)
        append(",\"zoomDelta\":").append(zoomDelta)
        append(",\"targetCenterRevision\":").append(targetCenterRevision)
        append(",\"targetCenter\":{")
        append("\"lat\":").append(targetCenterLat)
        append(",\"lon\":").append(targetCenterLon)
        append('}')
        append('}')
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        return rawQuery.split('&')
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator < 0) return@mapNotNull null
                val key = URLDecoder.decode(part.substring(0, separator), StandardCharsets.UTF_8)
                val value = URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8)
                key to value
            }
            .toMap()
    }
}

fun initializeJavaFX() {
    mapBridge
    if (!Platform.isFxApplicationThread()) {
        Platform.startup {}
    }
}

@Composable
@Preview
fun App() {
    var speed by remember { mutableStateOf(60f) }
    var position = remember { mutableStateOf(0f) }
    var logMessages by remember { mutableStateOf(listOf<String>()) }
    var selectedFile by remember { mutableStateOf<String?>(null) }
    var playing = remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var driveMode by remember { mutableStateOf(DriveMode.GPX) }
    var freeDriveSpeed by remember { mutableStateOf(0.0) }
    var freeDriveHeading by remember { mutableStateOf(0.0) }
    var freeDrivePositionSet by remember { mutableStateOf(false) }

    val webEngine = remember { mutableStateOf<WebEngine?>(null) }
    val freeDriveController = remember { FreeDriveController { webEngine.value } }
    val keyDispatcher = remember {
        FreeDriveKeyDispatcher(
            isEnabled = { driveMode == DriveMode.FREE_DRIVE && freeDriveController.isActive },
            onAccelerate = {
                freeDriveController.accelerate()
                freeDriveSpeed = freeDriveController.speedKmh
            },
            onDecelerate = {
                freeDriveController.decelerate()
                freeDriveSpeed = freeDriveController.speedKmh
            },
            onTurnLeft = {
                freeDriveController.turnLeft()
                freeDriveHeading = freeDriveController.headingDeg
            },
            onTurnRight = {
                freeDriveController.turnRight()
                freeDriveHeading = freeDriveController.headingDeg
            }
        )
    }

    adbPath = getSavedAdbPath() ?: "/usr/bin/adb"
    startAndroidApp()

    fun enterFreeDrive() {
        if (driveMode == DriveMode.FREE_DRIVE) return
        if (playing.value) {
            playing.value = false
            paused = false
            stopPlayGpxFile(webEngine.value, position, playing, false)
        }
        driveMode = DriveMode.FREE_DRIVE
        freeDriveController.stop()
        freeDriveSpeed = 0.0
        freeDriveHeading = 0.0
        freeDrivePositionSet = false
        resetMapFrame(webEngine.value)
        hideVehicleOnMap(webEngine.value)
        showCenterCross(webEngine.value)
        logMessages = logMessages + "Free drive enabled. Set position to start."
    }

    fun enterGpxMode() {
        if (driveMode == DriveMode.GPX) return
        driveMode = DriveMode.GPX
        freeDriveController.stop()
        freeDrivePositionSet = false
        logMessages = logMessages + "Free drive disabled."
        hideCenterCross(webEngine.value)
        resetMapFrame(webEngine.value)
        if (refinedTrackPoints.isNotEmpty()) {
            moveMarkerToIndex(webEngine.value, currentIndex, headingUp = false, follow = false)
            if (refinedTrackPoints.size > 1) {
                position.value = ((currentIndex.toFloat() / (refinedTrackPoints.size - 1)) * 100f)
            } else {
                position.value = 0f
            }
        } else {
            currentVehicleLatLon?.let { (lat, lon) ->
                updateVehicleOnMap(webEngine.value, lat, lon, currentVehicleHeadingDeg, headingUp = false, follow = false)
            }
        }
    }

    fun setFreeDrivePositionFromCenter() {
        if (webEngine.value == null) return
        val (lat, lon) = mapBridge.lastCenter()
        val initialHeading = currentVehicleHeadingDeg
        freeDriveController.start(Pair(lat, lon), initialHeading)
        freeDriveSpeed = freeDriveController.speedKmh
        freeDriveHeading = freeDriveController.headingDeg
        freeDrivePositionSet = true
        hideCenterCross(webEngine.value)
        updateVehicleState(lat, lon, freeDriveHeading)
        updateVehicleOnMap(webEngine.value, lat, lon, freeDriveHeading, headingUp = true, follow = true)
        logMessages = logMessages + "Free drive position set."
    }

    fun clearFreeDrivePosition() {
        freeDriveController.stop()
        freeDriveSpeed = 0.0
        freeDriveHeading = 0.0
        freeDrivePositionSet = false
        clearVehicleState()
        hideVehicleOnMap(webEngine.value)
        showCenterCross(webEngine.value)
        resetMapFrame(webEngine.value)
        logMessages = logMessages + "Free drive position cleared."
    }

    DisposableEffect(Unit) {
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        manager.addKeyEventDispatcher(keyDispatcher)
        onDispose {
            manager.removeKeyEventDispatcher(keyDispatcher)
        }
    }

    MaterialTheme(colors = AppColorPalette) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = {
                    selectedFile = openFilePicker(webEngine.value, { position })
                    logMessages = logMessages + "Selected file: ${selectedFile ?: "None"}"

                }, colors = ButtonDefaults.buttonColors(contentColor = Color.White),
                    enabled = driveMode == DriveMode.GPX && !playing.value) {
                    Text("Load GPX")
                }

                selectedFile?.let {
                    Text("File: $it", modifier = Modifier.padding(8.dp))
                }

                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        adbPath,
                        style = MaterialTheme.typography.caption,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    IconButton(
                        onClick = {
                            val chooser = JFileChooser().apply {
                                dialogTitle = "Select ADB Executable"
                                fileSelectionMode = JFileChooser.FILES_ONLY
                            }

                            val result = chooser.showOpenDialog(null)
                            if (result == JFileChooser.APPROVE_OPTION) {
                                val selected = chooser.selectedFile.absolutePath
                                adbPath = selected
                                saveAdbPath(selected)
                                logMessages = logMessages + "ADB Path set to: $selected"
                            } else {
                                logMessages = logMessages + "ADB path selection canceled."
                            }
                        }
                    ) {
                        Image(
                            painter = painterResource("icons/adb.svg"),
                            contentDescription = "Set ADB Path",
                            modifier = Modifier.size(24.dp)
                        )
                    }

                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Mode:")
                ModeToggleButton(
                    label = "GPX",
                    selected = driveMode == DriveMode.GPX,
                    onClick = { enterGpxMode() }
                )
                ModeToggleButton(
                    label = "Free Drive",
                    selected = driveMode == DriveMode.FREE_DRIVE,
                    onClick = { enterFreeDrive() }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (driveMode == DriveMode.GPX) {
                Text("Speed: ${speed.toInt()} km/h")
                Slider(
                    value = speed,
                    onValueChange = { speed = it },
                    valueRange = 0f..130f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text("Position: ${position.value.toInt()} %")
                Slider(
                    value = position.value,
                    onValueChange = {
                        position.value = it

                        if (refinedTrackPoints.isNotEmpty()) {
                            val index = (position.value / 100f * (refinedTrackPoints.lastIndex)).toInt()
                                .coerceIn(0, refinedTrackPoints.lastIndex)
                            currentIndex = index

                            val (lat1, lon1) = refinedTrackPoints[index]
                            val heading = headingForIndex(index)
                            updateVehicleState(lat1, lon1, heading)
                            updateVehicleOnMap(webEngine.value, lat1, lon1, heading, headingUp = false, follow = false)

                            sendIntent(lat1, lon1, speed.toInt())
                        }
                    },
                    valueRange = 0f..100f,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = refinedTrackPoints.isNotEmpty()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row {
                    Button(
                        onClick = {
                            if (!playing.value) {
                                if (currentIndex == refinedTrackPoints.size - 1) {
                                    currentIndex = 0
                                    moveMarkerToIndex(webEngine.value, currentIndex, headingUp = false, follow = false)
                                }
                                playing.value = true
                                paused = false
                                playGpxFile(webEngine.value, { speed.toInt() }, { paused }, position, playing)
                            } else {
                                paused = !paused
                            }
                        },
                        colors = ButtonDefaults.buttonColors(contentColor = Color.White),
                        enabled = refinedTrackPoints.isNotEmpty()
                    ) {
                        val iconName = if (!playing.value || paused) "play" else "pause"
                        Image(
                            painter = painterResource("icons/$iconName.svg"),
                            contentDescription = iconName.replaceFirstChar { it.uppercase() },
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            playing.value = false
                            paused = false
                            stopPlayGpxFile(webEngine.value, position, playing, true)
                        },
                        colors = ButtonDefaults.buttonColors(contentColor = Color.White),
                        enabled = playing.value
                    ) {
                        Image(
                            painter = painterResource("icons/stop.svg"),
                            contentDescription = "Stop",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            } else {
                val speedLabel = if (freeDriveSpeed < 0) {
                    "-${abs(freeDriveSpeed).toInt()}"
                } else {
                    freeDriveSpeed.toInt().toString()
                }
                Text("Free drive (heading-up)")
                if (freeDrivePositionSet) {
                    Text("Speed: $speedLabel km/h")
                    Text("Heading: ${freeDriveHeading.toInt()}°")
                    Text("Controls: ↑/↓ accelerate/decelerate, ←/→ steer")
                } else {
                    Text("Set position to enable controls.")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (driveMode == DriveMode.FREE_DRIVE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { setFreeDrivePositionFromCenter() },
                        colors = ButtonDefaults.buttonColors(contentColor = Color.White),
                        enabled = !freeDrivePositionSet && webEngine.value != null
                    ) {
                        Text("Set Position")
                    }
                    Button(
                        onClick = { clearFreeDrivePosition() },
                        colors = ButtonDefaults.buttonColors(contentColor = Color.White),
                        enabled = freeDrivePositionSet
                    ) {
                        Text("Clear position")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (freeDrivePositionSet) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Zoom:")
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { zoomMap(webEngine.value, zoomIn = false) },
                            colors = ButtonDefaults.buttonColors(contentColor = Color.White),
                            enabled = webEngine.value != null
                        ) {
                            Text("−")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { zoomMap(webEngine.value, zoomIn = true) },
                            colors = ButtonDefaults.buttonColors(contentColor = Color.White),
                            enabled = webEngine.value != null
                        ) {
                            Text("+")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(end = 1.dp) // optional: avoids layout jitter with thin scrollbars
            ) {
                MapView(webEngine)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Logs:")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp) // ⬅️ ~6 lines of text at 20sp lineHeight
            ) {
                SelectionContainer {
                    LazyColumn {
                        items(logMessages) { log ->
                            Text(
                                log,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MapView(webEngineState: MutableState<WebEngine?>, modifier: Modifier = Modifier.fillMaxSize()) {
    val mapResourceUrl = remember {
        Thread.currentThread().contextClassLoader.getResource("map/map.html")
            ?: object {}.javaClass.classLoader.getResource("map/map.html")
    }
    val panelState = remember { mutableStateOf<JFXPanel?>(null) }

    LaunchedEffect(mapResourceUrl) {
        Platform.runLater {
            if (mapResourceUrl == null) {
                println("❌ map/map.html was not found on the application classpath.")
                return@runLater
            }
            val mapUrl = "${mapResourceUrl.toExternalForm()}#statePort=${mapBridge.port}"
            println("🗺️ Loading map resource from: $mapUrl")
            val panel = createWebViewPanel(mapUrl, webEngineState)
            panelState.value = panel
        }
    }

    if (panelState.value != null) {
        SwingPanel(
            factory = { panelState.value!! },
            modifier = modifier
        )
    }
}

fun createWebViewPanel(url: String, webEngineState: MutableState<WebEngine?>): JFXPanel {
    val panel = JFXPanel()

    Platform.runLater {
        val webView = WebView()
        val webEngine = webView.engine
        webEngine.load(url)

        webEngine.onAlert = EventHandler { event ->
            println("🖥️ JavaScript Console: ${event.data}")
        }
        webEngine.loadWorker.exceptionProperty().addListener { _, _, ex ->
            if (ex != null) {
                println("❌ Map load exception: ${ex.javaClass.simpleName}: ${ex.message}")
            }
        }
        webEngineState.value = webEngine

        panel.scene = Scene(webView)

        webEngine.loadWorker.stateProperty().addListener { _, _, newState ->
            when (newState) {
                Worker.State.SUCCEEDED -> {
                    println("✅ Map HTML loaded: $url")
                    println("✅ Map WebView ready.")
                }
                Worker.State.FAILED -> {
                    val ex = webEngine.loadWorker.exception
                    println("❌ Map failed to load from $url")
                    if (ex != null) {
                        println("❌ Failure cause: ${ex.javaClass.name}: ${ex.message}")
                    }
                }
                Worker.State.CANCELLED -> println("⚠️ Map load cancelled for $url")
                else -> Unit
            }
        }

    }

    return panel
}

fun parseGpxFile(filePath: String): List<Pair<Double, Double>> {
    val gpxFile = File(filePath)
    if (!gpxFile.exists()) {
        println("❌ GPX file not found!")
        return emptyList()
    }

    val trackPoints = mutableListOf<Pair<Double, Double>>()

    val factory = DocumentBuilderFactory.newInstance()
    val builder = factory.newDocumentBuilder()
    val doc: Document = builder.parse(gpxFile)
    doc.documentElement.normalize()

    val nodeList = doc.getElementsByTagName("trkpt")

    for (i in 0 until nodeList.length) {
        val node = nodeList.item(i)
        val attributes = node.attributes
        val lat = attributes.getNamedItem("lat").nodeValue.toDouble()
        val lon = attributes.getNamedItem("lon").nodeValue.toDouble()

        trackPoints.add(Pair(lat, lon))
    }

    println("✅ Parsed ${trackPoints.size} track points from GPX file!")
    return trackPoints
}

fun sendGpxToMap(webEngine: WebEngine?, trackPoints: List<Pair<Double, Double>>) {
    if (webEngine == null) return
    if (trackPoints.isEmpty()) return

    val mapTrack = prepareTrackForMap(trackPoints)
    println("Publishing ${mapTrack.size} GPX map points to map state.")
    mapBridge.setTrack(mapTrack)
}

private fun prepareTrackForMap(trackPoints: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
    val deduplicated = trackPoints.fold(mutableListOf<Pair<Double, Double>>()) { points, point ->
        if (points.lastOrNull() != point) {
            points.add(point)
        }
        points
    }

    if (deduplicated.size <= MAX_MAP_TRACK_POINTS) return deduplicated

    val step = kotlin.math.ceil(deduplicated.size.toDouble() / MAX_MAP_TRACK_POINTS).toInt()
    val sampled = deduplicated.filterIndexed { index, _ -> index % step == 0 }.toMutableList()
    val lastPoint = deduplicated.last()
    if (sampled.lastOrNull() != lastPoint) {
        sampled.add(lastPoint)
    }
    return sampled
}

fun maybeCenterMapToApproximateLocation(webEngine: WebEngine) {
    if (!initialMapCenterRequested.compareAndSet(false, true)) return

    Thread({
        val location = fetchApproximateLocationCached()
        Platform.runLater {
            if (refinedTrackPoints.isNotEmpty() || currentVehicleLatLon != null) return@runLater
            val (lat, lon) = location ?: Pair(DEFAULT_MAP_LAT, DEFAULT_MAP_LON)
            mapBridge.setTargetCenter(lat, lon)
        }
    }, "ip-geolocation").apply { isDaemon = true }.start()
}

fun fetchApproximateLocation(): Pair<Double, Double>? {
    return try {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build()
        val request = HttpRequest.newBuilder(URI.create(IP_GEOLOCATION_URL))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return null
        parseLatLon(response.body())
    } catch (e: Exception) {
        null
    }
}

fun fetchApproximateLocationCached(): Pair<Double, Double>? {
    val cached = approximateUserLocation.get()
    if (cached != null) return cached
    val fetched = fetchApproximateLocation()
    if (fetched != null) {
        approximateUserLocation.compareAndSet(null, fetched)
    }
    return fetched ?: approximateUserLocation.get()
}

fun parseLatLon(json: String): Pair<Double, Double>? {
    val latMatch = Regex("\"latitude\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").find(json)
    val lonMatch = Regex("\"longitude\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").find(json)
    if (latMatch == null || lonMatch == null) return null
    val lat = latMatch.groupValues[1].toDoubleOrNull() ?: return null
    val lon = lonMatch.groupValues[1].toDoubleOrNull() ?: return null
    return Pair(lat, lon)
}

fun zoomMap(webEngine: WebEngine?, zoomIn: Boolean) {
    if (webEngine == null) return
    mapBridge.zoom(if (zoomIn) 1 else -1)
}

fun resetMapFrame(webEngine: WebEngine?) {
    if (webEngine == null) return
    mapBridge.resetFrame()
}

@Composable
fun ModeToggleButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                backgroundColor = AppColorPalette.primary,
                contentColor = AppColorPalette.onPrimary
            )
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColorPalette.primary),
            border = BorderStroke(1.dp, AppColorPalette.primary)
        ) {
            Text(label)
        }
    }
}

fun updateVehicleState(lat: Double, lon: Double, headingDeg: Double) {
    currentVehicleLatLon = Pair(lat, lon)
    currentVehicleHeadingDeg = headingDeg
}

fun clearVehicleState() {
    currentVehicleLatLon = null
    currentVehicleHeadingDeg = 0.0
}

fun updateVehicleOnMap(
    webEngine: WebEngine?,
    lat: Double,
    lon: Double,
    headingDeg: Double,
    headingUp: Boolean,
    follow: Boolean
) {
    if (webEngine == null) return
    mapBridge.updateVehicle(lat, lon, headingDeg, headingUp, follow)
}

fun hideVehicleOnMap(webEngine: WebEngine?) {
    if (webEngine == null) return
    mapBridge.hideVehicle()
}

fun showCenterCross(webEngine: WebEngine?) {
    if (webEngine == null) return
    mapBridge.showCenterCross(true)
}

fun hideCenterCross(webEngine: WebEngine?) {
    if (webEngine == null) return
    mapBridge.showCenterCross(false)
}

fun headingForIndex(index: Int): Double {
    if (refinedTrackPoints.isEmpty()) return 0.0
    val safeIndex = index.coerceIn(0, refinedTrackPoints.lastIndex)
    return when {
        safeIndex < refinedTrackPoints.lastIndex -> {
            val (lat1, lon1) = refinedTrackPoints[safeIndex]
            val (lat2, lon2) = refinedTrackPoints[safeIndex + 1]
            calculateHeading(lat1, lon1, lat2, lon2)
        }
        safeIndex > 0 -> {
            val (lat1, lon1) = refinedTrackPoints[safeIndex - 1]
            val (lat2, lon2) = refinedTrackPoints[safeIndex]
            calculateHeading(lat1, lon1, lat2, lon2)
        }
        else -> 0.0
    }
}

fun moveMarkerToIndex(webEngine: WebEngine?, index: Int, headingUp: Boolean, follow: Boolean) {
    if (refinedTrackPoints.isEmpty()) return
    val safeIndex = index.coerceIn(0, refinedTrackPoints.lastIndex)
    val (lat, lon) = refinedTrackPoints[safeIndex]
    val heading = headingForIndex(safeIndex)
    updateVehicleState(lat, lon, heading)
    updateVehicleOnMap(webEngine, lat, lon, heading, headingUp, follow)
}

fun openFilePicker(webEngine: WebEngine?, positionSetter: (Float) -> Unit): String? {
    val fileChooser = JFileChooser().apply {
        fileFilter = FileNameExtensionFilter("GPX Files (*.gpx)", "gpx") // ✅ Filter for .gpx
        dialogTitle = "Select a GPX File"
        isAcceptAllFileFilterUsed = false // ✅ Prevents "All Files" from being shown
    }

    val result = fileChooser.showOpenDialog(null)

    val filePath = if (result == JFileChooser.APPROVE_OPTION) {
        fileChooser.selectedFile.absolutePath
    } else null

    filePath?.let { path ->
        val trackPoints = parseGpxFile(path)
        sendGpxToMap(webEngine, trackPoints)
        refinedTrackPoints = refineTrack(trackPoints)
        currentIndex = 0

        moveMarkerToIndex(webEngine, currentIndex, headingUp = false, follow = false)
        positionSetter(0f)
    }


    return filePath
}

fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371.0 // Earth radius in km
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return R * c * 1000 // Distance in meters
}

fun startAndroidApp() {
    val adbCommand = "shell am start -n org.nitri.gpxplayer/.MainActivity"

    try {
        val process = ProcessBuilder(adbPath, *adbCommand.split(" ").toTypedArray())
            .redirectErrorStream(true)
            .start()

        val exitCode = process.waitFor()
        println("📱 Launched Android app, exit code: $exitCode")
    } catch (e: Exception) {
        println("❌ Failed to launch Android app: ${e.message}")
    }
}

fun sendIntent(latitude: Double, longitude: Double, speedKmh: Int) {
    val adbArgs = listOf(
        "shell",
        "am", "broadcast",
        "-n", "org.nitri.gpxplayer/.MockLocationReceiver",
        "-a", "org.nitri.gpxplayer.ACTION_SET_LOCATION",
        "-d", "geo:$latitude,$longitude",
        "--ei", "speed", "$speedKmh"
    )

    println("🚀 Sending: $adbPath ${adbArgs.joinToString(" ")}")

    try {
        val process = ProcessBuilder(adbPath, *adbArgs.toTypedArray())
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        println("📱 Sent geo intent, exit code: $exitCode")
        println(output)
    } catch (e: Exception) {
        println("❌ Failed to send geo intent: ${e.message}")
    }
}

fun refineTrack(trackPoints: List<Pair<Double, Double>>, intervalMeters: Double = 10.0): List<Pair<Double, Double>> {
    if (trackPoints.isEmpty()) return emptyList()

    val refined = mutableListOf<Pair<Double, Double>>()
    var prev = trackPoints[0]
    refined.add(prev)
    var interpolatedCount = 0

    for (i in 1 until trackPoints.size) {
        val curr = trackPoints[i]
        val distance = calculateDistance(prev.first, prev.second, curr.first, curr.second)

        if (distance > intervalMeters) {
            val deltaLat = curr.first - prev.first
            val deltaLon = curr.second - prev.second
            val fraction = intervalMeters / distance
            val numNewPoints = (distance / intervalMeters).toInt()

            for (j in 1..numNewPoints) {
                val lat = prev.first + (j * fraction * deltaLat)
                val lon = prev.second + (j * fraction * deltaLon)
                refined.add(Pair(lat, lon))
                interpolatedCount++
            }
        }

        refined.add(curr)
        prev = curr
    }

    println("Refined GPX track: ${trackPoints.size} original points, $interpolatedCount interpolated points, ${refined.size} playback points.")
    return refined
}

fun calculateHeading(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val lat1Rad = Math.toRadians(lat1)
    val lat2Rad = Math.toRadians(lat2)
    val deltaLonRad = Math.toRadians(lon2 - lon1)

    val y = sin(deltaLonRad) * cos(lat2Rad)
    val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(deltaLonRad)

    val headingRad = atan2(y, x)
    return (Math.toDegrees(headingRad) + 360) % 360
}

fun moveMarkerToCurrentIndex(webEngine: WebEngine, speedKmh: Int, interval: Long) {
    val (lat1, lon1) = refinedTrackPoints[currentIndex]
    val currentSpeedKmh = speedKmh
    val heading = headingForIndex(currentIndex)

    println("📍 Moving to: ($lat1, $lon1), Speed: $currentSpeedKmh km/h, Interval: $interval ms")

    updateVehicleState(lat1, lon1, heading)
    updateVehicleOnMap(webEngine, lat1, lon1, heading, headingUp = false, follow = false)
}

fun playGpxFile(webEngine: WebEngine?, speedProvider: () -> Int, pausedProvider: () -> Boolean, position: MutableState<Float>, playing: MutableState<Boolean>) {
    if (refinedTrackPoints.isEmpty()) return

    playing.value = true
    firstIntentSent = false

    playbackTimer?.cancel()
    playbackTimer = Timer("playback-timer", true)

    fun scheduleNextStep() {
        if (!playing.value || currentIndex >= refinedTrackPoints.size - 1) {
            println("✅ Playback finished.")
            stopPlayGpxFile(webEngine, position, playing, false)
            return
        }

        val (lat1, lon1) = refinedTrackPoints[currentIndex]
        val (lat2, lon2) = refinedTrackPoints[currentIndex + 1]
        val distance = calculateDistance(lat1, lon1, lat2, lon2)
        val interval = ((distance / (speedProvider().coerceAtLeast(1) / 3.6)) * 1000).toLong()

        webEngine?.let { engine ->
            moveMarkerToCurrentIndex(engine, speedProvider().coerceAtLeast(1), interval) // avoid division by zero
        }

        if (!firstIntentSent || System.currentTimeMillis() - lastIntentSentTime > 800) {
            sendIntent(lat1, lon1, speedProvider().coerceAtLeast(1))
            firstIntentSent = true
            lastIntentSentTime = System.currentTimeMillis()
        }

        if (!pausedProvider()) {
            currentIndex++
        }

        position.value = ((currentIndex.toFloat() / (refinedTrackPoints.size - 1)) * 100f)

        // Schedule next step dynamically
        playbackTimer?.schedule(object : TimerTask() {
            override fun run() {
                scheduleNextStep()
            }
        }, interval)
    }

    scheduleNextStep()
}

fun stopPlayGpxFile(webEngine: WebEngine?, position: MutableState<Float>, playing: MutableState<Boolean>, resetPosition: Boolean) {
    println("⏹ Stopping playback.")
    playbackTimer?.cancel()
    playbackTimer?.purge()
    playbackTimer = null
    playing.value = false
    firstIntentSent = false
    if (resetPosition) {
        currentIndex = 0
        webEngine?.let {
            markerToStart(it)
        }
        position.value = 0f
    }
}

private fun markerToStart(webEngine: WebEngine) {
    moveMarkerToIndex(webEngine, 0, headingUp = false, follow = false)
}

fun main() = application {
    initializeJavaFX()
    Window(
        title = "GPX Player",
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(
            width = 800.dp,
            height = 1000.dp
        )
    ) {
        App()
    }
}

