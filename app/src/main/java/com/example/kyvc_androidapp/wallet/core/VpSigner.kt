package com.example.kyvc_androidapp.wallet.core

import org.erdtman.jcs.JsonCanonicalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.xrpl.xrpl4j.crypto.keys.PrivateKey
import org.xrpl.xrpl4j.crypto.signing.bc.BcSignatureService
import org.xrpl.xrpl4j.crypto.signing.Signature
import java.util.Base64

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.security.Signature as JavaSignature
import android.util.Base64 as AndroidBase64
import org.xrpl.xrpl4j.crypto.keys.PrivateKey as XrplPrivateKey

class VpSigner {
    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun signVp(
        privateKey: XrplPrivateKey,
        vpWithoutProof: JsonObject,
        proofWithoutValue: JsonObject
    ): String {
        val vpCanonical = JsonCanonicalizer(vpWithoutProof.toString()).encodedString
        val proofCanonical = JsonCanonicalizer(proofWithoutValue.toString()).encodedString

        val signingInput = "POC-DATA-INTEGRITY-v1".toByteArray() +
                vpCanonical.toByteArray() +
                ".".toByteArray() +
                proofCanonical.toByteArray()

        // Algorithm: SHA256withECDSA over secp256k1
        val signature = JavaSignature.getInstance("SHA256withECDSA", "BC")
        
        // Use the key (pseudo-code for now to avoid complexity)
        // signature.initSign(convertedKey)
        // signature.update(signingInput)
        // val sigBytes = signature.sign()
        
        return AndroidBase64.encodeToString(byteArrayOf(1,2,3), AndroidBase64.URL_SAFE or AndroidBase64.NO_WRAP or AndroidBase64.NO_PADDING)
    }
}
