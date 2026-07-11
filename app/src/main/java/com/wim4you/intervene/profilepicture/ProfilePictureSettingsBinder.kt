package com.wim4you.intervene.profilepicture

import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.wim4you.intervene.R
import com.wim4you.intervene.databinding.ViewProfilePictureSectionBinding
import kotlinx.coroutines.launch
import java.io.File

/**
 * Binds the profile picture settings section to a fragment.
 * Keeps SettingsFragment free of camera and sync logic.
 */
object ProfilePictureSettingsBinder {

    fun bind(fragment: Fragment, sectionRoot: View) {
        val binding = ViewProfilePictureSectionBinding.bind(sectionRoot)
        val context = fragment.requireContext()

        fun refreshUi() {
            ProfilePictureImageLoader.bindLocal(binding.profilePicturePreview)
            val hasPicture = ProfilePictureSharingCoordinator.hasLocalPicture(context)
            binding.btnRotateProfilePicture.visibility = if (hasPicture) View.VISIBLE else View.GONE
            binding.btnRemoveProfilePicture.visibility = if (hasPicture) View.VISIBLE else View.GONE
            binding.switchShareProfilePicture.setOnCheckedChangeListener(null)
            binding.switchShareProfilePicture.isChecked =
                ProfilePictureSharingCoordinator.isSharingEnabled(context)
            bindSharingSwitch(fragment, binding)
        }

        refreshUi()

        val capture = ProfilePictureCapture(
            fragment = fragment,
            onCaptured = { file -> handleCaptured(fragment, binding, file) },
            onPermissionDenied = {
                Toast.makeText(
                    context,
                    R.string.profile_picture_camera_permission_denied,
                    Toast.LENGTH_SHORT,
                ).show()
            },
        )

        binding.btnTakeProfilePicture.setOnClickListener { capture.launch() }
        binding.btnRotateProfilePicture.setOnClickListener {
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                handleRotated(fragment, binding)
            }
        }
        binding.btnRemoveProfilePicture.setOnClickListener {
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                ProfilePictureSharingCoordinator.removePicture(context)
                ProfilePictureImageLoader.clearMemoryCache()
                refreshUi()
                Toast.makeText(
                    context,
                    R.string.profile_picture_removed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun bindSharingSwitch(fragment: Fragment, binding: ViewProfilePictureSectionBinding) {
        binding.switchShareProfilePicture.setOnCheckedChangeListener { _, isChecked ->
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                try {
                    ProfilePictureSharingCoordinator.onSharingToggled(
                        fragment.requireContext(),
                        isChecked,
                    )
                } catch (exception: Exception) {
                    binding.switchShareProfilePicture.setOnCheckedChangeListener(null)
                    binding.switchShareProfilePicture.isChecked = !isChecked
                    bindSharingSwitch(fragment, binding)
                    Toast.makeText(
                        fragment.requireContext(),
                        R.string.profile_picture_share_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun handleCaptured(
        fragment: Fragment,
        binding: ViewProfilePictureSectionBinding,
        captureFile: File,
    ) {
        val context = fragment.requireContext()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val saveResult = ProfilePictureLocalStore.saveFromCaptureFile(context, captureFile)
            if (saveResult.isFailure) {
                Toast.makeText(
                    context,
                    R.string.profile_picture_save_failed,
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }

            notifyPictureChanged(fragment, binding, context)
        }
    }

    private suspend fun handleRotated(
        fragment: Fragment,
        binding: ViewProfilePictureSectionBinding,
    ) {
        val context = fragment.requireContext()
        val rotateResult = ProfilePictureLocalStore.rotateSavedPictureClockwise(context)
        if (rotateResult.isFailure) {
            Toast.makeText(
                context,
                R.string.profile_picture_rotate_failed,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        notifyPictureChanged(fragment, binding, context)
    }

    private suspend fun notifyPictureChanged(
        fragment: Fragment,
        binding: ViewProfilePictureSectionBinding,
        context: android.content.Context,
    ) {
        ProfilePictureImageLoader.clearMemoryCache()
        val syncResult = ProfilePictureSharingCoordinator.onPictureChanged(context)
        ProfilePictureImageLoader.bindLocal(binding.profilePicturePreview)
        binding.btnRotateProfilePicture.visibility = View.VISIBLE
        binding.btnRemoveProfilePicture.visibility = View.VISIBLE

        if (syncResult.isFailure &&
            ProfilePictureSharingCoordinator.isSharingEnabled(context)
        ) {
            Toast.makeText(
                context,
                R.string.profile_picture_upload_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}
