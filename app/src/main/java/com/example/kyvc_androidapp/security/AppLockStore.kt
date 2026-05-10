package com.example.kyvc_androidapp.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

class AppLockStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    fun hasPin(): Boolean {
        return prefs.contains(KEY_PIN_CIPHER_TEXT) && prefs.contains(KEY_PIN_IV)
    }

    fun hasPattern(): Boolean {
        return prefs.contains(KEY_PATTERN_CIPHER_TEXT) && prefs.contains(KEY_PATTERN_IV)
    }

    fun hasAnyLock(): Boolean {
        return hasPin() || hasPattern()
    }

    fun setPin(pin: String) {
        require(pin.matches(PIN_REGEX)) { "PIN must be 4-8 digits" }
        storeSecret(
            rawSecret = pin,
            cipherTextKey = KEY_PIN_CIPHER_TEXT,
            ivKey = KEY_PIN_IV
        )
    }

    fun verifyPin(pin: String): Boolean {
        if (!hasPin() || pin.isBlank()) {
            return false
        }
        return verifySecret(
            rawSecret = pin,
            cipherTextKey = KEY_PIN_CIPHER_TEXT,
            ivKey = KEY_PIN_IV
        )
    }

    fun clearPin() {
        prefs.edit()
            .remove(KEY_PIN_CIPHER_TEXT)
            .remove(KEY_PIN_IV)
            .apply()
    }

    fun setPattern(pattern: List<Int>) {
        require(isValidPattern(pattern)) { "Pattern must connect at least 4 points" }
        storeSecret(
            rawSecret = pattern.joinToString("-"),
            cipherTextKey = KEY_PATTERN_CIPHER_TEXT,
            ivKey = KEY_PATTERN_IV
        )
    }

    fun verifyPattern(pattern: List<Int>): Boolean {
        if (!hasPattern() || !isValidPattern(pattern)) {
            return false
        }
        return verifySecret(
            rawSecret = pattern.joinToString("-"),
            cipherTextKey = KEY_PATTERN_CIPHER_TEXT,
            ivKey = KEY_PATTERN_IV
        )
    }

    fun isBiometricEnabled(): Boolean {
        return prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
    }

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    fun getFailedAuthAttempts(): Int {
        return prefs.getInt(KEY_FAILED_AUTH_ATTEMPTS, 0).coerceAtLeast(0)
    }

    fun getRemainingAuthAttempts(): Int {
        return (AUTH_FAILURE_THRESHOLD - getFailedAuthAttempts()).coerceAtLeast(0)
    }

    fun getAuthFailureThreshold(): Int {
        return AUTH_FAILURE_THRESHOLD
    }

    fun isEmailVerificationRequired(): Boolean {
        return getFailedAuthAttempts() >= AUTH_FAILURE_THRESHOLD
    }

    fun recordAuthFailure(): Int {
        val nextCount = (getFailedAuthAttempts() + 1).coerceAtMost(AUTH_FAILURE_THRESHOLD)
        prefs.edit()
            .putInt(KEY_FAILED_AUTH_ATTEMPTS, nextCount)
            .remove(KEY_SENSITIVE_AUTH_REASON)
            .remove(KEY_SENSITIVE_AUTH_AT_MS)
            .apply()
        return nextCount
    }

    fun resetAuthFailures() {
        prefs.edit()
            .putInt(KEY_FAILED_AUTH_ATTEMPTS, 0)
            .remove(KEY_SENSITIVE_AUTH_REASON)
            .remove(KEY_SENSITIVE_AUTH_AT_MS)
            .apply()
    }

    fun markSessionUnlocked() {
        prefs.edit()
            .putLong(KEY_SESSION_UNLOCKED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_SESSION_UNLOCKED_AT_MS)
            .remove(KEY_SENSITIVE_AUTH_REASON)
            .remove(KEY_SENSITIVE_AUTH_AT_MS)
            .apply()
    }

    fun getSessionUnlockedAtMillis(): Long? {
        val value = prefs.getLong(KEY_SESSION_UNLOCKED_AT_MS, 0L)
        return value.takeIf { it > 0L }
    }

    fun getSessionExpiresAtMillis(): Long? {
        return getSessionUnlockedAtMillis()?.plus(SESSION_DURATION_MS)
    }

    fun isSessionUnlocked(): Boolean {
        val expiresAt = getSessionExpiresAtMillis() ?: return false
        return System.currentTimeMillis() < expiresAt
    }

    fun getSessionRemainingMillis(): Long {
        val expiresAt = getSessionExpiresAtMillis() ?: return 0L
        return (expiresAt - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun markSensitiveActionAuthorized(reason: String) {
        require(reason.isNotBlank()) { "Sensitive auth reason must not be blank" }
        prefs.edit()
            .putString(KEY_SENSITIVE_AUTH_REASON, reason)
            .putLong(KEY_SENSITIVE_AUTH_AT_MS, System.currentTimeMillis())
            .apply()
    }

    fun isSensitiveActionAuthorized(reason: String): Boolean {
        val storedReason = prefs.getString(KEY_SENSITIVE_AUTH_REASON, null)
        if (storedReason != reason) return false
        val authorizedAt = prefs.getLong(KEY_SENSITIVE_AUTH_AT_MS, 0L)
        if (authorizedAt <= 0L) return false
        return System.currentTimeMillis() < authorizedAt + SENSITIVE_AUTH_DURATION_MS
    }

    fun consumeSensitiveActionAuthorization(reason: String): Boolean {
        val authorized = isSensitiveActionAuthorized(reason)
        prefs.edit()
            .remove(KEY_SENSITIVE_AUTH_REASON)
            .remove(KEY_SENSITIVE_AUTH_AT_MS)
            .apply()
        return authorized
    }

    fun getSensitiveActionRemainingMillis(reason: String): Long {
        val storedReason = prefs.getString(KEY_SENSITIVE_AUTH_REASON, null)
        if (storedReason != reason) return 0L
        val authorizedAt = prefs.getLong(KEY_SENSITIVE_AUTH_AT_MS, 0L)
        if (authorizedAt <= 0L) return 0L
        return (authorizedAt + SENSITIVE_AUTH_DURATION_MS - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private fun isValidPattern(pattern: List<Int>): Boolean {
        return pattern.size >= MIN_PATTERN_POINTS && pattern.distinct().size == pattern.size
    }

    private fun storeSecret(
        rawSecret: String,
        cipherTextKey: String,
        ivKey: String
    ) {
        require(rawSecret.isNotBlank()) { "Secret must not be blank" }
        val salt = ByteArray(SALT_SIZE).also(secureRandom::nextBytes)
        val hash = pbkdf2(rawSecret, salt)
        val payload = buildString {
            append(ITERATION_COUNT)
            append(':')
            append(salt.toBase64())
            append(':')
            append(hash.toBase64())
        }
        val encrypted = encrypt(payload)
        prefs.edit()
            .putString(cipherTextKey, encrypted.cipherText)
            .putString(ivKey, encrypted.iv)
            .apply()
    }

    private fun verifySecret(
        rawSecret: String,
        cipherTextKey: String,
        ivKey: String
    ): Boolean {
        if (rawSecret.isBlank()) {
            return false
        }
        val encrypted = EncryptedValue(
            cipherText = prefs.getString(cipherTextKey, null).orEmpty(),
            iv = prefs.getString(ivKey, null).orEmpty()
        )
        val parts = decrypt(encrypted).split(':')
        require(parts.size == 3) { "Stored secret payload is invalid" }
        val iterations = parts[0].toIntOrNull() ?: return false
        val salt = parts[1].fromBase64()
        val expectedHash = parts[2].fromBase64()
        val actualHash = pbkdf2(rawSecret, salt, iterations)
        return MessageDigest.isEqual(expectedHash, actualHash)
    }

    private fun pbkdf2(pin: String, salt: ByteArray, iterations: Int = ITERATION_COUNT): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, HASH_SIZE_BITS)
        return SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
    }

    private fun encrypt(value: String): EncryptedValue {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val cipherBytes = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return EncryptedValue(
            cipherText = Base64.encodeToString(cipherBytes, Base64.NO_WRAP),
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        )
    }

    private fun decrypt(value: EncryptedValue): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val ivBytes = Base64.decode(value.iv, Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, ivBytes))
        val plainBytes = cipher.doFinal(Base64.decode(value.cipherText, Base64.NO_WRAP))
        return plainBytes.toString(Charsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun ByteArray.toBase64(): String {
        return Base64.encodeToString(this, Base64.NO_WRAP)
    }

    private fun String.fromBase64(): ByteArray {
        return Base64.decode(this, Base64.NO_WRAP)
    }

    private data class EncryptedValue(
        val cipherText: String,
        val iv: String
    )

    companion object {
        private const val PREFS_NAME = "kyvc_app_lock"
        private const val KEY_ALIAS = "kyvc_app_lock_key"
        private const val KEY_PIN_CIPHER_TEXT = "pin_cipher_text"
        private const val KEY_PIN_IV = "pin_iv"
        private const val KEY_PATTERN_CIPHER_TEXT = "pattern_cipher_text"
        private const val KEY_PATTERN_IV = "pattern_iv"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_FAILED_AUTH_ATTEMPTS = "failed_auth_attempts"
        private const val KEY_SESSION_UNLOCKED_AT_MS = "session_unlocked_at_ms"
        private const val KEY_SENSITIVE_AUTH_REASON = "sensitive_auth_reason"
        private const val KEY_SENSITIVE_AUTH_AT_MS = "sensitive_auth_at_ms"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val ITERATION_COUNT = 120_000
        private const val HASH_SIZE_BITS = 256
        private const val SALT_SIZE = 16
        private const val MIN_PATTERN_POINTS = 4
        private const val AUTH_FAILURE_THRESHOLD = 5
        private const val SESSION_DURATION_MS = 30 * 60 * 1000L
        private const val SENSITIVE_AUTH_DURATION_MS = 60 * 1000L
        private val PIN_REGEX = Regex("^\\d{4,8}$")
    }
}
