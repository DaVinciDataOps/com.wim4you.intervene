package com.wim4you.intervene.profilepicture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import com.wim4you.intervene.R
import com.wim4you.intervene.security.SecureUrlValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

object ProfilePictureImageLoader {
    private val client = OkHttpClient()
    private val memoryCache = ConcurrentHashMap<String, Bitmap>()
    private val loadingJobs = ConcurrentHashMap<ImageView, Job>()

    fun bind(imageView: ImageView, remoteUrl: String?, scope: CoroutineScope) {
        loadingJobs.remove(imageView)?.cancel()
        if (remoteUrl.isNullOrBlank()) {
            imageView.setImageResource(R.drawable.ic_profile_placeholder)
            return
        }

        memoryCache[remoteUrl]?.let { cached ->
            imageView.setImageBitmap(cached)
            return
        }

        imageView.setImageResource(R.drawable.ic_profile_placeholder)
        val job = scope.launch {
            val bitmap = loadRemoteBitmap(remoteUrl) ?: return@launch
            memoryCache[remoteUrl] = bitmap
            withContext(Dispatchers.Main) {
                if (loadingJobs[imageView]?.isActive == true) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
        loadingJobs[imageView] = job
    }

    fun bindLocal(imageView: ImageView) {
        loadingJobs.remove(imageView)?.cancel()
        val bitmap = ProfilePictureLocalStore.loadBitmap(imageView.context)
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap)
        } else {
            imageView.setImageResource(R.drawable.ic_profile_placeholder)
        }
    }

    fun clearMemoryCache() {
        memoryCache.clear()
    }

    private suspend fun loadRemoteBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        if (!SecureUrlValidator.isAllowedRemoteImageUrl(url)) return@withContext null
        runCatching {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bytes = response.body?.bytes() ?: return@withContext null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull()
    }
}
