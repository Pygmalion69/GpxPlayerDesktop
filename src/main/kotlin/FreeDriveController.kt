import java.util.Timer
import java.util.TimerTask
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

private const val FREE_DRIVE_TICK_MS = 100L
private const val FREE_DRIVE_INTENT_INTERVAL_MS = 800L
private const val FREE_DRIVE_SPEED_STEP_KMH = 5.0
private const val FREE_DRIVE_HEADING_STEP_DEG = 5.0
private const val FREE_DRIVE_MAX_SPEED_KMH = 130.0
private const val FREE_DRIVE_MIN_SPEED_KMH = -30.0

class FreeDriveController(
    private val webEngineProvider: () -> javafx.scene.web.WebEngine?
) {
    var isActive: Boolean = false
        private set

    var speedKmh: Double = 0.0
        private set

    var headingDeg: Double = 0.0
        private set

    private var position: Pair<Double, Double> = Pair(DEFAULT_MAP_LAT, DEFAULT_MAP_LON)
    private var timer: Timer? = null
    private var lastUpdateTimeMs: Long = 0L
    private var lastIntentTimeMs: Long = 0L

    fun start(initialPosition: Pair<Double, Double>, initialHeading: Double) {
        if (isActive) return
        isActive = true
        position = initialPosition
        headingDeg = normalizeHeading(initialHeading)
        speedKmh = 0.0
        lastUpdateTimeMs = System.currentTimeMillis()
        lastIntentTimeMs = 0L
        startTimer()
    }

    fun stop() {
        isActive = false
        timer?.cancel()
        timer?.purge()
        timer = null
    }

    fun accelerate() {
        adjustSpeed(FREE_DRIVE_SPEED_STEP_KMH)
    }

    fun decelerate() {
        adjustSpeed(-FREE_DRIVE_SPEED_STEP_KMH)
    }

    fun turnLeft() {
        adjustHeading(-FREE_DRIVE_HEADING_STEP_DEG)
    }

    fun turnRight() {
        adjustHeading(FREE_DRIVE_HEADING_STEP_DEG)
    }

    private fun adjustSpeed(delta: Double) {
        speedKmh = (speedKmh + delta).coerceIn(FREE_DRIVE_MIN_SPEED_KMH, FREE_DRIVE_MAX_SPEED_KMH)
    }

    private fun adjustHeading(delta: Double) {
        headingDeg = normalizeHeading(headingDeg + delta)
    }

    private fun startTimer() {
        timer?.cancel()
        timer = Timer("free-drive-timer", true)
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                tick()
            }
        }, 0L, FREE_DRIVE_TICK_MS)
    }

    private fun tick() {
        if (!isActive) return
        val now = System.currentTimeMillis()
        val deltaSeconds = (now - lastUpdateTimeMs) / 1000.0
        lastUpdateTimeMs = now

        val distanceMeters = (speedKmh / 3.6) * deltaSeconds
        if (distanceMeters != 0.0) {
            position = moveByMeters(position.first, position.second, headingDeg, distanceMeters)
        }

        updateVehicleState(position.first, position.second, headingDeg)
        updateVehicleOnMap(
            webEngineProvider(),
            position.first,
            position.second,
            headingDeg,
            headingUp = true,
            follow = true
        )

        if (now - lastIntentTimeMs >= FREE_DRIVE_INTENT_INTERVAL_MS) {
            sendIntent(position.first, position.second, abs(speedKmh).toInt())
            lastIntentTimeMs = now
        }
    }

    private fun normalizeHeading(heading: Double): Double {
        val normalized = heading % 360.0
        return if (normalized < 0) normalized + 360.0 else normalized
    }

    private fun moveByMeters(
        lat: Double,
        lon: Double,
        headingDeg: Double,
        distanceMeters: Double
    ): Pair<Double, Double> {
        val radius = 6_371_000.0
        val bearingRad = Math.toRadians(headingDeg)
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        val angularDistance = distanceMeters / radius

        val newLatRad = asin(
            sin(latRad) * cos(angularDistance) +
                cos(latRad) * sin(angularDistance) * cos(bearingRad)
        )

        val newLonRad = lonRad + atan2(
            sin(bearingRad) * sin(angularDistance) * cos(latRad),
            cos(angularDistance) - sin(latRad) * sin(newLatRad)
        )

        return Pair(Math.toDegrees(newLatRad), wrapLongitude(Math.toDegrees(newLonRad)))
    }

    private fun wrapLongitude(lon: Double): Double {
        var wrapped = lon
        while (wrapped > 180) wrapped -= 360
        while (wrapped < -180) wrapped += 360
        return wrapped
    }
}
