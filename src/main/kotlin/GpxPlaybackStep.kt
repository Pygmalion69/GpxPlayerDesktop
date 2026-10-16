/** A stopped or paused player stays in place and rechecks controls promptly. */
internal data class GpxPlaybackStep(val speedKmh: Int, val intervalMillis: Long, val advance: Boolean)

internal fun gpxPlaybackStep(distanceMeters: Double, speedKmh: Int, paused: Boolean): GpxPlaybackStep {
    if (paused || speedKmh <= 0) return GpxPlaybackStep(0, 100L, false)
    val interval = (distanceMeters / (speedKmh / 3.6) * 1000).toLong().coerceAtLeast(1L)
    return GpxPlaybackStep(speedKmh, interval, true)
}
