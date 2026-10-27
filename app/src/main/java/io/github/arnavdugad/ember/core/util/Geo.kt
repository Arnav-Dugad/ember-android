package io.github.arnavdugad.ember.core.util

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Location helpers. Ember stores location at city level only: coordinates rounded to two
 * decimals (about 1 km) and a precision-5 geohash (about 5 km cells), so a profile can never be
 * used to pinpoint someone.
 */
object Geo {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
    const val STORED_PRECISION = 5

    fun geohash(lat: Double, lng: Double, precision: Int = STORED_PRECISION): String {
        var latLo = -90.0
        var latHi = 90.0
        var lngLo = -180.0
        var lngHi = 180.0
        val sb = StringBuilder()
        var bit = 0
        var ch = 0
        var even = true
        while (sb.length < precision) {
            if (even) {
                val mid = (lngLo + lngHi) / 2
                if (lng >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    lngLo = mid
                } else {
                    lngHi = mid
                }
            } else {
                val mid = (latLo + latHi) / 2
                if (lat >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    latLo = mid
                } else {
                    latHi = mid
                }
            }
            even = !even
            if (bit < 4) {
                bit++
            } else {
                sb.append(BASE32[ch])
                bit = 0
                ch = 0
            }
        }
        return sb.toString()
    }

    fun coarse(value: Double): Double = (value * 100).roundToInt() / 100.0

    /** Great-circle distance in kilometres. */
    fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * 6371.0 * asin(sqrt(a))
    }

    /** "3 km away", never "0 km": coarse locations make sub-kilometre precision a lie. */
    fun distanceLabel(km: Int?): String? = when {
        km == null -> null
        km <= 2 -> "Nearby"
        else -> "%,d km away".format(km)
    }
}
