package com.example.kyvc_androidapp.wallet.core

import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECPublicKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Security
import java.security.Signature
import java.util.Base64

class VpSignerTest {
    @Test
    fun signCompactJwsCreatesVerifiableEs256kJwt() {
        ensureBouncyCastleProvider()
        val privateScalar = hexToBytes("0000000000000000000000000000000000000000000000000000000000000001")
        val protectedHeader = """{"alg":"ES256K","typ":"vp+jwt","cty":"vp","kid":"did:xrpl:1:rHolder#holder-key-1","challenge":"challenge-1","domain":"kyvc.local"}"""
        val payload = """{"@context":["https://www.w3.org/ns/credentials/v2"],"type":["VerifiablePresentation"],"holder":"did:xrpl:1:rHolder","verifiableCredential":[{"@context":"https://www.w3.org/ns/credentials/v2","id":"data:application/vc+jwt,eyJhbGciOiJFUzI1Nksi","type":"EnvelopedVerifiableCredential"}]}"""

        val jwt = VpSigner().signCompactJws(privateScalar, protectedHeader, payload)
        val parts = jwt.split(".")

        assertEquals(3, parts.size)
        assertFalse(parts.any { it.contains("=") })
        assertArrayEquals(protectedHeader.toByteArray(Charsets.UTF_8), base64UrlDecode(parts[0]))
        assertArrayEquals(payload.toByteArray(Charsets.UTF_8), base64UrlDecode(parts[1]))
        assertEquals(64, base64UrlDecode(parts[2]).size)
        assertTrue(verifyEs256k(jwt, privateScalar))
    }

    private fun verifyEs256k(jwt: String, privateScalar: ByteArray): Boolean {
        val parts = jwt.split(".")
        val signingInput = "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)
        val rawSignature = base64UrlDecode(parts[2])
        val publicKey = publicKeyFromPrivateScalar(privateScalar)
        val verifier = Signature.getInstance("SHA256withECDSA", BouncyCastleProvider.PROVIDER_NAME)
        verifier.initVerify(publicKey)
        verifier.update(signingInput)
        return verifier.verify(joseRaw64ToDer(rawSignature))
    }

    private fun publicKeyFromPrivateScalar(privateScalar: ByteArray): java.security.PublicKey {
        val params = ECNamedCurveTable.getParameterSpec("secp256k1")
        val publicPoint = params.g.multiply(BigInteger(1, privateScalar)).normalize()
        val keySpec = ECPublicKeySpec(publicPoint, params)
        return KeyFactory.getInstance("ECDSA", BouncyCastleProvider.PROVIDER_NAME).generatePublic(keySpec)
    }

    private fun joseRaw64ToDer(rawSignature: ByteArray): ByteArray {
        require(rawSignature.size == 64)
        val r = rawSignature.copyOfRange(0, 32).stripLeadingZeroesForDer()
        val s = rawSignature.copyOfRange(32, 64).stripLeadingZeroesForDer()
        val bodyLength = 2 + r.size + 2 + s.size
        return byteArrayOf(0x30, bodyLength.toByte(), 0x02, r.size.toByte()) +
            r +
            byteArrayOf(0x02, s.size.toByte()) +
            s
    }

    private fun ByteArray.stripLeadingZeroesForDer(): ByteArray {
        val strippedValue = dropWhile { it == 0.toByte() }.toByteArray()
        val stripped = if (strippedValue.isEmpty()) byteArrayOf(0) else strippedValue
        return if ((stripped[0].toInt() and 0x80) != 0) byteArrayOf(0) + stripped else stripped
    }

    private fun base64UrlDecode(value: String): ByteArray {
        return Base64.getUrlDecoder().decode(value)
    }

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ensureBouncyCastleProvider() {
        val provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (provider?.javaClass?.name != BouncyCastleProvider::class.java.name) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.addProvider(BouncyCastleProvider())
        }
    }
}
