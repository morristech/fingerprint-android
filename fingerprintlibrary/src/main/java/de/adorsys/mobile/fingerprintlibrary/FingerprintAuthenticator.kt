package de.adorsys.mobile.fingerprintlibrary

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.Handler
import android.support.annotation.RequiresApi
import android.support.v4.hardware.fingerprint.FingerprintManagerCompat
import android.text.TextUtils

/**
 * This class handles the fingerprint communication with the user's system and simplifies its API.
 *
 * @param errors You can assign the class your personal error strings for the error codes, e.g.:
 *     val errors = mapOf(
 *         Pair<Int, String>(FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE, getString(R.string.error_override_hw_unavailable)),
 *         Pair<Int, String>(FingerprintManager.FINGERPRINT_ERROR_UNABLE_TO_PROCESS, getString(R.string.error_override_unable_to_process)),
 *         Pair<Int, String>(FingerprintManager.FINGERPRINT_ERROR_TIMEOUT, getString(R.string.error_override_error_timeout)),
 *         Pair<Int, String>(FingerprintManager.FINGERPRINT_ERROR_NO_SPACE, getString(R.string.error_override_no_space)),
 *         Pair<Int, String>(FingerprintManager.FINGERPRINT_ERROR_CANCELED, getString(R.string.error_override_canceled)),
 *         Pair<Int, String>(FingerprintManager.FINGERPRINT_ERROR_LOCKOUT, getString(R.string.error_override_lockout)),
 *         Pair<Int, String>(FingerprintManager.FINGERPRINT_ERROR_VENDOR, getString(R.string.error_override_vendor)),
 *         Pair<Int, String>(FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT, getString(R.string.error_override_lockout_permanent)),
 *         Pair<Int, String>(FingerprintManager.FINGERPRINT_ERROR_USER_CANCELED, getString(R.string.error_override_user_cancel)))
 *
 * @param useSystemErrors You can force the library to use the human readable error string returned by the system
 *     but in at least the two locked cases it is not recommended. The system error messages don't inform the user
 *     what he/she has to do and the library doesn't subscribe the authorization until the necessary condition is true again.
 *     This is explained in the library's error messages.
 *
 */
@TargetApi(Build.VERSION_CODES.M)
class FingerprintAuthenticator(private val context: Context,
                               private val errors: Map<Int, String> = emptyMap(),
                               private val useSystemErrors: Boolean = false) : FingerprintManagerCompat.AuthenticationCallback() {
    private val handler = Handler()
    private val subscribeRunnable = Runnable {
        lockoutOccurred = false
        fingerprintManager?.authenticate(null, 0, null, this, null)
    }
    private var fingerprintManager: FingerprintManagerCompat? = null
    private var authenticationListener: AuthenticationListener? = null
    private var lockoutOccurred = false

    init {
        fingerprintManager =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    FingerprintManagerCompat.from(context)
                } else {
                    null
                }
    }

    // There is no active permission request required for using the fingerprint
    // and it is declared inside the AndroidManifest
    @SuppressLint("MissingPermission")
    fun hasFingerprintEnrolled(): Boolean {
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && fingerprintManager != null
                && fingerprintManager!!.isHardwareDetected
                && fingerprintManager!!.hasEnrolledFingerprints())
    }

    // There is no active permission request required for using the fingerprint
    // and it is declared inside the AndroidManifest
    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.M)
    fun subscribe(listener: AuthenticationListener) {
        authenticationListener = listener

        if (hasFingerprintEnrolled() && !lockoutOccurred) {
            fingerprintManager?.authenticate(null, 0, null, this, null)
        } else if (lockoutOccurred) {
            handler.postDelayed(subscribeRunnable, FINGERPRINT_LOCKOUT_TIME)
        }
    }

    fun unsubscribe() {
        authenticationListener = null
    }

    /**
     * Called when an unrecoverable error has been encountered and the operation is complete.
     * No further callbacks will be made on this object.
     *
     * @param errorCode An integer identifying the error message
     * @param errString A human-readable error string that can be shown in UI
     */
    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
        when (errorCode) {
            FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT,
            FingerprintManager.FINGERPRINT_ERROR_LOCKOUT -> {
                lockoutOccurred = true
            }
        // workaround for Android bug: https://stackoverflow.com/a/40854259/3734116
            FingerprintManager.FINGERPRINT_ERROR_CANCELED -> return
        }

        authenticationListener?.onFingerprintAuthenticationFailure(getErrorMessage(errorCode, errString), errorCode)
    }

    /**
     * Called when a recoverable error has been encountered during authentication. The help
     * string is provided to give the user guidance for what went wrong, such as
     * "Sensor dirty, please clean it."
     *
     * @param helpCode   An integer identifying the error message
     * @param helpString A human-readable string that can be shown in UI
     */
    override fun onAuthenticationHelp(helpCode: Int, helpString: CharSequence) {
        authenticationListener?.onFingerprintAuthenticationFailure(getErrorMessage(helpCode, helpString), helpCode)
    }

    /**
     * Called when a fingerprint is recognized.
     *
     * @param result An object containing authentication-related data
     */
    override fun onAuthenticationSucceeded(result: FingerprintManagerCompat.AuthenticationResult) {
        authenticationListener?.onFingerprintAuthenticationSuccess()
    }

    /**
     * Called when a fingerprint is not recognized.
     * The user probably used the wrong finger.
     */
    override fun onAuthenticationFailed() {
        authenticationListener?.onFingerprintAuthenticationFailure(getErrorMessage(FINGERPRINT_ERROR_NOT_RECOGNIZED, null), FINGERPRINT_ERROR_NOT_RECOGNIZED)
    }

    private fun getErrorMessage(code: Int, errString: CharSequence?): String {
        return when (code) {
            FingerprintManager.FINGERPRINT_ERROR_HW_UNAVAILABLE ->
                if (errors.contains(code)) errors[code]!!
                else return determineErrorStringSource(errString?.toString(), context.getString(R.string.fingerprint_error_hardware_unavailable))
            FingerprintManager.FINGERPRINT_ERROR_UNABLE_TO_PROCESS ->
                if (errors.contains(code)) errors[code]!!
                else return determineErrorStringSource(errString?.toString(), context.getString(R.string.fingerprint_error_unable_to_process))
            FingerprintManager.FINGERPRINT_ERROR_TIMEOUT ->
                if (errors.contains(code)) errors[code]!!
                else return determineErrorStringSource(errString?.toString(), context.getString(R.string.fingerprint_error_timeout))
            FingerprintManager.FINGERPRINT_ERROR_NO_SPACE ->
                if (errors.contains(code)) errors[code]!!
                else return determineErrorStringSource(errString?.toString(), context.getString(R.string.fingerprint_error_no_space))
            FingerprintManager.FINGERPRINT_ERROR_CANCELED ->
                if (errors.contains(code)) errors[code]!!
                else return determineErrorStringSource(errString?.toString(), context.getString(R.string.fingerprint_error_canceled))
            FingerprintManager.FINGERPRINT_ERROR_LOCKOUT ->
                if (errors.contains(code)) errors[code]!!
                // you should not use the string returned by the system to make sure the user
                // knows that he/she has to wait for 30 seconds
                else return determineErrorStringSource(errString?.toString(), context.getString(R.string.fingerprint_error_lockout))
            FingerprintManager.FINGERPRINT_ERROR_VENDOR ->
                if (errors.contains(code)) errors[code]!!
                else return determineErrorStringSource(errString?.toString(), context.getString(R.string.fingerprint_error_not_recognized))
            FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT ->
                if (errors.contains(code)) errors[code]!!
                // you should not use the string returned by the system to make sure the user
                // knows that he/she has to lock the system and return by using another pattern
                else return determineErrorStringSource(errString?.toString(), context.getString(R.string.fingerprint_error_lockout_permanent))
            FingerprintManager.FINGERPRINT_ERROR_USER_CANCELED ->
                if (errors.contains(code)) errors[code]!!
                else return determineErrorStringSource(errString?.toString(), context.getString(R.string.fingerprint_error_user_cancelled))
            FINGERPRINT_ERROR_NOT_RECOGNIZED ->
                if (errors.contains(code)) errors[code]!!
                else return determineErrorStringSource(errString?.toString(), context.getString(R.string.fingerprint_error_not_recognized))
            else -> return errString?.toString() ?: context.getString(R.string.fingerprint_error_unknown)
        }
    }

    private fun determineErrorStringSource(systemErrorString: String?, libraryErrorString: String): String {
        return if (useSystemErrors && !TextUtils.isEmpty(systemErrorString)) {
            systemErrorString.toString()
        } else {
            libraryErrorString
        }
    }

    companion object {
        // Use a bit more than 30 seconds to prevent subscribing too early
        private const val FINGERPRINT_LOCKOUT_TIME = 31000L
        const val FINGERPRINT_ERROR_NOT_RECOGNIZED = -999
    }
}
