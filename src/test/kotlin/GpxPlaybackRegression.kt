/** Run with Gradle's playbackRegressionTest task; no device or JavaFX display required. */
fun main() {
    var index = 4
    fun tick(speed: Int, paused: Boolean): GpxPlaybackStep {
        val step = gpxPlaybackStep(10.0, speed, paused)
        if (step.advance) index++
        return step
    }

    repeat(3) {
        check(tick(0, false).speedKmh == 0)
        check(index == 4) { "Zero speed must not move the track position" }
    }
    index = 0 // Seek to the beginning while speed is zero.
    check(!tick(0, false).advance && index == 0)
    val resumed = tick(5, false)
    check(index == 1 && resumed.speedKmh == 5 && resumed.intervalMillis == 7200L)
    repeat(3) { tick(5, false) }
    check(index == 4) { "Resuming after a zero-speed seek must keep advancing" }

    val paused = tick(5, true)
    check(paused.speedKmh == 0 && paused.intervalMillis <= 100L && index == 4)
    check(tick(2, false).intervalMillis == 18000L && index == 5)
    check(gpxPlaybackStep(0.0, 5, false).intervalMillis >= 1L)
    // Exercise the real timer and state updates with a harmless ADB stand-in.
    adbPath = "/bin/true"
    refinedTrackPoints = List(100) { 51.0 + it * 0.000001 to 6.0 }
    currentIndex = 4
    val position = androidx.compose.runtime.mutableStateOf(4f)
    val playing = androidx.compose.runtime.mutableStateOf(false)
    val speed = java.util.concurrent.atomic.AtomicInteger(0)
    val isPaused = java.util.concurrent.atomic.AtomicBoolean(false)
    try {
        playGpxFile(null, { speed.get() }, { isPaused.get() }, position, playing)
        Thread.sleep(250)
        check(currentIndex == 4) { "Timer advanced at zero speed" }
        currentIndex = 0
        position.value = 0f
        Thread.sleep(150)
        check(currentIndex == 0) { "Timer advanced after seeking at zero speed" }
        speed.set(130)
        val deadline = System.nanoTime() + 2_000_000_000L
        while (position.value < 5f && System.nanoTime() < deadline) Thread.sleep(10)
        check(position.value >= 5f) { "Timer failed to resume after zero speed and seek" }
        isPaused.set(true)
        playGpxFile(null, { speed.get() }, { isPaused.get() }, position, playing)
        val heldPosition = position.value
        Thread.sleep(250)
        check(position.value == heldPosition) { "Paused timer advanced" }
        isPaused.set(false)
        val resumeDeadline = System.nanoTime() + 2_000_000_000L
        while (position.value == heldPosition && System.nanoTime() < resumeDeadline) Thread.sleep(10)
        check(position.value > heldPosition) { "Timer failed to resume from pause" }
    } finally {
        stopPlayGpxFile(null, position, playing, true)
    }
    println("GPX playback regression checks passed")
}
