package com.example.kyvc_androidapp.wallet.core

import org.erdtman.jcs.JsonCanonicalizer
import kotlinx.serialization.json.JsonObject
import java.security.Security
import java.security.Signature as JavaSignature
import android.util.Base64 as AndroidBase64
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECPrivateKeySpec
import org.bouncycastle.jce.ECNamedCurveTable
import org.xrpl.xrpl4j.crypto.keys.PrivateKey as XrplPrivateKey
import java.security.KeyFactory
import java.math.BigInteger

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
        return AndroidBase64.encodeToString(sigBytes, AndroidBase64.URL_SAFE or AndroidBase64.NO_WRAP or AndroidBase64.NO_PADDING)
    }
}
