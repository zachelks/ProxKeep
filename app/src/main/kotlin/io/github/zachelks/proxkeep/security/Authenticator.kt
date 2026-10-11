package io.github.zachelks.proxkeep.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Asks the person to prove they are the device's owner, with a fingerprint
 * or face if one is enrolled, or else the device's PIN, pattern or password.
 *
 * The check is done by Android. The app only learns whether it passed.
 */
class Authenticator(private val appLock: AppLock) {

    /** False when the device has no screen lock, so there is nothing to check against. */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(ALLOWED) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Shows the system prompt. [onResult] receives true only if the person
     * authenticated, and false if they cancelled or the prompt failed.
     */
    fun authenticate(activity: FragmentActivity, title: String, onResult: (Boolean) -> Unit) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                appLock.onAuthenticationFinished()
                onResult(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                appLock.onAuthenticationFinished()
                onResult(false)
            }

            // onAuthenticationFailed is one unrecognized attempt. The prompt
            // stays open and ends in one of the two callbacks above.
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(ALLOWED)
            .build()
        appLock.onAuthenticationStarted()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }

    private companion object {
        const val ALLOWED = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }
}
