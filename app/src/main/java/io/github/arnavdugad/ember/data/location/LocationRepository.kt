package io.github.arnavdugad.ember.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.ember.core.util.Geo
import io.github.arnavdugad.ember.data.model.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class CityLocation(val city: String, val lat: Double, val lng: Double)

/**
 * Coarse location only (network provider, ~1-3 km), immediately rounded to two decimals and
 * turned into a city name. No Play services dependency, so it works on any device.
 */
@Singleton
class LocationRepository @Inject constructor(@ApplicationContext private val context: Context) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun currentCity(): CityLocation? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) -> LocationManager.PASSIVE_PROVIDER
            else -> return null
        }
        val fresh = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = android.os.CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                    if (cont.isActive) cont.resume(location)
                }
            }
        }
        val location = fresh ?: manager.getLastKnownLocation(provider) ?: return null
        val lat = Geo.coarse(location.latitude)
        val lng = Geo.coarse(location.longitude)
        return CityLocation(cityName(lat, lng) ?: "Near you", lat, lng)
    }

    /** Approximate centre of a typed city name, rounded to city level. Null if unknown. */
    suspend fun locate(city: String): CityLocation? {
        if (city.isBlank()) return null
        val address = geocode(city, 1).firstOrNull() ?: return null
        return CityLocation(city.trim(), Geo.coarse(address.latitude), Geo.coarse(address.longitude))
    }

    /**
     * The city picker: bundled popular cities first (instant, offline), then the Geocoder for
     * anywhere else once the query is specific enough. Coordinates are rounded to city level.
     */
    suspend fun searchCities(query: String): List<CitySuggestion> {
        val local = Cities.search(query)
        if (query.trim().length < 3 || local.size >= 8) return local
        val remote = geocode(query.trim(), 6).mapNotNull { a ->
            val name = a.locality ?: a.subAdminArea ?: a.featureName?.takeIf { it.any(Char::isLetter) } ?: return@mapNotNull null
            val detail = listOfNotNull(a.adminArea?.takeIf { it != name }, a.countryName).joinToString(", ")
            CitySuggestion(Place(name, Geo.coarse(a.latitude), Geo.coarse(a.longitude)), detail)
        }
        val seen = local.map { it.place.name.lowercase() }.toMutableSet()
        return local + remote.filter { seen.add(it.place.name.lowercase()) }
    }

    private suspend fun geocode(query: String, max: Int): List<Address> {
        if (!Geocoder.isPresent()) return emptyList()
        val geocoder = Geocoder(context, Locale.getDefault())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            withTimeoutOrNull(8_000) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocationName(query, max, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.toList())
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(emptyList())
                        }
                    })
                }
            } ?: emptyList()
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocationName(query, max).orEmpty() }.getOrDefault(emptyList())
            }
        }
    }

    private suspend fun cityName(lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val address: Address? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            withTimeoutOrNull(8_000) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocation(lat, lng, 1)?.firstOrNull() }.getOrNull()
            }
        }
        return address?.locality ?: address?.subAdminArea ?: address?.adminArea
    }
}
