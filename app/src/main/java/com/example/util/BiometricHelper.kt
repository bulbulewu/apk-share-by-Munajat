package com.example.util

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricHelper {
    private const val TAG = "BiometricHelper"

    private const val ALLOWED_AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun isBiometricAvailable(context: Context): Boolean {
        return try {
            val manager = BiometricManager.from(context)
            manager.canAuthenticate(ALLOWED_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
        } catch (t: Throwable) {
            Log.w(TAG, "Biometric check failed: ${t.message}")
            false
        }
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String = "Unlock APK Share",
        subtitle: String = "Verify your identity to access offline transfers",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val executor = ContextCompat.getMainExecutor(activity)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Log.d(TAG, "Biometric attempt did not match")
                }
            }
            val prompt = BiometricPrompt(activity, executor, callback)
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
                .build()
            prompt.authenticate(promptInfo)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to show biometric prompt", t)
            onError(t.message ?: "Biometric authentication unavailable")
        }
    }
}
