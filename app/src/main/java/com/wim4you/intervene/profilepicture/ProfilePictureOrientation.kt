package com.wim4you.intervene.profilepicture

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File

internal object ProfilePictureOrientation {
    const val ROTATE_STEP_DEGREES = 90

    fun loadUprightBitmap(sourceFile: File): Bitmap {
        val raw = android.graphics.BitmapFactory.decodeFile(sourceFile.absolutePath)
            ?: throw IllegalStateException("Could not decode captured image")
        val exif = ExifInterface(sourceFile.absolutePath)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        return applyExifOrientation(raw, orientation)
    }

    fun rotateClockwise(bitmap: Bitmap): Bitmap {
        return rotateByDegrees(bitmap, ROTATE_STEP_DEGREES)
    }

    fun rotateByDegrees(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true,
        )
        if (rotated !== source) {
            source.recycle()
        }
        return rotated
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        val oriented = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        )
        if (oriented !== bitmap) {
            bitmap.recycle()
        }
        return oriented
    }
}
