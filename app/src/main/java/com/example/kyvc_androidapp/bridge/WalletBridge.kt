package com.example.kyvc_androidapp.bridge

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import com.example.kyvc_androidapp.data.local.AppDatabase
import com.example.kyvc_androidapp.data.local.entity.CredentialEntity
import com.example.kyvc_androidapp.wallet.core.VpSigner
import com.example.kyvc_androidapp.wallet.core.WalletStateStore
import com.example.kyvc_androidapp.wallet.core.XrplClientHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.bouncycastle.jce.ECNamedCurveTable
import org.xrpl.xrpl4j.crypto.keys.Seed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import android.util.Base64

class WalletBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val walletStateStore: WalletStateStore,
    private val xrplHelper: XrplClientHelper,
    private val db: AppDatabase,
    private val launchQrScanner: (String) -> Unit
) {
    private var currentSeed: Seed? = null
    private var webViewRef: WeakReference<WebView>? = null
    private val vpSigner = VpSigner()

    fun attachWebView(webView: WebView) {
        webViewRef = WeakReference(webView)
    }

    @JavascriptInterface
    fun checkBridge(): String {
        return "Connected"
    }

    @JavascriptInterface
    fun createWallet(jsonPayload: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val request = parseJsonObjectOrEmpty(jsonPayload)
                val overwrite = request.text("overwrite")?.toBooleanStrictOrNull() ?: false
                val walletState = walletStateStore.createWallet(overwrite = overwrite)
                currentSeed = walletStateStore.requireSeed()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Wallet ready: ${walletState.account}", Toast.LENGTH_SHORT).show()
                    emitWalletCallback("CREATE_WALLET", true, walletState)
                }
            } catch (e: Exception) {
                Log.e(TAG, "createWallet failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to create wallet: ${e.message}", Toast.LENGTH_LONG).show()
                    emitCallback("CREATE_WALLET", false) {
                        put("error", e.message ?: "Unknown error")
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun getWalletInfo(jsonPayload: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val walletState = walletStateStore.requireWalletState()
                currentSeed = walletStateStore.requireSeed()
                withContext(Dispatchers.Main) {
                    emitWalletCallback("GET_WALLET_INFO", true, walletState)
                }
            } catch (e: Exception) {
                Log.e(TAG, "getWalletInfo failed", e)
                withContext(Dispatchers.Main) {
                    emitCallback("GET_WALLET_INFO", false) {
                        put("error", e.message ?: "Unknown error")
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun saveVC(jsonPayload: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val json = Json.parseToJsonElement(jsonPayload).jsonObject
                val status = json.obj("credentialStatus")
                val subject = json.obj("credentialSubject")
                val issuerDid = json.text("issuerDid") ?: json.text("issuer").orEmpty()
                val holderDid = json.text("holderDid") ?: subject?.text("id").orEmpty()
                val entity = CredentialEntity(
                    credentialId = json.text("credentialId") ?: json.text("id").orEmpty(),
                    vcJson = json.text("vcJson") ?: jsonPayload,
                    issuerDid = issuerDid,
                    issuerAccount = json.text("issuerAccount")
                        ?: status?.text("issuer")
                        ?: accountFromDid(issuerDid),
                    holderDid = holderDid,
                    holderAccount = json.text("holderAccount")
                        ?: status?.text("subject")
                        ?: accountFromDid(holderDid),
                    credentialType = json.text("credentialType") ?: status?.text("credentialType").orEmpty(),
                    vcCoreHash = json.text("vcCoreHash") ?: status?.text("vcCoreHash").orEmpty(),
                    validFrom = json.text("validFrom") ?: json.text("issuanceDate").orEmpty(),
                    validUntil = json.text("validUntil") ?: json.text("expirationDate").orEmpty()
                )
                require(entity.credentialId.isNotBlank()) { "credentialId or id is required" }
                db.credentialDao().insertCredential(entity)
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "VC Saved Successfully", Toast.LENGTH_SHORT).show()
                    emitCallback("SAVE_VC", true) {
                        put("credentialId", entity.credentialId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "saveVC failed", e)
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to save VC: ${e.message}", Toast.LENGTH_LONG).show()
                    emitCallback("SAVE_VC", false) {
                        put("error", e.message ?: "Unknown error")
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun submitToXRPL(jsonPayload: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val request = Json.parseToJsonElement(jsonPayload).jsonObject
                val seed = walletStateStore.requireSeed()
                currentSeed = seed
                val walletState = walletStateStore.requireWalletState()

                val savedCredential = request.text("credentialId")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { db.credentialDao().getCredentialById(it) }
                val vcStatus = request.obj("credentialStatus")
                    ?: request.obj("credential")?.obj("credentialStatus")
                    ?: request.text("vcJson")?.let { runCatching { Json.parseToJsonElement(it).jsonObject.obj("credentialStatus") }.getOrNull() }

                val issuerAccount = request.text("issuerAccount")
                    ?: vcStatus?.text("issuer")
                    ?: savedCredential?.issuerAccount
                    ?: accountFromDid(request.text("issuerDid").orEmpty())
                val credentialType = request.text("credentialType")
                    ?: vcStatus?.text("credentialType")
                    ?: savedCredential?.credentialType
                val expectedHolderAccount = request.text("holderAccount")
                    ?: vcStatus?.text("subject")
                    ?: savedCredential?.holderAccount
                    ?: walletState.account

                require(!issuerAccount.isNullOrBlank()) { "issuerAccount is required" }
                require(!credentialType.isNullOrBlank()) { "credentialType is required" }

                val holderAccount = seed.deriveKeyPair().publicKey().deriveAddress().value()
                if (!expectedHolderAccount.isNullOrBlank()) {
                    require(holderAccount == expectedHolderAccount) {
                        "holder account mismatch: seed=$holderAccount, vc=$expectedHolderAccount"
                    }
                }

                val result = xrplHelper.submitCredentialAccept(
                    seed = seed,
                    issuerAddress = issuerAccount,
                    credentialTypeHex = credentialType
                )
                val txHash = result.transactionResult().hash().value()

                savedCredential?.let {
                    db.credentialDao().updateCredential(
                        it.copy(
                            acceptedAt = nowUtcIso(),
                            credentialAcceptHash = txHash
                        )
                    )
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "XRPL submitted: ${result.engineResult()}", Toast.LENGTH_LONG).show()
                    emitCallback("SUBMIT_TO_XRPL", true) {
                        put("credentialId", savedCredential?.credentialId.orEmpty())
                        put("holderAccount", holderAccount)
                        put("issuerAccount", issuerAccount)
                        put("credentialType", credentialType)
                        put("txHash", txHash)
                        put("engineResult", result.engineResult())
                        put("engineResultMessage", result.engineResultMessage())
                        put("accepted", result.accepted())
                        put("applied", result.applied())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "submitToXRPL failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "XRPL submission failed: ${e.message}", Toast.LENGTH_LONG).show()
                    emitCallback("SUBMIT_TO_XRPL", false) {
                        put("error", e.message ?: "Unknown error")
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun signMessage(jsonPayload: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val request = Json.parseToJsonElement(jsonPayload).jsonObject
                val challenge = request.text("challenge") ?: request.text("message")
                val domain = request.text("domain") ?: "kyvc.local"
                require(!challenge.isNullOrBlank()) { "challenge or message is required" }

                val walletState = walletStateStore.requireWalletState()
                val seed = walletStateStore.requireSeed()
                currentSeed = seed
                val vcJson = resolveVcJson(request)
                val vcObject = Json.parseToJsonElement(vcJson).jsonObject

                val vpWithoutProof = buildJsonObject {
                    put("@context", JsonArray(listOf(JsonPrimitive("https://www.w3.org/ns/credentials/v2"))))
                    put("type", JsonArray(listOf(JsonPrimitive("VerifiablePresentation"))))
                    put("holder", walletState.did)
                    put("verifiableCredential", JsonArray(listOf(vcObject)))
                }
                val proofWithoutValue = buildJsonObject {
                    put("type", "DataIntegrityProof")
                    put("cryptosuite", "ecdsa-secp256k1-jcs-poc-2026")
                    put("created", nowUtcIso())
                    put("verificationMethod", "${walletState.did}#holder-key-1")
                    put("proofPurpose", "authentication")
                    put("challenge", challenge)
                    put("domain", domain)
                }
                val proofValue = vpSigner.signVp(
                    privateKey = seed.deriveKeyPair().privateKey(),
                    vpWithoutProof = vpWithoutProof,
                    proofWithoutValue = proofWithoutValue
                )
                val proof = buildJsonObject {
                    proofWithoutValue.forEach { (key, value) -> put(key, value) }
                    put("proofValue", proofValue)
                }
                val presentation = buildJsonObject {
                    vpWithoutProof.forEach { (key, value) -> put(key, value) }
                    put("proof", proof)
                }
                val didDocument = buildHolderDidDocument(walletState)

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "VP signed", Toast.LENGTH_SHORT).show()
                    emitCallback("SIGN_MESSAGE", true) {
                        put("holder", walletState.did)
                        put("challenge", challenge)
                        put("domain", domain)
                        put("proofValue", proofValue)
                        put("presentation", presentation)
                        put("didDocument", Json.parseToJsonElement(didDocument))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "signMessage failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to sign VP: ${e.message}", Toast.LENGTH_LONG).show()
                    emitCallback("SIGN_MESSAGE", false) {
                        put("error", e.message ?: "Unknown error")
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun scanQRCode(jsonPayload: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val request = parseJsonObjectOrEmpty(jsonPayload)
                val requestId = request.text("requestId") ?: "qr-${System.currentTimeMillis()}"
                val purpose = request.text("purpose") ?: "unknown"
                val endpoint = request.text("endpoint") ?: request.text("callbackUrl")
                val expiresInSec = request.text("expiresInSec") ?: request.text("ttl")
                val qrData = request.text("qrData") ?: request.text("data") ?: request.text("text")

                withContext(Dispatchers.Main) {
                    emitCallback("SCAN_QR_CODE", true) {
                        put("mode", "request_received")
                        put("requestId", requestId)
                        put("purpose", purpose)
                        endpoint?.let { put("endpoint", it) }
                        expiresInSec?.let { put("expiresInSec", it) }
                        qrData?.let { put("qrData", it) }
                    }
                    launchQrScanner(jsonPayload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "scanQRCode failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "QR scan failed: ${e.message}", Toast.LENGTH_LONG).show()
                    emitCallback("SCAN_QR_CODE", false) {
                        put("error", e.message ?: "Unknown error")
                    }
                }
            }
        }
    }

    fun onQrScanResult(requestJson: String?, qrData: String?, errorMessage: String?) {
        scope.launch(Dispatchers.Main) {
            try {
                if (!errorMessage.isNullOrBlank()) {
                    emitCallback("SCAN_QR_CODE", false) {
                        put("error", errorMessage)
                        requestJson?.let { put("request", it) }
                    }
                    return@launch
                }

                val request = parseJsonObjectOrEmpty(requestJson ?: "{}")
                val requestId = request.text("requestId") ?: "qr-${System.currentTimeMillis()}"
                val purpose = request.text("purpose") ?: "unknown"
                val endpoint = request.text("endpoint") ?: request.text("callbackUrl")
                val expiresInSec = request.text("expiresInSec") ?: request.text("ttl")

                emitCallback("SCAN_QR_CODE", true) {
                    put("mode", "scanned")
                    put("requestId", requestId)
                    put("purpose", purpose)
                    qrData?.let { put("qrData", it) }
                    endpoint?.let { put("endpoint", it) }
                    expiresInSec?.let { put("expiresInSec", it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onQrScanResult failed", e)
                emitCallback("SCAN_QR_CODE", false) {
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    private fun JsonObject.text(name: String): String? {
        return this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.obj(name: String): JsonObject? {
        return this[name] as? JsonObject
    }

    private fun parseJsonObjectOrEmpty(jsonPayload: String): JsonObject {
        if (jsonPayload.isBlank()) {
            return buildJsonObject { }
        }
        return runCatching { Json.parseToJsonElement(jsonPayload).jsonObject }.getOrElse {
            buildJsonObject { }
        }
    }

    private fun accountFromDid(did: String): String {
        val prefix = "did:xrpl:1:"
        return if (did.startsWith(prefix)) did.removePrefix(prefix) else ""
    }

    private suspend fun resolveVcJson(request: JsonObject): String {
        request.text("vcJson")?.let { return it }
        request.obj("credential")?.let { return it.toString() }
        request.text("credentialId")
            ?.takeIf { it.isNotBlank() }
            ?.let { credentialId ->
                db.credentialDao().getCredentialById(credentialId)?.vcJson?.let { return it }
            }
        throw IllegalArgumentException("vcJson, credential, or credentialId is required")
    }

    private fun nowUtcIso(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }

    private fun emitCallback(
        action: String,
        ok: Boolean,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit
    ) {
        val result = buildJsonObject {
            put("action", action)
            put("ok", ok)
            put("source", "Android")
            extra()
        }.toString()
        webViewRef?.get()?.evaluateJavascript(
            "window.onAndroidResult && window.onAndroidResult(${JSONObject.quote(result)})",
            null
        )
    }

    private fun emitWalletCallback(
        action: String,
        ok: Boolean,
        walletState: WalletStateStore.WalletState
    ) {
        emitCallback(action, ok) {
            put("account", walletState.account)
            put("publicKey", walletState.publicKey)
            put("did", walletState.did)
            put("didDocument", buildHolderDidDocument(walletState))
        }
    }

    private fun buildHolderDidDocument(walletState: WalletStateStore.WalletState): String {
        val publicKeyJwk = publicKeyJwk(walletState.publicKey)
        val keyId = "${walletState.did}#holder-key-1"
        return buildJsonObject {
            put(
                "@context",
                JsonArray(
                    listOf(
                        JsonPrimitive("https://www.w3.org/ns/did/v1"),
                        JsonPrimitive("https://w3id.org/security/jwk/v1")
                    )
                )
            )
            put("id", walletState.did)
            put(
                "verificationMethod",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("id", keyId)
                            put("type", "JsonWebKey")
                            put("controller", walletState.did)
                            put("publicKeyJwk", publicKeyJwk)
                        }
                    )
                )
            )
            put("authentication", JsonArray(listOf(JsonPrimitive(keyId))))
        }.toString()
    }

    private fun publicKeyJwk(publicKeyHex: String): JsonObject {
        val curve = ECNamedCurveTable.getParameterSpec("secp256k1").curve
        val point = curve.decodePoint(publicKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val normalized = point.normalize()
        return buildJsonObject {
            put("kty", "EC")
            put("crv", "secp256k1")
            put("x", base64UrlNoPadding(normalized.affineXCoord.encoded))
            put("y", base64UrlNoPadding(normalized.affineYCoord.encoded))
        }
    }

    private fun base64UrlNoPadding(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private companion object {
        private const val TAG = "WalletBridge"
    }
}
