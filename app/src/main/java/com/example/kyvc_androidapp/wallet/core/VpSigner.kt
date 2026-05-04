package com.example.kyvc_androidapp.wallet.core

import org.erdtman.jcs.JsonCanonicalizer
import kotlinx.serialization.json.JsonObject
import java.security.Security
import java.security.Signature as JavaSignature
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECPrivateKeySpec
import org.bouncycastle.jce.ECNamedCurveTable
import org.xrpl.xrpl4j.crypto.keys.PrivateKey as XrplPrivateKey
import java.security.KeyFactory
import java.math.BigInteger
import java.util.Base64

class VpSigner {
    init {
        val provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (provider?.javaClass?.name != BouncyCastleProvider::class.java.name) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun signVp(
        privateKey: XrplPrivateKey,
        vpWithoutProof: JsonObject,
        proofWithoutValue: JsonObject
    ): String {
        // 1. Canonicalize
        val vpCanonical = JsonCanonicalizer(vpWithoutProof.toString()).encodedString
        val proofCanonical = JsonCanonicalizer(proofWithoutValue.toString()).encodedString

        // 2. Prepare signing input
        val signingInput = "POC-DATA-INTEGRITY-v1".toByteArray() +
                vpCanonical.toByteArray() +
                ".".toByteArray() +
                proofCanonical.toByteArray()

        // 3. Convert XrplPrivateKey to Java PrivateKey
        val rawKeyBytes = privateKey.naturalBytes().toByteArray()
        val d = BigInteger(1, rawKeyBytes)
        
        val ecSpec = ECNamedCurveTable.getParameterSpec("secp256k1")
        val privKeySpec = ECPrivateKeySpec(d, ecSpec)
        val keyFactory = KeyFactory.getInstance("ECDSA", "BC")
        val javaPrivKey = keyFactory.generatePrivate(privKeySpec)

        // 4. Sign
        val signature = JavaSignature.getInstance("SHA256withECDSA", "BC")
        signature.initSign(javaPrivKey)
        signature.update(signingInput)
        val sigBytes = signature.sign()
        
        // 5. Encode as Base64Url NoPadding
        return base64UrlNoPadding(sigBytes)
    }

    fun signVp(
        privateKeyScalar: ByteArray,
        vpWithoutProof: JsonObject,
        proofWithoutValue: JsonObject
    ): String {
        val vpCanonical = JsonCanonicalizer(vpWithoutProof.toString()).encodedString
        val proofCanonical = JsonCanonicalizer(proofWithoutValue.toString()).encodedString
        val signingInput = "POC-DATA-INTEGRITY-v1".toByteArray() +
                vpCanonical.toByteArray() +
                ".".toByteArray() +
                proofCanonical.toByteArray()
        val sigBytes = signDer(privateKeyScalar, signingInput)
        return base64UrlNoPadding(sigBytes)
    }

    fun signCompactJws(
        privateKey: XrplPrivateKey,
        protectedHeaderJson: String,
        payloadJson: String
    ): String {
        val encodedHeader = base64UrlNoPadding(protectedHeaderJson.toByteArray(Charsets.UTF_8))
        val encodedPayload = base64UrlNoPadding(payloadJson.toByteArray(Charsets.UTF_8))
        val signingInput = "$encodedHeader.$encodedPayload".toByteArray(Charsets.US_ASCII)
        val derSignature = signDer(privateKey, signingInput)
        val rawSignature = derToJoseRaw64(derSignature)
        return "$encodedHeader.$encodedPayload.${base64UrlNoPadding(rawSignature)}"
    }

    fun signCompactJws(
        privateKeyScalar: ByteArray,
        protectedHeaderJson: String,
        payloadJson: String
    ): String {
        val encodedHeader = base64UrlNoPadding(protectedHeaderJson.toByteArray(Charsets.UTF_8))
        val encodedPayload = base64UrlNoPadding(payloadJson.toByteArray(Charsets.UTF_8))
        val signingInput = "$encodedHeader.$encodedPayload".toByteArray(Charsets.US_ASCII)
        val derSignature = signDer(privateKeyScalar, signingInput)
        val rawSignature = derToJoseRaw64(derSignature)
        return "$encodedHeader.$encodedPayload.${base64UrlNoPadding(rawSignature)}"
    }

    private fun signDer(privateKey: XrplPrivateKey, signingInput: ByteArray): ByteArray {
        val rawKeyBytes = privateKey.naturalBytes().toByteArray()
        val d = BigInteger(1, rawKeyBytes)
        val ecSpec = ECNamedCurveTable.getParameterSpec("secp256k1")
        val privKeySpec = ECPrivateKeySpec(d, ecSpec)
        val keyFactory = KeyFactory.getInstance("ECDSA", "BC")
        val javaPrivKey = keyFactory.generatePrivate(privKeySpec)
        val signature = JavaSignature.getInstance("SHA256withECDSA", "BC")
        signature.initSign(javaPrivKey)
        signature.update(signingInput)
        return signature.sign()
    }

    private fun signDer(privateKeyScalar: ByteArray, signingInput: ByteArray): ByteArray {
        val d = BigInteger(1, privateKeyScalar)
        val ecSpec = ECNamedCurveTable.getParameterSpec("secp256k1")
        val privKeySpec = ECPrivateKeySpec(d, ecSpec)
        val keyFactory = KeyFactory.getInstance("ECDSA", "BC")
        val javaPrivKey = keyFactory.generatePrivate(privKeySpec)
        val signature = JavaSignature.getInstance("SHA256withECDSA", "BC")
        signature.initSign(javaPrivKey)
        signature.update(signingInput)
        return signature.sign()
    }

    private fun base64UrlNoPadding(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun derToJoseRaw64(derSignature: ByteArray): ByteArray {
        require(derSignature.size >= 8 && derSignature[0] == 0x30.toByte()) {
            "Invalid DER ECDSA signature"
        }
        var offset = 2
        if ((derSignature[1].toInt() and 0x80) != 0) {
            val lengthBytes = derSignature[1].toInt() and 0x7F
            offset = 2 + lengthBytes
        }
        require(derSignature[offset] == 0x02.toByte()) { "Invalid DER ECDSA R marker" }
        val rLength = derSignature[offset + 1].toInt() and 0xFF
        val r = derSignature.copyOfRange(offset + 2, offset + 2 + rLength)
        offset += 2 + rLength
        require(derSignature[offset] == 0x02.toByte()) { "Invalid DER ECDSA S marker" }
        val sLength = derSignature[offset + 1].toInt() and 0xFF
        val s = derSignature.copyOfRange(offset + 2, offset + 2 + sLength)
        return toFixed32(r) + toFixed32(s)
    }

    private fun toFixed32(value: ByteArray): ByteArray {
        val trimmed = value.dropWhile { it == 0.toByte() }.toByteArray()
        require(trimmed.size <= 32) { "ECDSA integer is too large" }
        return ByteArray(32 - trimmed.size) + trimmed
    }
}
