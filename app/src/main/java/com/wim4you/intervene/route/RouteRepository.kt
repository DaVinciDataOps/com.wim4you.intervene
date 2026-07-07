package com.wim4you.intervene.route

import android.content.Context
import android.location.Geocoder
import android.os.Build
import com.google.android.gms.maps.model.LatLng
import com.wim4you.intervene.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

data class RouteResult(
    val points: List<LatLng>,
    val destination: LatLng,
    val distanceText: String,
    val durationText: String,
)

class RouteRepository(private val context: Context) {

    suspend fun geocodeAddress(address: String): LatLng? = withContext(Dispatchers.IO) {
        if (address.isBlank()) return@withContext null
        val geocoder = Geocoder(context, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocationName(address, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<android.location.Address>) {
                        val location = addresses.firstOrNull()
                        continuation.resume(
                            location?.let { LatLng(it.latitude, it.longitude) }
                        )
                    }

                    override fun onError(errorMessage: String?) {
                        continuation.resume(null)
                    }
                })
            }
        } else {
            @Suppress("DEPRECATION")
            geocoder.getFromLocationName(address, 1)
                ?.firstOrNull()
                ?.let { LatLng(it.latitude, it.longitude) }
        }
    }

    suspend fun fetchRoute(origin: LatLng, destination: LatLng): Result<RouteResult> =
        withContext(Dispatchers.IO) {
            val originParam = "${origin.latitude},${origin.longitude}"
            val destParam = "${destination.latitude},${destination.longitude}"
            val key = URLEncoder.encode(BuildConfig.GOOGLE_MAPS_API_KEY, Charsets.UTF_8.name())
            val urlString = "https://maps.googleapis.com/maps/api/directions/json" +
                "?origin=$originParam&destination=$destParam&mode=walking&key=$key"

            val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
            }

            try {
                val responseCode = connection.responseCode
                val body = if (responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader().readText()
                } else {
                    connection.errorStream?.bufferedReader()?.readText().orEmpty()
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext Result.failure(Exception("Directions request failed ($responseCode)"))
                }

                val json = JSONObject(body)
                val status = json.getString("status")
                if (status != "OK") {
                    val message = json.optString("error_message", status)
                    return@withContext Result.failure(Exception(message))
                }

                val route = json.getJSONArray("routes").getJSONObject(0)
                val encodedPolyline = route.getJSONObject("overview_polyline").getString("points")
                val leg = route.getJSONArray("legs").getJSONObject(0)
                val distanceText = leg.getJSONObject("distance").getString("text")
                val durationText = leg.getJSONObject("duration").getString("text")

                Result.success(
                    RouteResult(
                        points = PolylineDecoder.decode(encodedPolyline),
                        destination = destination,
                        distanceText = distanceText,
                        durationText = durationText,
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                connection.disconnect()
            }
        }
}
