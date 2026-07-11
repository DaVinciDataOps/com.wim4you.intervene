package com.wim4you.intervene.profilepicture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

internal object ProfilePictureLocalStore {
    private const val DIRECTORY = "profile_pictures"
    private const val MAX_DIMENSION_PX = 512
    private const val JPEG_QUALITY = 85

    fun hasPicture(context: Context): Boolean {
        val filename = ProfilePicturePreferences.getLocalFilename(context) ?: return false
        return fileFor(context, filename).exists()
    }

    fun loadBitmap(context: Context): Bitmap? {
        val filename = ProfilePicturePreferences.getLocalFilename(context) ?: return null
        val file = fileFor(context, filename)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    fun saveFromCaptureFile(context: Context, captureFile: File): Result<Unit> {
        return runCatching {
            val upright = ProfilePictureOrientation.loadUprightBitmap(captureFile)
            val scaled = scaleDown(upright)
            if (scaled !== upright) {
                upright.recycle()
            }
            persistBitmap(context, scaled)
            scaled.recycle()
            captureFile.delete()
        }
    }

    fun rotateSavedPictureClockwise(context: Context): Result<Unit> {
        return runCatching {
            val filename = ProfilePicturePreferences.getLocalFilename(context)
                ?: throw IllegalStateException("No profile picture to rotate")
            val file = fileFor(context, filename)
            if (!file.exists()) throw IllegalStateException("No profile picture to rotate")

            val current = BitmapFactory.decodeFile(file.absolutePath)
                ?: throw IllegalStateException("Could not decode profile picture")
            val rotated = ProfilePictureOrientation.rotateClockwise(current)
            FileOutputStream(file).use { output ->
                if (!rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    rotated.recycle()
                    throw IllegalStateException("Could not save rotated profile picture")
                }
            }
            rotated.recycle()
        }
    }

    fun deleteLocal(context: Context) {
        val filename = ProfilePicturePreferences.getLocalFilename(context) ?: return
        fileFor(context, filename).delete()
        ProfilePicturePreferences.setLocalFilename(context, null)
    }

    fun createCaptureFile(context: Context): File {
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        return File(directory, "capture_${System.currentTimeMillis()}.jpg")
    }

    private fun persistBitmap(context: Context, bitmap: Bitmap) {
        val filename = ProfilePicturePreferences.getLocalFilename(context)
            ?: "${UUID.randomUUID()}.jpg"
        val file = fileFor(context, filename)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                throw IllegalStateException("Could not save profile picture")
            }
        }
        ProfilePicturePreferences.setLocalFilename(context, filename)
    }

    private fun fileFor(context: Context, filename: String): File {
        return File(File(context.filesDir, DIRECTORY), filename)
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val largestSide = maxOf(source.width, source.height)
        if (largestSide <= MAX_DIMENSION_PX) return source
        val scale = MAX_DIMENSION_PX.toFloat() / largestSide
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }
}
