import androidx.compose.desktop.ui.tooling.preview.Preview
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
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import netscape.javascript.JSObject
import org.w3c.dom.Document
import java.io.File
import java.util.*
import java.util.prefs.Preferences
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

var playing = false
var firstIntentSent = false
var lastIntentSentTime = 0L
var currentIndex = 0
var refinedTrackPoints = listOf<Pair<Double, Double>>()
var adbPath = "/usr/bin/adb"

private var playbackTimer: Timer? = null

private val prefs = Preferences.userRoot().node("gpxplayer")

fun getSavedAdbPath(): String? = prefs.get("adb_path", null)

fun saveAdbPath(path: String) {
    prefs.put("adb_path", path)
}

fun initializeJavaFX() {
    if (!Platform.isFxApplicationThread()) {
        Platform.startup {}
    }
}

@Composable
@Preview
fun App() {
    var speed by remember { mutableStateOf(60f) }
    var logMessages by remember { mutableStateOf(listOf<String>()) }
    var selectedFile by remember { mutableStateOf<String?>(null) }

    val webEngine = remember { mutableStateOf<WebEngine?>(null) }

    adbPath = getSavedAdbPath() ?: "/usr/bin/adb"
    startAndroidApp()

    MaterialTheme(colors = AppColorPalette) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = {
                    selectedFile = openFilePicker(webEngine.value)
                    logMessages = logMessages + "Selected file: ${selectedFile ?: "None"}"

                }, colors = ButtonDefaults.buttonColors(contentColor = Color.White)) {
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

            Text("Speed: ${speed.toInt()} km/h")
            Slider(
                value = speed,
                onValueChange = { speed = it },
                valueRange = 0f..130f,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row {
                Button(
                    onClick = { playGpxFile(webEngine.value) { speed.toInt() } },
                    colors = ButtonDefaults.buttonColors(contentColor = Color.White)
                ) {
                    Text("Play")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { stopPlayGpxFile(webEngine.value) },
                    colors = ButtonDefaults.buttonColors(contentColor = Color.White)
                ) {
                    Text("Stop")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Spacer(modifier = Modifier.height(16.dp))

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
fun MapView(webEngineState: MutableState<WebEngine?>) {
    val mapHtmlPath = "file://${File("assets/map.html").absolutePath}"
    // ✅ Ensure panelState is always initialized
    val panelState = remember { mutableStateOf<JFXPanel?>(null) }

    LaunchedEffect(Unit) {
        Platform.runLater {
            val panel = createWebViewPanel(mapHtmlPath, webEngineState)
            panelState.value = panel
        }
    }

    if (panelState.value != null) {
        SwingPanel(
            factory = { panelState.value!! },
            modifier = Modifier.fillMaxSize()
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
        webEngineState.value = webEngine

        panel.scene = Scene(webView)

        val bridge = JavaScriptBridge(webEngine)
        webEngine.loadWorker.stateProperty().addListener { _, _, newState ->
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                val window = webEngine.executeScript("window") as JSObject
                window.setMember("javafx", bridge)
                println("✅ JavaFX Bridge Injected!")
                webEngine.executeScript(
                    """
            var style = document.createElement('style');
            style.innerHTML = 'body::-webkit-scrollbar { display: none; } body { overflow: hidden; }';
            document.head.appendChild(style);
            """.trimIndent()
                )
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
    if (trackPoints.isEmpty()) return

    val trackJson = trackPoints.joinToString(",") { "[${it.first}, ${it.second}]" }
    val jsCommand = """
        console.log("📡 Receiving GPX Data...");
        var trackCoords = [$trackJson];
        if (typeof map !== "undefined") {
            var polyline = L.polyline(trackCoords, {color: 'blue'}).addTo(map);
            map.fitBounds(polyline.getBounds());
        } else {
            console.error("❌ Leaflet map not found!");
        }
    """.trimIndent()

    Platform.runLater {
        webEngine?.executeScript(jsCommand)
        webEngine?.let{markerToStart(it)}
    }
}

fun sendJavaScriptCommand(webEngine: WebEngine?, command: String) {
    Platform.runLater {
        webEngine?.executeScript(command)
    }
}

fun openFilePicker(webEngine: WebEngine?): String? {
    val fileChooser = JFileChooser().apply {
        fileFilter = FileNameExtensionFilter("GPX Files (*.gpx)", "gpx") // ✅ Filter for .gpx
        dialogTitle = "Select a GPX File"
        isAcceptAllFileFilterUsed = false // ✅ Prevents "All Files" from being shown
    }

    val result = fileChooser.showOpenDialog(null)

    val filePath = if (result == JFileChooser.APPROVE_OPTION) {
        fileChooser.selectedFile.absolutePath
    } else null

    filePath?.let {
        val trackPoints = parseGpxFile(it)
        sendGpxToMap(webEngine, trackPoints)
        refinedTrackPoints = refineTrack(trackPoints)
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
    println("✅ First track point added: ${prev.first}, ${prev.second}")

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
                println("🆕 Interpolated point: $lat, $lon")
            }
        }

        refined.add(curr)
        println("✅ Original point added: ${curr.first}, ${curr.second}")
        prev = curr
    }

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

fun playGpxFile(webEngine: WebEngine?, speedProvider: () -> Int) {
    if (refinedTrackPoints.isEmpty() || playing) return

    playing = true
    firstIntentSent = false
    currentIndex = 0

    fun scheduleNextStep() {
        if (!playing || currentIndex >= refinedTrackPoints.size - 1) {
            playing = false
            println("✅ Playback finished.")
            return
        }

        val (lat1, lon1) = refinedTrackPoints[currentIndex]
        val (lat2, lon2) = refinedTrackPoints[currentIndex + 1]
        val distance = calculateDistance(lat1, lon1, lat2, lon2)
        val currentSpeedKmh = speedProvider().coerceAtLeast(1) // avoid division by zero
        val interval = ((distance / (currentSpeedKmh / 3.6)) * 1000).toLong()

        println("📍 Moving to: ($lat1, $lon1), Speed: $currentSpeedKmh km/h, Interval: $interval ms")

        val heading = calculateHeading(lat1, lon1, lat2, lon2)
        val jsCommand = """
            marker.setLatLng([$lat1, $lon1]);
            marker.setRotationAngle($heading);
            marker.setOpacity(1);
        """.trimIndent()
        sendJavaScriptCommand(webEngine, jsCommand)

        if (!firstIntentSent || System.currentTimeMillis() - lastIntentSentTime > 800) {
            sendIntent(lat1, lon1, currentSpeedKmh)
            firstIntentSent = true
            lastIntentSentTime = System.currentTimeMillis()
        }

        currentIndex++

        // Schedule next step dynamically
        Timer().schedule(object : TimerTask() {
            override fun run() {
                scheduleNextStep()
            }
        }, interval)
    }

    scheduleNextStep()
}

fun stopPlayGpxFile(webEngine: WebEngine?) {
    if (playing) {
        println("⏹ Stopping playback.")
        playbackTimer?.cancel()
        playbackTimer = null
        playing = false
        firstIntentSent = false
        webEngine?.let {
           markerToStart(it)
        }
    }
}

private fun markerToStart(webEngine: WebEngine) {
    val (lat1, lon1) = refinedTrackPoints.firstOrNull() ?: return
    val heading = if (refinedTrackPoints.size >= 2) {
        val (lat2, lon2) = refinedTrackPoints[1]
        calculateHeading(lat1, lon1, lat2, lon2)
    } else 0.0

    val js = """
        marker.setLatLng([$lat1, $lon1]);
        marker.setRotationAngle($heading);
        marker.setOpacity(1);
    """.trimIndent()

    sendJavaScriptCommand(webEngine, js)
}

fun main() = application {
    initializeJavaFX()
    Window(
        title = "GPX Player",
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(
            width = 800.dp,
            height = 900.dp
        )
    ) {
        App()
    }
}

class JavaScriptBridge(private val webEngine: WebEngine) {
    // Caution: ignore the never used warning!
    fun postMessage(message: String) {
        if (message == "Leaflet Ready") {
            println("✅ Leaflet is now fully loaded!")

            // Now we can safely execute JavaScript commands
//            Platform.runLater {
//                webEngine.executeScript("""
//                    console.log("✅ Running JavaScript after Leaflet Ready!");
//                    L.marker([51.78962, 6.14120]).addTo(map)
//                        .bindPopup("📍 Kotlin-Controlled Marker!").openPopup();
//                """.trimIndent())
//            }
        }
    }
}
