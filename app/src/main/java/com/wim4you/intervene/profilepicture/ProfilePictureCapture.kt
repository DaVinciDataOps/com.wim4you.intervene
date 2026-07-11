package com.wim4you.intervene.profilepicture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import java.io.File

class ProfilePictureCapture(
    private val fragment: Fragment,
    private val onCaptured: (File) -> Unit,
    private val onPermissionDenied: () -> Unit,
) {
    private var pendingCaptureFile: File? = null

    private val takePictureLauncher: ActivityResultLauncher<Uri> =
        fragment.registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val file = pendingCaptureFile
            pendingCaptureFile = null
            if (success && file != null && file.exists()) {
                onCaptured(file)
            }
        }

    private val cameraPermissionLauncher: ActivityResultLauncher<String> =
        fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchCamera(fragment.requireContext())
            } else {
                onPermissionDenied()
            }
        }

    fun launch() {
        val context = fragment.requireContext()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera(context)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera(context: Context) {
        val file = ProfilePictureLocalStore.createCaptureFile(context)
        pendingCaptureFile = file
        val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        takePictureLauncher.launch(uri)
    }

    companion object {
        const val AUTHORITY = "com.wim4you.intervene.profilepicture.fileprovider"
    }
}
