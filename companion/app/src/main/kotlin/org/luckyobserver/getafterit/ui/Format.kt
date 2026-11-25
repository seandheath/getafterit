package org.luckyobserver.getafterit.ui

import java.util.Locale

private const val METERS_PER_MILE = 1609.344
private const val FEET_PER_METER = 3.28084

fun formatDuration(secs: Long): String =
    if (secs >= 3600) String.format(Locale.US, "%d:%02d:%02d", secs / 3600, secs / 60 % 60, secs % 60)
    else String.format(Locale.US, "%d:%02d", secs / 60, secs % 60)

fun formatDistance(m: Double, miles: Boolean): String =
    String.format(Locale.US, "%.2f %s", m / if (miles) METERS_PER_MILE else 1000.0, if (miles) "mi" else "km")

/** Pace per mile or km; "--" when stopped or slower than an hour per unit. */
fun formatPace(secPerKm: Int?, miles: Boolean): String {
    if (secPerKm == null || secPerKm <= 0) return "--"
    val perUnit = if (miles) (secPerKm * METERS_PER_MILE / 1000).toLong() else secPerKm.toLong()
    if (perUnit >= 3600) return "--"
    return formatDuration(perUnit) + if (miles) " /mi" else " /km"
}

fun formatElevation(m: Long, miles: Boolean): String =
    if (miles) "${(m * FEET_PER_METER).toLong()} ft" else "$m m"
