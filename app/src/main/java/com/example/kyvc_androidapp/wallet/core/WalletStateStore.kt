package com.example.kyvc_androidapp.wallet.core

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.kyvc_androidapp.domain.model.XrplAccount
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.bouncycastle.jce.ECNamedCurveTable
import java.math.BigInteger
import java.security.SecureRandom

class WalletStateStore(
    context: Context,
    private val walletManager: WalletManager
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasWallet(): Boolean {
        return prefs.contains(KEY_ENCRYPTED_SEED) && prefs.contains(KEY_IV)
    }

    fun createWallet(overwrite: Boolean = false): WalletState {
        if (!overwrite) {
            getWalletStateOrNull()?.let { return it }
        }

        val seed = walletManager.createRandomSeed()
        val seedValue = walletManager.seedToBase58(seed)
        val account = walletManager.getXrplAccount(seed)
        val encryptedSeed = encrypt(seedValue)
        val authKey = createHolderAuthKey()
        val encryptedAuthPrivateKey = encrypt(authKey.privateKeyHex)

        prefs.edit()
            .putString(KEY_ENCRYPTED_SEED, encryptedSeed.cipherText)
            .putString(KEY_IV, encryptedSeed.iv)
            .putString(KEY_ACCOUNT, account.address)
            .putString(KEY_PUBLIC_KEY, account.publicKey)
            .putString(KEY_AUTH_ENCRYPTED_PRIVATE_KEY, encryptedAuthPrivateKey.cipherText)
            .putString(KEY_AUTH_IV, encryptedAuthPrivateKey.iv)
            .putString(KEY_AUTH_PUBLIC_KEY, authKey.publicKeyHex)
            .putString(KEY_DID, account.did)
            .apply()

        return WalletState(
            account = account.address,
            publicKey = account.publicKey,
            authPublicKey = authKey.publicKeyHex,
            did = account.did
        )
    }

    fun getWalletStateOrNull(): WalletState? {
        val account = prefs.getString(KEY_ACCOUNT, null) ?: return null
        val publicKey = prefs.getString(KEY_PUBLIC_KEY, null) ?: return null
        val did = prefs.getString(KEY_DID, null) ?: "did:xrpl:1:$account"
        val authPublicKey = ensureHolderAuthKey().publicKeyHex
        return WalletState(account = account, publicKey = publicKey, authPublicKey = authPublicKey, did = did)
    }

    fun requireSeed() = walletManager.fromSeed(requireSeedValue())

    fun requireAuthPrivateKeyBytes(): ByteArray {
        val cipherText = prefs.getString(KEY_AUTH_ENCRYPTED_PRIVATE_KEY, null)
        val iv = prefs.getString(KEY_AUTH_IV, null)
        require(!cipherText.isNullOrBlank() && !iv.isNullOrBlank()) { "Holder auth key has not been created" }
        return decrypt(EncryptedValue(cipherText = cipherText, iv = iv)).hexToBytes()
    }

    fun requireWalletState(): WalletState {
        return getWalletStateOrNull() ?: throw IllegalStateException("Wallet has not been created")
    }

    private fun requireSeedValue(): String {
        val cipherText = prefs.getString(KEY_ENCRYPTED_SEED, null)
        val iv = prefs.getString(KEY_IV, null)
        require(!cipherText.isNullOrBlank() && !iv.isNullOrBlank()) { "Wallet has not been created" }
        return decrypt(EncryptedValue(cipherText = cipherText, iv = iv))
    }

    private fun ensureHolderAuthKey(): HolderAuthKey {
        val publicKey = prefs.getString(KEY_AUTH_PUBLIC_KEY, null)
        val encryptedPrivateKey = prefs.getString(KEY_AUTH_ENCRYPTED_PRIVATE_KEY, null)
        val iv = prefs.getString(KEY_AUTH_IV, null)
        if (!publicKey.isNullOrBlank() && !encryptedPrivateKey.isNullOrBlank() && !iv.isNullOrBlank()) {
            return HolderAuthKey(publicKeyHex = publicKey, privateKeyHex = "")
        }

        val authKey = createHolderAuthKey()
        val encryptedAuthPrivateKey = encrypt(authKey.privateKeyHex)
        prefs.edit()
            .putString(KEY_AUTH_ENCRYPTED_PRIVATE_KEY, encryptedAuthPrivateKey.cipherText)
            .putString(KEY_AUTH_IV, encryptedAuthPrivateKey.iv)
            .putString(KEY_AUTH_PUBLIC_KEY, authKey.publicKeyHex)
            .apply()
        return authKey
    }

    private fun createHolderAuthKey(): HolderAuthKey {
        val params = ECNamedCurveTable.getParameterSpec("secp256k1")
        val random = SecureRandom()
        var d: BigInteger
        do {
            d = BigInteger(params.n.bitLength(), random)
        } while (d == BigInteger.ZERO || d >= params.n)
        val publicPoint = params.g.multiply(d).normalize()
        return HolderAuthKey(
            privateKeyHex = d.toByteArray().stripLeadingZeroes().toFixedHex(32),
            publicKeyHex = publicPoint.getEncoded(true).toHex()
        )
    }

    private fun encrypt(plainText: String): EncryptedValue {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return EncryptedValue(
            cipherText = Base64.encodeToString(cipherBytes, Base64.NO_WRAP),
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        )
    }

    private fun decrypt(value: EncryptedValue): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = Base64.decode(value.iv, Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
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

    data class WalletState(
        val account: String,
        val publicKey: String,
        val authPublicKey: String,
        val did: String
    ) {
        fun toXrplAccount(): XrplAccount {
            return XrplAccount(address = account, publicKey = publicKey, did = did)
        }
    }

    private data class HolderAuthKey(
        val publicKeyHex: String,
        val privateKeyHex: String
    )

    private data class EncryptedValue(
        val cipherText: String,
        val iv: String
    )

    private fun ByteArray.stripLeadingZeroes(): ByteArray {
        val stripped = dropWhile { it == 0.toByte() }.toByteArray()
        return if (stripped.isEmpty()) byteArrayOf(0) else stripped
    }

    private fun ByteArray.toFixedHex(size: Int): String {
        require(size > 0)
        require(this.size <= size) { "Value is larger than $size bytes" }
        return (ByteArray(size - this.size) + this).toHex()
    }

    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02X".format(it) }
    }

    private fun String.hexToBytes(): ByteArray {
        val normalized = trim()
        require(normalized.length % 2 == 0) { "Invalid hex length" }
        return normalized.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private companion object {
        private const val PREFS_NAME = "kyvc_holder_wallet"
        private const val KEY_ALIAS = "kyvc_holder_wallet_seed_key"
        private const val KEY_ENCRYPTED_SEED = "encrypted_seed"
        private const val KEY_IV = "seed_iv"
        private const val KEY_ACCOUNT = "account"
        private const val KEY_PUBLIC_KEY = "public_key"
        private const val KEY_AUTH_ENCRYPTED_PRIVATE_KEY = "auth_encrypted_private_key"
        private const val KEY_AUTH_IV = "auth_private_key_iv"
        private const val KEY_AUTH_PUBLIC_KEY = "auth_public_key"
        private const val KEY_DID = "did"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
