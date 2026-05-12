package com.example.kyvc_androidapp.wallet.core

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import cash.z.ecc.android.bip39.Mnemonics
import com.example.kyvc_androidapp.domain.model.XrplAccount
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.bouncycastle.jce.ECNamedCurveTable
import org.xrpl.xrpl4j.crypto.keys.Entropy
import org.xrpl.xrpl4j.crypto.keys.Seed
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import cash.z.ecc.android.bip39.toEntropy

class WalletStateStore(
    context: Context,
    private val walletManager: WalletManager
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasWallet(): Boolean {
        migrateLegacyWalletIfNecessary()
        val accounts = prefs.getStringSet(KEY_WALLET_ACCOUNTS, emptySet()).orEmpty()
        return accounts.isNotEmpty() || prefs.contains(KEY_ACTIVE_ACCOUNT)
    }

    fun getOwnerBinding(): WalletOwnerBinding? {
        val ownerHash = prefs.getString(KEY_OWNER_USER_HASH, null)?.takeIf { it.isNotBlank() } ?: return null
        return WalletOwnerBinding(
            userHash = ownerHash,
            displayHint = prefs.getString(KEY_OWNER_DISPLAY_HINT, null),
            environment = prefs.getString(KEY_OWNER_ENVIRONMENT, null),
            boundAtMillis = prefs.getLong(KEY_OWNER_BOUND_AT_MS, 0L).takeIf { it > 0L }
        )
    }

    fun bindOwner(
        userHash: String,
        displayHint: String? = null,
        environment: String? = null
    ): WalletOwnerBinding {
        require(userHash.isNotBlank()) { "userHash is required" }
        val existing = getOwnerBinding()
        require(existing == null || existing.userHash == userHash) {
            "Wallet is already bound to another web user"
        }
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_OWNER_USER_HASH, userHash)
            .putString(KEY_OWNER_DISPLAY_HINT, displayHint.orEmpty())
            .putString(KEY_OWNER_ENVIRONMENT, environment.orEmpty())
            .putLong(KEY_OWNER_BOUND_AT_MS, existing?.boundAtMillis ?: now)
            .apply()
        return getOwnerBinding() ?: WalletOwnerBinding(
            userHash = userHash,
            displayHint = displayHint,
            environment = environment,
            boundAtMillis = now
        )
    }

    fun ownerMatches(userHash: String): Boolean {
        val owner = getOwnerBinding() ?: return false
        return owner.userHash == userHash
    }

    fun clearAllWalletsAndOwner() {
        migrateLegacyWalletIfNecessary()
        val accounts = prefs.getStringSet(KEY_WALLET_ACCOUNTS, emptySet())?.toSet().orEmpty()
        val editor = prefs.edit()
        accounts.forEach { account ->
            editor.remove("wallet:${account}:encrypted_seed")
                .remove("wallet:${account}:seed_iv")
                .remove("wallet:${account}:public_key")
                .remove("wallet:${account}:auth_encrypted_private_key")
                .remove("wallet:${account}:auth_private_key_iv")
                .remove("wallet:${account}:auth_public_key")
                .remove("wallet:${account}:did")
                .remove("wallet:${account}:encrypted_mnemonic")
                .remove("wallet:${account}:mnemonic_iv")
                .remove("wallet:${account}:index")
                .remove("wallet:${account}:name")
        }
        editor.remove(KEY_WALLET_ACCOUNTS)
            .remove(KEY_ACTIVE_ACCOUNT)
            .remove(KEY_OWNER_USER_HASH)
            .remove(KEY_OWNER_DISPLAY_HINT)
            .remove(KEY_OWNER_ENVIRONMENT)
            .remove(KEY_OWNER_BOUND_AT_MS)
            .apply()
    }

    fun createWallet(overwrite: Boolean = false): WalletState {
        migrateLegacyWalletIfNecessary()
        
        // Multi-wallet is now HD-first.
        // If overwrite is false and we already have wallets, return an existing wallet instead of creating a new one.
        if (!overwrite) {
            getWalletStateOrNull()?.let { return it }
            listWallets().firstOrNull()?.let { existing ->
                prefs.edit().putString(KEY_ACTIVE_ACCOUNT, existing.account).apply()
                return existing
            }
        }
        
        val mnemonic = walletManager.createRandomMnemonic()
        val seed = walletManager.mnemonicToSeed(mnemonic)
        val seedValue = walletManager.seedToBase58(seed)
        return persistWallet(seedValue = seedValue, mnemonic = mnemonic, replaceExisting = overwrite)
    }

    fun restoreWallet(
        seedValue: String,
        overwrite: Boolean = false,
        authPrivateKeyHex: String? = null
    ): WalletState {
        migrateLegacyWalletIfNecessary()
        require(seedValue.isNotBlank()) { "seed is required" }
        return persistWallet(
            seedValue = seedValue.trim(),
            replaceExisting = overwrite,
            authKey = authPrivateKeyHex?.trim().takeUnless { it.isNullOrBlank() }?.let(::holderAuthKeyFromPrivateHex)
        )
    }

    fun restoreWalletWithMnemonic(
        mnemonicString: String,
        overwrite: Boolean = false,
        authPrivateKeyHex: String? = null
    ): WalletState {
        migrateLegacyWalletIfNecessary()
        val mnemonic = mnemonicString.trim().toCharArray()
        val seed = walletManager.mnemonicToSeed(mnemonic)
        val seedValue = walletManager.seedToBase58(seed)
        return persistWallet(
            seedValue = seedValue,
            mnemonic = mnemonic,
            replaceExisting = overwrite,
            authKey = authPrivateKeyHex?.trim().takeUnless { it.isNullOrBlank() }?.let(::holderAuthKeyFromPrivateHex)
        )
    }

    fun getWalletStateOrNull(): WalletState? {
        migrateLegacyWalletIfNecessary()
        val activeAccount = prefs.getString(KEY_ACTIVE_ACCOUNT, null)
            ?: prefs.getStringSet(KEY_WALLET_ACCOUNTS, emptySet())
                .orEmpty()
                .sorted()
                .firstOrNull()
                ?.also { prefs.edit().putString(KEY_ACTIVE_ACCOUNT, it).apply() }
            ?: return null
        return getWalletByAccount(activeAccount)
    }

    fun listWallets(): List<WalletState> {
        migrateLegacyWalletIfNecessary()
        val accounts = prefs.getStringSet(KEY_WALLET_ACCOUNTS, emptySet()) ?: emptySet()
        return accounts.mapNotNull { getWalletByAccount(it) }
    }

    fun switchWallet(account: String) {
        migrateLegacyWalletIfNecessary()
        val accounts = prefs.getStringSet(KEY_WALLET_ACCOUNTS, emptySet()) ?: emptySet()
        require(account in accounts) { "Wallet not found: $account" }
        prefs.edit().putString(KEY_ACTIVE_ACCOUNT, account).apply()
    }

    fun clearActiveWalletSelection() {
        migrateLegacyWalletIfNecessary()
        prefs.edit().remove(KEY_ACTIVE_ACCOUNT).apply()
    }

    fun removeWallet(account: String): Boolean {
        migrateLegacyWalletIfNecessary()
        val accounts = prefs.getStringSet(KEY_WALLET_ACCOUNTS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (account !in accounts) return false

        accounts.remove(account)
        val editor = prefs.edit()
        // Put a fresh copy to avoid getStringSet reference corner-cases.
        editor.putStringSet(KEY_WALLET_ACCOUNTS, HashSet(accounts))

        if (prefs.getString(KEY_ACTIVE_ACCOUNT, null) == account) {
            editor.putString(KEY_ACTIVE_ACCOUNT, accounts.firstOrNull())
        }

        // Clean up account-specific data
        editor.remove("wallet:${account}:encrypted_seed")
            .remove("wallet:${account}:seed_iv")
            .remove("wallet:${account}:public_key")
            .remove("wallet:${account}:auth_encrypted_private_key")
            .remove("wallet:${account}:auth_private_key_iv")
            .remove("wallet:${account}:auth_public_key")
            .remove("wallet:${account}:did")
            .remove("wallet:${account}:encrypted_mnemonic")
            .remove("wallet:${account}:mnemonic_iv")
            .remove("wallet:${account}:index")
            .remove("wallet:${account}:name")

        editor.apply()
        return true
    }

    fun setAccountName(account: String, name: String) {
        prefs.edit().putString("wallet:${account}:name", name).apply()
    }

    fun upgradeToMnemonic(account: String): String {
        val existingSeedValue = exportSeedValue(account)
        val seed = walletManager.fromSeed(existingSeedValue)
        val entropy = seed.decodedSeed().bytes().toByteArray()
        val mnemonicCode = Mnemonics.MnemonicCode(entropy)
        val mnemonicChars = mnemonicCode.chars
        
        val editor = prefs.edit()
        val encryptedMnemonic = encrypt(String(mnemonicChars))
        editor.putString("wallet:${account}:encrypted_mnemonic", encryptedMnemonic.cipherText)
            .putString("wallet:${account}:mnemonic_iv", encryptedMnemonic.iv)
            .apply()
            
        return String(mnemonicChars)
    }

    fun deriveNextAccount(masterAccount: String, customName: String? = null): WalletState {
        val mnemonicString = exportMnemonicValue(masterAccount)
        require(mnemonicString.isNotBlank()) { "Mnemonic is required for derivation" }
        
        val mnemonic = mnemonicString.toCharArray()
        // Find existing indices for this mnemonic grouping
        val accounts = listWallets()
        
        // For simple grouping, we look at wallets that share the same mnemonic value
        val existingIndices = accounts.filter { 
            exportMnemonicValue(it.account) == mnemonicString 
        }.map { it.derivationIndex }
        
        val nextIndex = (existingIndices.maxOrNull() ?: 0) + 1
        
        // Simplified derivation for POC: 
        val masterEntropy = Mnemonics.MnemonicCode(mnemonic).toEntropy()
        val nextEntropy = MessageDigest.getInstance("SHA-256").digest(masterEntropy + nextIndex.toString().toByteArray())
        val truncatedEntropy = nextEntropy.copyOfRange(0, 16)
        
        val nextSeed = Seed.secp256k1SeedFromEntropy(Entropy.of(truncatedEntropy))
        val nextSeedValue = walletManager.seedToBase58(nextSeed)
        
        val name = customName ?: "Account ${nextIndex + 1}"
        
        return persistWallet(
            seedValue = nextSeedValue,
            mnemonic = mnemonic,
            replaceExisting = false,
            derivationIndex = nextIndex,
            customName = name
        )
    }

    private fun getWalletByAccount(account: String): WalletState? {
        val publicKey = prefs.getString("wallet:${account}:public_key", null) ?: return null
        val did = prefs.getString("wallet:${account}:did", "did:xrpl:1:$account") ?: "did:xrpl:1:$account"
        val authPublicKey = ensureHolderAuthKey(account).publicKeyHex
        val mnemonic = try { exportMnemonicValue(account).takeIf { it.isNotBlank() } } catch (e: Exception) { null }
        val derivationIndex = prefs.getInt("wallet:${account}:index", 0)
        val name = prefs.getString("wallet:${account}:name", "Account ${derivationIndex + 1}") ?: "Account ${derivationIndex + 1}"
        
        return WalletState(
            account = account, 
            publicKey = publicKey, 
            authPublicKey = authPublicKey, 
            did = did, 
            mnemonic = mnemonic,
            derivationIndex = derivationIndex,
            name = name
        )
    }

    fun requireSeed() = walletManager.fromSeed(requireSeedValue())

    fun exportSeedValue(account: String? = null): String {
        val targetAccount = account ?: prefs.getString(KEY_ACTIVE_ACCOUNT, null) ?: throw IllegalStateException("No active wallet")
        val cipherText = prefs.getString("wallet:${targetAccount}:encrypted_seed", null)
        val iv = prefs.getString("wallet:${targetAccount}:seed_iv", null)
        require(!cipherText.isNullOrBlank() && !iv.isNullOrBlank()) { "Wallet seed not found for $targetAccount" }
        return decrypt(EncryptedValue(cipherText = cipherText, iv = iv))
    }

    fun exportMnemonicValue(account: String? = null): String {
        val targetAccount = account ?: prefs.getString(KEY_ACTIVE_ACCOUNT, null) ?: return ""
        val cipherText = prefs.getString("wallet:${targetAccount}:encrypted_mnemonic", null)
        val iv = prefs.getString("wallet:${targetAccount}:mnemonic_iv", null)
        if (cipherText.isNullOrBlank() || iv.isNullOrBlank()) {
            return ""
        }
        return decrypt(EncryptedValue(cipherText = cipherText, iv = iv))
    }

    fun exportAuthPrivateKeyHex(account: String? = null): String {
        val targetAccount = account ?: prefs.getString(KEY_ACTIVE_ACCOUNT, null) ?: throw IllegalStateException("No active wallet")
        val cipherText = prefs.getString("wallet:${targetAccount}:auth_encrypted_private_key", null)
        val iv = prefs.getString("wallet:${targetAccount}:auth_private_key_iv", null)
        require(!cipherText.isNullOrBlank() && !iv.isNullOrBlank()) { "Holder auth key not found for $targetAccount" }
        return decrypt(EncryptedValue(cipherText = cipherText, iv = iv))
    }

    fun requireAuthPrivateKeyBytes(): ByteArray {
        val account = prefs.getString(KEY_ACTIVE_ACCOUNT, null) ?: throw IllegalStateException("No active wallet")
        return exportAuthPrivateKeyHex(account).hexToBytes()
    }

    fun requireWalletState(): WalletState {
        return getWalletStateOrNull() ?: throw IllegalStateException("Wallet has not been created")
    }

    private fun requireSeedValue(): String {
        return exportSeedValue()
    }

    private fun ensureHolderAuthKey(account: String): HolderAuthKey {
        val publicKey = prefs.getString("wallet:${account}:auth_public_key", null)
        val encryptedPrivateKey = prefs.getString("wallet:${account}:auth_encrypted_private_key", null)
        val iv = prefs.getString("wallet:${account}:auth_private_key_iv", null)
        if (!publicKey.isNullOrBlank() && !encryptedPrivateKey.isNullOrBlank() && !iv.isNullOrBlank()) {
            return HolderAuthKey(publicKeyHex = publicKey, privateKeyHex = "")
        }

        val authKey = createHolderAuthKey()
        val encryptedAuthPrivateKey = encrypt(authKey.privateKeyHex)
        prefs.edit()
            .putString("wallet:${account}:auth_encrypted_private_key", encryptedAuthPrivateKey.cipherText)
            .putString("wallet:${account}:auth_private_key_iv", encryptedAuthPrivateKey.iv)
            .putString("wallet:${account}:auth_public_key", authKey.publicKeyHex)
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

    private fun holderAuthKeyFromPrivateHex(privateKeyHex: String): HolderAuthKey {
        val normalized = privateKeyHex.trim().uppercase()
        require(normalized.matches(Regex("^[0-9A-F]{64}$"))) {
            "authPrivateKeyHex must be a 32-byte hex string"
        }
        val params = ECNamedCurveTable.getParameterSpec("secp256k1")
        val d = BigInteger(normalized, 16)
        require(d != BigInteger.ZERO && d < params.n) {
            "authPrivateKeyHex is out of secp256k1 range"
        }
        val publicPoint = params.g.multiply(d).normalize()
        return HolderAuthKey(
            publicKeyHex = publicPoint.getEncoded(true).toHex(),
            privateKeyHex = normalized
        )
    }

    private fun persistWallet(
        seedValue: String,
        replaceExisting: Boolean,
        mnemonic: CharArray? = null,
        authKey: HolderAuthKey? = null,
        derivationIndex: Int = 0,
        customName: String? = null
    ): WalletState {
        val seed = walletManager.fromSeed(seedValue)
        val account = walletManager.getXrplAccount(seed).address
        val publicKey = walletManager.getXrplAccount(seed).publicKey
        val did = "did:xrpl:1:$account"

        // If the same account already exists and overwrite is not requested,
        // just switch active account and keep existing metadata/keys.
        val existingWallet = getWalletByAccount(account)
        if (existingWallet != null && !replaceExisting) {
            prefs.edit().putString(KEY_ACTIVE_ACCOUNT, account).apply()
            return existingWallet
        }
        
        val encryptedSeed = encrypt(seedValue)
        val resolvedAuthKey = authKey ?: createHolderAuthKey()
        val encryptedAuthPrivateKey = encrypt(resolvedAuthKey.privateKeyHex)

        val editor = prefs.edit()
        
        // Add to accounts list
        val currentAccounts = prefs.getStringSet(KEY_WALLET_ACCOUNTS, emptySet())?.toMutableSet() ?: mutableSetOf()
        currentAccounts.add(account)
        editor.putStringSet(KEY_WALLET_ACCOUNTS, currentAccounts)
        editor.putString(KEY_ACTIVE_ACCOUNT, account)

        // Store specific wallet data
        editor.putString("wallet:${account}:encrypted_seed", encryptedSeed.cipherText)
            .putString("wallet:${account}:seed_iv", encryptedSeed.iv)
            .putString("wallet:${account}:public_key", publicKey)
            .putString("wallet:${account}:auth_encrypted_private_key", encryptedAuthPrivateKey.cipherText)
            .putString("wallet:${account}:auth_private_key_iv", encryptedAuthPrivateKey.iv)
            .putString("wallet:${account}:auth_public_key", resolvedAuthKey.publicKeyHex)
            .putString("wallet:${account}:did", did)
            .putInt("wallet:${account}:index", derivationIndex)
        
        customName?.let { editor.putString("wallet:${account}:name", it) }

        if (mnemonic != null) {
            val encryptedMnemonic = encrypt(String(mnemonic))
            editor.putString("wallet:${account}:encrypted_mnemonic", encryptedMnemonic.cipherText)
                .putString("wallet:${account}:mnemonic_iv", encryptedMnemonic.iv)
        }

        editor.apply()

        val finalName = customName ?: "Account ${derivationIndex + 1}"
        return WalletState(
            account = account,
            publicKey = publicKey,
            authPublicKey = resolvedAuthKey.publicKeyHex,
            did = did,
            mnemonic = mnemonic?.let { String(it) },
            derivationIndex = derivationIndex,
            name = finalName
        )
    }

    private fun migrateLegacyWalletIfNecessary() {
        if (prefs.contains(KEY_ENCRYPTED_SEED) && !prefs.contains(KEY_ACTIVE_ACCOUNT)) {
            val seedValue = decrypt(EncryptedValue(
                cipherText = prefs.getString(KEY_ENCRYPTED_SEED, "").orEmpty(),
                iv = prefs.getString(KEY_IV, "").orEmpty()
            ))
            val mnemonicValue = try {
                val mEnc = prefs.getString(KEY_ENCRYPTED_MNEMONIC, null)
                val mIv = prefs.getString(KEY_MNEMONIC_IV, null)
                if (mEnc != null && mIv != null) {
                    decrypt(EncryptedValue(cipherText = mEnc, iv = mIv))
                } else null
            } catch (e: Exception) { null }

            val authPrivateKey = try {
                val aEnc = prefs.getString(KEY_AUTH_ENCRYPTED_PRIVATE_KEY, null)
                val aIv = prefs.getString(KEY_AUTH_IV, null)
                if (aEnc != null && aIv != null) {
                    decrypt(EncryptedValue(cipherText = aEnc, iv = aIv))
                } else null
            } catch (e: Exception) { null }
            
            persistWallet(
                seedValue = seedValue,
                replaceExisting = false,
                mnemonic = mnemonicValue?.toCharArray(),
                authKey = authPrivateKey?.let { holderAuthKeyFromPrivateHex(it) }
            )
            
            // Clean up legacy keys ONLY after successful migration
            prefs.edit()
                .remove(KEY_ENCRYPTED_SEED)
                .remove(KEY_IV)
                .remove(KEY_ACCOUNT)
                .remove(KEY_PUBLIC_KEY)
                .remove(KEY_AUTH_ENCRYPTED_PRIVATE_KEY)
                .remove(KEY_AUTH_IV)
                .remove(KEY_AUTH_PUBLIC_KEY)
                .remove(KEY_DID)
                .remove(KEY_ENCRYPTED_MNEMONIC)
                .remove(KEY_MNEMONIC_IV)
                .apply()
        }
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
        val did: String,
        val mnemonic: String? = null,
        val derivationIndex: Int = 0,
        val name: String = "Account 1"
    ) {
        fun toXrplAccount(): XrplAccount {
            return XrplAccount(address = account, publicKey = publicKey, did = did)
        }
    }

    data class WalletOwnerBinding(
        val userHash: String,
        val displayHint: String?,
        val environment: String?,
        val boundAtMillis: Long?
    )

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
        private const val KEY_ENCRYPTED_MNEMONIC = "encrypted_mnemonic"
        private const val KEY_MNEMONIC_IV = "mnemonic_iv"
        private const val KEY_WALLET_ACCOUNTS = "wallet_accounts"
        private const val KEY_ACTIVE_ACCOUNT = "active_account"
        private const val KEY_OWNER_USER_HASH = "wallet_owner_user_hash"
        private const val KEY_OWNER_DISPLAY_HINT = "wallet_owner_display_hint"
        private const val KEY_OWNER_ENVIRONMENT = "wallet_owner_environment"
        private const val KEY_OWNER_BOUND_AT_MS = "wallet_owner_bound_at_ms"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
