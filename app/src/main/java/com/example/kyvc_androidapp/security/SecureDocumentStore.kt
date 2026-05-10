package com.example.kyvc_androidapp.security

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class SecureDocumentStore(
    private val context: Context
) {
    private val docsDir: File = File(context.filesDir, "holder_docs").apply { mkdirs() }

    fun encryptAndStore(rawBytes: ByteArray, originalFilename: String?): StoredBlob {
        val encrypted = encrypt(rawBytes)
        val safeName = sanitizeFilename(originalFilename)
        val fileName = "${System.currentTimeMillis()}-${UUID.randomUUID()}-$safeName.bin"
        val file = File(docsDir, fileName)
        file.writeBytes(encrypted.iv + encrypted.cipherText)
        return StoredBlob(
            blobPath = file.absolutePath,
            originalFilename = safeName.ifBlank { "document" },
            byteSize = rawBytes.size.toLong()
        )
    }

    fun loadDecrypted(path: String): ByteArray {
        val file = File(path)
        require(file.exists()) { "stored document file not found" }
        val all = file.readBytes()
        require(all.size > IV_SIZE_BYTES) { "stored document is corrupted" }
        val iv = all.copyOfRange(0, IV_SIZE_BYTES)
        val cipherText = all.copyOfRange(IV_SIZE_BYTES, all.size)
        return decrypt(iv, cipherText)
    }

    private fun sanitizeFilename(name: String?): String {
        if (name.isNullOrBlank()) return "document"
        return name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
    }

    private fun encrypt(bytes: ByteArray): EncryptedValue {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val cipherText = cipher.doFinal(bytes)
        return EncryptedValue(cipher.iv, cipherText)
    }

    private fun decrypt(iv: ByteArray, cipherText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(cipherText)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val keyGenSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(keyGenSpec)
        return keyGenerator.generateKey()
    }

    data class StoredBlob(
        val blobPath: String,
        val originalFilename: String,
        val byteSize: Long
    )

    private data class EncryptedValue(
        val iv: ByteArray,
        val cipherText: ByteArray
    )

    companion object {
        private const val KEY_ALIAS = "kyvc-holder-doc-vault"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_SIZE_BYTES = 12
    }
}
