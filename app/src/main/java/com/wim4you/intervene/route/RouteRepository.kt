package com.wim4you.intervene.route

import android.content.Context
import android.location.Geocoder
import android.os.Build
import com.google.android.gms.maps.model.LatLng
import com.wim4you.intervene.BuildConfig
import com.wim4you.intervene.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume

data class RouteResult(
    val points: List<LatLng>,
    val destination: LatLng,
    val distanceText: String,
    val durationText: String,
)

class RouteRepository(
    private val context: Context,
    private val directionsApiClient: DirectionsApiClient,
) {

    private val directionsApiKey: String
        get() = BuildConfig.GOOGLE_DIRECTIONS_API_KEY

    suspend fun geocodeAddress(address: String): LatLng? = withContext(Dispatchers.IO) {
        if (address.isBlank()) return@withContext null
        val geocoder = Geocoder(context, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocationName(address, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<android.location.Address>) {
                        val location = addresses.firstOrNull()
                        continuation.resume(
                            location?.let { LatLng(it.latitude, it.longitude) },
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

            try {
                val response = directionsApiClient.getDirections(
                    origin = originParam,
                    destination = destParam,
                    apiKey = directionsApiKey,
                )

                if (response.code != 200) {
                    return@withContext Result.failure(
                        Exception(context.getString(R.string.route_directions_http_error, response.code)),
                    )
                }

                val json = JSONObject(response.body)
                val status = json.getString("status")
                if (status != "OK") {
                    val apiMessage = json.optString("error_message", status)
                    return@withContext Result.failure(Exception(mapDirectionsError(status, apiMessage)))
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
                    ),
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun mapDirectionsError(status: String, apiMessage: String): String {
        return when (status) {
            "REQUEST_DENIED" -> context.getString(R.string.route_not_authorized)
            "ZERO_RESULTS" -> context.getString(R.string.route_no_results)
            else -> apiMessage.ifBlank { context.getString(R.string.route_fetch_failed) }
        }
    }
}
