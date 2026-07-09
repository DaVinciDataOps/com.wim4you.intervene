package com.wim4you.intervene.route

import android.net.Uri
import com.wim4you.intervene.security.ApiKeyGuard
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class DirectionsApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {

    data class HttpResponse(
        val code: Int,
        val body: String,
    )

    fun getDirections(
        origin: String,
        destination: String,
        apiKey: String,
    ): HttpResponse {
        val encodedOrigin = Uri.encode(origin)
        val encodedDestination = Uri.encode(destination)
        val url = buildString {
            append("https://maps.googleapis.com/maps/api/directions/json")
            append("?origin=").append(encodedOrigin)
            append("&destination=").append(encodedDestination)
            append("&mode=walking")
            append("&key=").append(apiKey)
        }
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                HttpResponse(response.code, body)
            }
        } catch (exception: Exception) {
            ApiKeyGuard.logDirectionsFailure("Directions request failed", url, exception)
            throw exception
        }
    }
}
