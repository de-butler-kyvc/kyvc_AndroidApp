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

        prefs.edit()
            .putString(KEY_ENCRYPTED_SEED, encryptedSeed.cipherText)
            .putString(KEY_IV, encryptedSeed.iv)
            .putString(KEY_ACCOUNT, account.address)
            .putString(KEY_PUBLIC_KEY, account.publicKey)
            .putString(KEY_DID, account.did)
            .apply()

        return WalletState(
            account = account.address,
            publicKey = account.publicKey,
            did = account.did
        )
    }

    fun getWalletStateOrNull(): WalletState? {
        val account = prefs.getString(KEY_ACCOUNT, null) ?: return null
        val publicKey = prefs.getString(KEY_PUBLIC_KEY, null) ?: return null
        val did = prefs.getString(KEY_DID, null) ?: "did:xrpl:1:$account"
        return WalletState(account = account, publicKey = publicKey, did = did)
    }

    fun requireSeed() = walletManager.fromSeed(requireSeedValue())

    fun requireWalletState(): WalletState {
        return getWalletStateOrNull() ?: throw IllegalStateException("Wallet has not been created")
    }

    private fun requireSeedValue(): String {
        val cipherText = prefs.getString(KEY_ENCRYPTED_SEED, null)
        val iv = prefs.getString(KEY_IV, null)
        require(!cipherText.isNullOrBlank() && !iv.isNullOrBlank()) { "Wallet has not been created" }
        return decrypt(EncryptedValue(cipherText = cipherText, iv = iv))
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
        val did: String
    ) {
        fun toXrplAccount(): XrplAccount {
            return XrplAccount(address = account, publicKey = publicKey, did = did)
        }
    }

    private data class EncryptedValue(
        val cipherText: String,
        val iv: String
    )

    private companion object {
        private const val PREFS_NAME = "kyvc_holder_wallet"
        private const val KEY_ALIAS = "kyvc_holder_wallet_seed_key"
        private const val KEY_ENCRYPTED_SEED = "encrypted_seed"
        private const val KEY_IV = "seed_iv"
        private const val KEY_ACCOUNT = "account"
        private const val KEY_PUBLIC_KEY = "public_key"
        private const val KEY_DID = "did"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
