package com.wim4you.intervene.route

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
        val url = "https://maps.googleapis.com/maps/api/directions/json" +
            "?origin=$origin&destination=$destination&mode=walking&key=$apiKey"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            return HttpResponse(response.code, body)
        }
    }
}
