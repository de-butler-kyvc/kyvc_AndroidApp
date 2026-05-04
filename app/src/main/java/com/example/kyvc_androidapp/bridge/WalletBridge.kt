package com.example.kyvc_androidapp.bridge

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import com.example.kyvc_androidapp.data.local.entity.CredentialEntity
import com.example.kyvc_androidapp.data.repository.CredentialRepository
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.erdtman.jcs.JsonCanonicalizer
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.spec.ECPublicKeySpec
import org.bouncycastle.jce.spec.ECNamedCurveSpec
import org.xrpl.xrpl4j.crypto.keys.Base58EncodedSecret
import org.xrpl.xrpl4j.crypto.keys.Seed
import org.xrpl.xrpl4j.model.transactions.Address
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.lang.ref.WeakReference
import java.math.BigInteger
import java.security.KeyFactory
import java.time.Instant
import java.time.format.DateTimeParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.security.MessageDigest
import java.security.Signature
import android.util.Base64
import java.util.concurrent.TimeUnit

class WalletBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val walletStateStore: WalletStateStore,
    private val xrplHelper: XrplClientHelper,
    private val credentialRepository: CredentialRepository,
    private val launchQrScanner: (String) -> Unit
) {
    private val verifierPrefs: SharedPreferences = context.getSharedPreferences("kyvc-verifier", Context.MODE_PRIVATE)
    private var currentSeed: Seed? = null
    private var webViewRef: WeakReference<WebView>? = null
    private val vpSigner = VpSigner()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()

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
                val envelope = parseCredentialEnvelope(jsonPayload)
                val json = envelope.payload
                validateCredentialJwt(envelope, issuerDidDocument = null)
                validateCredentialAgainstWallet(json, walletStateStore.getWalletStateOrNull())
                val issuerProof = verifyIssuerProof(json)
                require(!issuerProof.supported || issuerProof.verified) {
                    issuerProof.error ?: "issuer proof verification failed"
                }
                val status = json.obj("credentialStatus")
                val subject = json.obj("credentialSubject")
                val issuerDid = json.text("issuerDid") ?: json.text("issuer").orEmpty()
                val holderDid = json.text("holderDid") ?: subject?.text("id").orEmpty()
                val entity = CredentialEntity(
                    credentialId = json.text("credentialId") ?: json.text("id").orEmpty(),
                    vcJson = envelope.rawCredential,
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
                credentialRepository.insertCredential(entity)
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "VC Saved Successfully", Toast.LENGTH_SHORT).show()
                    emitCallback("SAVE_VC", true) {
                        put("credentialId", entity.credentialId)
                        envelope.vcJwt?.let { put("vcJwt", it) }
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
    fun checkCredentialStatus(jsonPayload: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val request = Json.parseToJsonElement(jsonPayload).jsonObject
                val savedCredential = request.text("credentialId")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { credentialRepository.getCredentialById(it) }
                val vcJson = resolveVcJson(request)
                val vc = Json.parseToJsonElement(vcJson).jsonObject
                validateCredentialAgainstWallet(vc, walletStateStore.getWalletStateOrNull())
                val status = vc.obj("credentialStatus")
                    ?: throw IllegalArgumentException("credentialStatus is required")

                val issuerAccount = request.text("issuerAccount")
                    ?: status.text("issuer")
                    ?: savedCredential?.issuerAccount
                    ?: accountFromDid(request.text("issuerDid").orEmpty())
                val holderAccount = request.text("holderAccount")
                    ?: status.text("subject")
                    ?: savedCredential?.holderAccount
                    ?: walletStateStore.getWalletStateOrNull()?.account
                    ?: throw IllegalArgumentException("holderAccount is required")
                val credentialType = request.text("credentialType")
                    ?: status.text("credentialType")
                    ?: savedCredential?.credentialType
                    ?: throw IllegalArgumentException("credentialType is required")

                val result = xrplHelper.getCredentialStatus(
                    issuerAddress = issuerAccount,
                    holderAddress = holderAccount,
                    credentialTypeHex = credentialType
                )

                if (savedCredential != null) {
                    val nextCredential = when {
                        result.active -> savedCredential.copy(revokedOrInactiveAt = null)
                        result.found && !result.active && savedCredential.acceptedAt != null ->
                            savedCredential.copy(revokedOrInactiveAt = result.checkedAtUtc)
                        else -> savedCredential
                    }
                    if (nextCredential != savedCredential) {
                        credentialRepository.updateCredential(nextCredential)
                    }
                }

                withContext(Dispatchers.Main) {
                    emitCallback("CHECK_CREDENTIAL_STATUS", true) {
                        put("credentialId", savedCredential?.credentialId.orEmpty())
                        put("credentialIndex", result.credentialIndex)
                        put("found", result.found)
                        put("active", result.active)
                        put("accepted", result.accepted)
                        put("issuerAccount", result.issuerAccount)
                        put("holderAccount", result.holderAccount)
                        put("credentialType", result.credentialType)
                        put("checkedAtUtc", result.checkedAtUtc)
                        result.flags?.let { put("flags", it) }
                        result.expiration?.let { put("expiration", it) }
                        result.error?.let { put("error", it) }
                    }
                    Toast.makeText(
                        context,
                        if (result.active) "Credential active" else "Credential inactive",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkCredentialStatus failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Credential status check failed: ${e.message}", Toast.LENGTH_LONG).show()
                    emitCallback("CHECK_CREDENTIAL_STATUS", false) {
                        put("error", e.message ?: "Unknown error")
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun verifyVC(jsonPayload: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val request = Json.parseToJsonElement(jsonPayload).jsonObject
                val savedCredential = request.text("credentialId")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { credentialRepository.getCredentialById(it) }
                val vcJson = resolveVcJson(request)
                val vc = Json.parseToJsonElement(vcJson).jsonObject
                val walletState = walletStateStore.getWalletStateOrNull()
                validateCredentialAgainstWallet(vc, walletState)

                val status = vc.obj("credentialStatus")
                    ?: throw IllegalArgumentException("credentialStatus is required")
                val proof = vc.obj("proof")
                val issuerAccount = status.text("issuer")
                    ?: accountFromDid(vc.text("issuer").orEmpty())
                val holderAccount = status.text("subject")
                    ?: walletState?.account
                    ?: throw IllegalArgumentException("holderAccount is required")
                val credentialType = status.text("credentialType")
                    ?: throw IllegalArgumentException("credentialStatus.credentialType is required")
                val canonicalHash = computeVcCoreHash(vc)
                val declaredHash = status.text("vcCoreHash") ?: savedCredential?.vcCoreHash
                val activeStatus = xrplHelper.getCredentialStatus(
                    issuerAddress = issuerAccount,
                    holderAddress = holderAccount,
                    credentialTypeHex = credentialType
                )
                val issuerProof = verifyIssuerProof(vc)

                val issues = mutableListOf<String>()
                if (declaredHash.isNullOrBlank()) {
                    issues += "vcCoreHash is missing"
                } else if (!declaredHash.equals(canonicalHash, ignoreCase = true)) {
                    issues += "vcCoreHash mismatch"
                }
                if (proof == null) {
                    issues += "proof is missing"
                } else {
                    val proofType = proof.text("type")
                    if (proofType.isNullOrBlank()) {
                        issues += "proof.type is missing"
                    }
                    if (proof.text("proofValue").isNullOrBlank() && proof.text("signature").isNullOrBlank()) {
                        issues += "proof value is missing"
                    }
                    val proofPurpose = proof.text("proofPurpose")
                    if (proofPurpose.isNullOrBlank()) {
                        issues += "proof.proofPurpose is missing"
                    }
                }
                if (issuerProof.supported && !issuerProof.verified) {
                    issues += issuerProof.error ?: "issuer proof verification failed"
                }
                if (!activeStatus.active) {
                    issues += activeStatus.error ?: "XRPL Credential status is not active"
                }

                val ok = issues.isEmpty()
                withContext(Dispatchers.Main) {
                    emitCallback("VERIFY_VC", ok) {
                        put("credentialId", savedCredential?.credentialId.orEmpty())
                        put("issuerAccount", issuerAccount)
                        put("holderAccount", holderAccount)
                        put("credentialType", credentialType)
                        put("canonicalHash", canonicalHash)
                        declaredHash?.let { put("declaredHash", it) }
                        put("proofPresent", proof != null)
                        put("statusActive", activeStatus.active)
                        put("statusFound", activeStatus.found)
                        put("statusAccepted", activeStatus.accepted)
                        put("checkedAtUtc", activeStatus.checkedAtUtc)
                        put("issuerProofSupported", issuerProof.supported)
                        put("issuerProofVerified", issuerProof.verified)
                        issuerProof.status?.let { put("issuerProofStatus", it) }
                        if (issues.isNotEmpty()) {
                            put("issues", JsonArray(issues.map { JsonPrimitive(it) }))
                        }
                        activeStatus.error?.let { put("statusError", it) }
                    }
                    Toast.makeText(
                        context,
                        if (ok) "VC verified" else "VC verification failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "verifyVC failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "VC verification failed: ${e.message}", Toast.LENGTH_LONG).show()
                    emitCallback("VERIFY_VC", false) {
                        put("error", e.message ?: "Unknown error")
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun listCredentials(jsonPayload: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val credentials = credentialRepository.getAllCredentialsOnce()
                withContext(Dispatchers.Main) {
                    emitCallback("LIST_CREDENTIALS", true) {
                        put(
                            "credentials",
                            JsonArray(
                                credentials.map { credential ->
                                    buildJsonObject {
                                        put("credentialId", credential.credentialId)
                                        put("issuerDid", credential.issuerDid)
                                        put("issuerAccount", credential.issuerAccount)
                                        put("holderDid", credential.holderDid)
                                        put("holderAccount", credential.holderAccount)
                                        put("credentialType", credential.credentialType)
                                        put("vcCoreHash", credential.vcCoreHash)
                                        put("validFrom", credential.validFrom)
                                        put("validUntil", credential.validUntil)
                                        put("acceptedAt", credential.acceptedAt ?: "")
                                        put("credentialAcceptHash", credential.credentialAcceptHash ?: "")
                                        put("revokedOrInactiveAt", credential.revokedOrInactiveAt ?: "")
                                        put("vcJson", credential.vcJson)
                                    }
                                }
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "listCredentials failed", e)
                withContext(Dispatchers.Main) {
                    emitCallback("LIST_CREDENTIALS", false) {
                        put("error", e.message ?: "Unknown error")
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun refreshAllCredentialStatuses(jsonPayload: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val credentials = credentialRepository.getAllCredentialsOnce()
                val results = credentials.map { credential ->
                    val status = xrplHelper.getCredentialStatus(
                        issuerAddress = credential.issuerAccount,
                        holderAddress = credential.holderAccount,
                        credentialTypeHex = credential.credentialType
                    )
                    val updated = when {
                        status.active -> credential.copy(revokedOrInactiveAt = null)
                        status.found && !status.active && credential.acceptedAt != null ->
                            credential.copy(revokedOrInactiveAt = status.checkedAtUtc)
                        else -> credential
                    }
                    if (updated != credential) {
                        credentialRepository.updateCredential(updated)
                    }
                    buildJsonObject {
                        put("credentialId", credential.credentialId)
                        put("active", status.active)
                        put("found", status.found)
                        put("accepted", status.accepted)
                        put("checkedAtUtc", status.checkedAtUtc)
                        status.error?.let { put("error", it) }
                    }
                }
                withContext(Dispatchers.Main) {
                    emitCallback("REFRESH_CREDENTIAL_STATUSES", true) {
                        put("results", JsonArray(results))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "refreshAllCredentialStatuses failed", e)
                withContext(Dispatchers.Main) {
                    emitCallback("REFRESH_CREDENTIAL_STATUSES", false) {
                        put("error", e.message ?: "Unknown error")
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun registerVerifierChallenge(jsonPayload: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val request = parseJsonObjectOrEmpty(jsonPayload)
                val challenge = request.text("challenge")
                    ?: throw IllegalArgumentException("challenge is required")
                val domain = request.text("domain") ?: "kyvc.local"
                val issuedAt = request.text("issuedAt") ?: nowUtcIso()
                val expiresAt = request.text("expiresAt")
                    ?: request.text("expiry")
                    ?: request.text("expiresAtUtc")
                    ?: request.text("ttl")?.toLongOrNull()?.let { ttlSec ->
                        Instant.now().plusSeconds(ttlSec).toString()
                    }
                    ?: Instant.now().plusSeconds(300).toString()

                storeVerifierChallenge(
                    challenge = challenge,
                    domain = domain,
                    issuedAt = issuedAt,
                    expiresAt = expiresAt,
                    used = false
                )
                withContext(Dispatchers.Main) {
                    emitCallback("REGISTER_VERIFIER_CHALLENGE", true) {
                        put("challenge", challenge)
                        put("domain", domain)
                        put("issuedAt", issuedAt)
                        put("expiresAt", expiresAt)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "registerVerifierChallenge failed", e)
                withContext(Dispatchers.Main) {
                    emitCallback("REGISTER_VERIFIER_CHALLENGE", false) {
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
                    ?.let { credentialRepository.getCredentialById(it) }
                val vcJson = resolveVcJson(request)
                val vcObject = Json.parseToJsonElement(vcJson).jsonObject
                validateCredentialAgainstWallet(vcObject, walletStateStore.getWalletStateOrNull())
                val vcStatus = request.obj("credentialStatus")
                    ?: request.obj("credential")?.obj("credentialStatus")
                    ?: vcObject.obj("credentialStatus")

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
                val engineResult = result.engineResult()
                val ledgerApplied = engineResult == "tesSUCCESS"
                val alreadyExists = engineResult == "tecDUPLICATE"

                if (ledgerApplied || alreadyExists) savedCredential?.let {
                    credentialRepository.updateCredential(
                        it.copy(
                            acceptedAt = nowUtcIso(),
                            credentialAcceptHash = txHash
                        )
                    )
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "XRPL submitted: $engineResult", Toast.LENGTH_LONG).show()
                    emitCallback("SUBMIT_TO_XRPL", ledgerApplied || alreadyExists) {
                        put("credentialId", savedCredential?.credentialId.orEmpty())
                        put("holderAccount", holderAccount)
                        put("issuerAccount", issuerAccount)
                        put("credentialType", credentialType)
                        put("txHash", txHash)
                        put("engineResult", engineResult)
                        put("engineResultMessage", result.engineResultMessage())
                        put("accepted", result.accepted())
                        put("applied", result.applied())
                        put("ledgerApplied", ledgerApplied)
                        put("alreadyExists", alreadyExists)
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
    fun submitCredentialCreate(jsonPayload: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val request = Json.parseToJsonElement(jsonPayload).jsonObject
                val issuerSeedValue = request.text("issuerSeed")
                    ?: request.text("seed")
                    ?: throw IllegalArgumentException("issuerSeed is required")
                val seed = parseSeed(issuerSeedValue)
                val issuerAccount = seed.deriveKeyPair().publicKey().deriveAddress().value()

                val vcJson = request.text("vcJson")
                val vcObject = vcJson?.let { Json.parseToJsonElement(it).jsonObject }
                val subjectAccount = request.text("subjectAccount")
                    ?: request.text("holderAccount")
                    ?: vcObject?.obj("credentialStatus")?.text("subject")
                    ?: accountFromDid(vcObject?.obj("credentialSubject")?.text("id").orEmpty())
                    ?: walletStateStore.getWalletStateOrNull()?.account
                    ?: throw IllegalArgumentException("subjectAccount is required")
                val credentialType = request.text("credentialType")
                    ?: vcObject?.obj("credentialStatus")?.text("credentialType")
                    ?: throw IllegalArgumentException("credentialType is required")
                val expirationDays = request.text("expirationDays")?.toLongOrNull()
                    ?: request.text("expiresInDays")?.toLongOrNull()
                val credentialUri = request.text("credentialUri")
                    ?: request.text("uri")

                val expectedIssuerAccount = request.text("issuerAccount")
                    ?: request.text("issuer")
                    ?: vcObject?.obj("credentialStatus")?.text("issuer")
                if (!expectedIssuerAccount.isNullOrBlank()) {
                    require(expectedIssuerAccount == issuerAccount) {
                        "issuer account mismatch: seed=$issuerAccount, expected=$expectedIssuerAccount"
                    }
                }

                val result = xrplHelper.submitCredentialCreate(
                    seed = seed,
                    subjectAddress = subjectAccount,
                    credentialTypeHex = credentialType,
                    expirationDays = expirationDays,
                    credentialUri = credentialUri
                )
                val txHash = result.transactionResult().hash().value()
                val engineResult = result.engineResult()
                val ledgerApplied = engineResult == "tesSUCCESS"
                val alreadyExists = engineResult == "tecDUPLICATE"
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "CredentialCreate submitted: $engineResult", Toast.LENGTH_LONG).show()
                    emitCallback("SUBMIT_CREDENTIAL_CREATE", ledgerApplied || alreadyExists) {
                        put("issuerAccount", issuerAccount)
                        put("subjectAccount", subjectAccount)
                        put("credentialType", credentialType)
                        put("txHash", txHash)
                        put("engineResult", engineResult)
                        put("engineResultMessage", result.engineResultMessage())
                        put("accepted", result.accepted())
                        put("applied", result.applied())
                        put("ledgerApplied", ledgerApplied)
                        put("alreadyExists", alreadyExists)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "submitCredentialCreate failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "CredentialCreate failed: ${e.message}", Toast.LENGTH_LONG).show()
                    emitCallback("SUBMIT_CREDENTIAL_CREATE", false) {
                        put("error", e.message ?: "Unknown error")
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun requestIssuerCredential(jsonPayload: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val request = parseJsonObjectOrEmpty(jsonPayload)
                val baseUrl = request.text("coreBaseUrl")
                    ?: request.text("issuerBaseUrl")
                    ?: "https://dev-core-kyvc.khuoo.synology.me"
                val holderState = walletStateStore.requireWalletState()
                val holderAccount = request.text("holderAccount") ?: holderState.account
                val holderDid = request.text("holderDid") ?: holderState.did
                val claims = request.obj("claims") ?: buildJsonObject {
                    request.text("kycLevel")?.let { put("kycLevel", it) }
                    request.text("jurisdiction")?.let { put("jurisdiction", it) }
                    request.text("corporateName")?.let { put("corporateName", it) }
                    request.text("businessNumber")?.let { put("businessNumber", it) }
                    request.text("representativeName")?.let { put("representativeName", it) }
                    request.text("beneficialOwner")?.let { put("beneficialOwner", it) }
                }
                val validFrom = request.text("validFrom") ?: nowUtcIso()
                val validUntil = request.text("validUntil")
                    ?: request.text("expirationDate")
                    ?: Instant.now().plusSeconds(30L * 24L * 60L * 60L).toString()
                val payload = buildJsonObject {
                    put("holder_account", holderAccount)
                    put("holder_did", holderDid)
                    put("claims", claims)
                    put("valid_from", validFrom)
                    put("valid_until", validUntil)
                }.toString()

                val endpoint = resolveBackendEndpoint(baseUrl, request.text("endpoint"), "/issuer/credentials/kyc")
                val responseBody = postJson(endpoint, payload, "Issuer request")
                val response = Json.parseToJsonElement(responseBody).jsonObject
                val credentialJwt = response.text("credential")
                    ?: response.text("vc_jwt")
                    ?: response.text("vcJwt")
                val credential = credentialJwt?.let { decodeJwtPayload(it) }
                    ?: response.obj("credential")
                    ?: response.obj("credential_json")
                    ?: response.obj("vc")
                    ?: response.obj("data")
                    ?: response
                credentialJwt?.let {
                    val issuerDid = credential.text("issuer")
                    val issuerAccount = accountFromDid(issuerDid.orEmpty())
                        ?: credential.obj("credentialStatus")?.text("issuer")
                    val issuerDidDocument = fetchJsonOrNull(
                        resolveBackendEndpoint(baseUrl, null, "/dids/$issuerAccount/diddoc.json")
                    )
                    validateCredentialJwt(
                        CredentialEnvelope(rawCredential = it, vcJwt = it, payload = credential),
                        issuerDidDocument = issuerDidDocument
                    )
                }
                val vcJson = credential.toString()
                val credentialId = credential.text("id") ?: request.text("credentialId").orEmpty()
                val issuerAccount = credential.obj("credentialStatus")?.text("issuer")
                    ?: accountFromDid(credential.text("issuer").orEmpty())
                    ?: request.text("issuerAccount")
                    ?: ""
                val credentialType = credential.obj("credentialStatus")?.text("credentialType")
                    ?: response.text("credential_type")
                    ?: request.text("credentialType")
                    ?: ""
                val vcCoreHash = credential.obj("credentialStatus")?.text("vcCoreHash")
                    ?: response.text("vc_core_hash")
                    ?: ""

                withContext(Dispatchers.Main) {
                    emitCallback("ISSUER_CREDENTIAL_RECEIVED", true) {
                        put("coreBaseUrl", baseUrl)
                        put("endpoint", endpoint)
                        put("credentialId", credentialId)
                        put("issuerAccount", issuerAccount)
                        put("holderAccount", holderAccount)
                        put("holderDid", holderDid)
                        put("credentialType", credentialType)
                        put("vcCoreHash", vcCoreHash)
                        put("vcJson", vcJson)
                        credentialJwt?.let { put("vcJwt", it) }
                        response.text("status_mode")?.let { put("statusMode", it) }
                        response.text("txHash")?.let { put("txHash", it) }
                        response.text("credential_create_hash")?.let { put("credentialCreateHash", it) }
                        response["credential_create_transaction"]?.let { put("credentialCreateTransaction", it) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "requestIssuerCredential failed", e)
                val issuerFailure = classifyIssuerRequestFailure(e.message)
                withContext(Dispatchers.Main) {
                    emitCallback("ISSUER_CREDENTIAL_RECEIVED", false) {
                        put("error", e.message ?: "Unknown error")
                        put("errorCode", issuerFailure.code)
                        put("errorTitle", issuerFailure.title)
                        put("errorHint", issuerFailure.hint)
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun requestVerifierChallenge(jsonPayload: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val request = parseJsonObjectOrEmpty(jsonPayload)
                val baseUrl = request.text("coreBaseUrl")
                    ?: request.text("verifierBaseUrl")
                    ?: "https://dev-core-kyvc.khuoo.synology.me"
                val domain = request.text("domain") ?: "kyvc.local"
                val payload = buildJsonObject { put("domain", domain) }.toString()
                val endpoint = resolveBackendEndpoint(baseUrl, request.text("endpoint"), "/verifier/presentations/challenges")
                val responseBody = postJson(endpoint, payload, "Verifier challenge request")
                val response = Json.parseToJsonElement(responseBody).jsonObject
                val challenge = response.text("challenge") ?: throw IllegalStateException("challenge missing")
                val expiresAt = response.text("expires_at") ?: response.text("expiresAt")
                    ?: Instant.now().plusSeconds(300).toString()
                val issuedAt = nowUtcIso()

                storeVerifierChallenge(
                    challenge = challenge,
                    domain = domain,
                    issuedAt = issuedAt,
                    expiresAt = expiresAt,
                    used = false
                )
                withContext(Dispatchers.Main) {
                    emitCallback("REQUEST_VERIFIER_CHALLENGE", true) {
                        put("challenge", challenge)
                        put("domain", domain)
                        put("issuedAt", issuedAt)
                        put("expiresAt", expiresAt)
                        put("endpoint", endpoint)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "requestVerifierChallenge failed", e)
                withContext(Dispatchers.Main) {
                    emitCallback("REQUEST_VERIFIER_CHALLENGE", false) {
                        put("error", e.message ?: "Unknown error")
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun verifyCredentialWithServer(jsonPayload: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val request = parseJsonObjectOrEmpty(jsonPayload)
                val baseUrl = request.text("coreBaseUrl")
                    ?: request.text("verifierBaseUrl")
                    ?: "https://dev-core-kyvc.khuoo.synology.me"
                val walletState = walletStateStore.requireWalletState()
                val vcJson = resolveVcJson(request)
                val vcJwt = resolveVcJwt(request)
                val credential = Json.parseToJsonElement(vcJson).jsonObject
                val requireStatus = request["require_status"]?.jsonPrimitive?.booleanOrNull ?: true
                val statusMode = request.text("status_mode") ?: "xrpl"
                val didDocuments = buildJsonObject {
                    put(walletState.did, Json.parseToJsonElement(buildHolderDidDocument(walletState)))
                    val issuerDid = credential.text("issuer")
                    val issuerAccount = accountFromDid(issuerDid.orEmpty())
                        ?: credential.obj("credentialStatus")?.text("issuer")
                    putDidDocumentFromCore(baseUrl, issuerDid, issuerAccount)

                    val proofVerificationMethod = credential.obj("proof")?.text("verificationMethod")
                    val proofDid = proofVerificationMethod?.substringBefore("#")
                    val proofAccount = accountFromDid(proofDid.orEmpty())
                    putDidDocumentFromCore(baseUrl, proofDid, proofAccount)

                    request.obj("issuerDidDocument")?.let { issuerDidDocument ->
                        val suppliedIssuerDid = issuerDidDocument.text("id") ?: credential.text("issuer")
                        if (!suppliedIssuerDid.isNullOrBlank()) {
                            put(suppliedIssuerDid, issuerDidDocument)
                        }
                    }
                }
                val requestBody = buildJsonObject {
                    if (vcJwt != null) {
                        put("credential", vcJwt)
                    } else {
                        put("credential", credential)
                    }
                    put("did_documents", didDocuments)
                    request.obj("policy")?.let { put("policy", it) }
                    put("require_status", requireStatus)
                    put("status_mode", statusMode)
                }
                val endpoint = resolveBackendEndpoint(baseUrl, request.text("endpoint"), "/verifier/credentials/verify")
                val responseBody = postJson(endpoint, requestBody.toString(), "Verifier credential request")
                val response = Json.parseToJsonElement(responseBody).jsonObject
                val ok = response["ok"]?.jsonPrimitive?.booleanOrNull == true
                val failureInfo = if (ok) null else classifyVerifierFailure(
                    runCatching { JSONObject(responseBody) }.getOrNull(),
                    null
                )

                withContext(Dispatchers.Main) {
                    emitCallback("VERIFY_CREDENTIAL_WITH_SERVER", ok) {
                        put("endpoint", endpoint)
                        put("require_status", requireStatus)
                        put("status_mode", statusMode)
                        put("response", response)
                        failureInfo?.let { info ->
                            put("errorCode", info.code)
                            put("errorTitle", info.title)
                            put("errorHint", info.hint)
                            put("error", info.title)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "verifyCredentialWithServer failed", e)
                val failureInfo = classifyVerifierFailure(null, e.message)
                withContext(Dispatchers.Main) {
                    emitCallback("VERIFY_CREDENTIAL_WITH_SERVER", false) {
                        put("error", e.message ?: "Unknown error")
                        put("errorCode", failureInfo.code)
                        put("errorTitle", failureInfo.title)
                        put("errorHint", failureInfo.hint)
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
                val authPrivateKey = walletStateStore.requireAuthPrivateKeyBytes()
                currentSeed = seed
                val vcJson = resolveVcJson(request)
                val vcObject = Json.parseToJsonElement(vcJson).jsonObject
                val vcJwt = resolveVcJwt(request)
                validateCredentialAgainstWallet(vcObject, walletState)
                ensureVerifierChallengeUsable(challenge, domain)

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
                    privateKeyScalar = authPrivateKey,
                    vpWithoutProof = vpWithoutProof,
                    proofWithoutValue = proofWithoutValue
                )
                val vpJwt = vcJwt?.let {
                    val envelopedVc = buildJsonObject {
                        put("@context", "https://www.w3.org/ns/credentials/v2")
                        put("id", "data:application/vc+jwt,$it")
                        put("type", "EnvelopedVerifiableCredential")
                    }
                    val vpPayload = buildJsonObject {
                        put("@context", JsonArray(listOf(JsonPrimitive("https://www.w3.org/ns/credentials/v2"))))
                        put("type", JsonArray(listOf(JsonPrimitive("VerifiablePresentation"))))
                        put("holder", walletState.did)
                        put("verifiableCredential", JsonArray(listOf(envelopedVc)))
                    }
                    val protectedHeader = buildJsonObject {
                        put("alg", "ES256K")
                        put("typ", "vp+jwt")
                        put("cty", "vp")
                        put("kid", "${walletState.did}#holder-key-1")
                        put("challenge", challenge)
                        put("domain", domain)
                    }
                    vpSigner.signCompactJws(
                        privateKeyScalar = authPrivateKey,
                        protectedHeaderJson = protectedHeader.toString(),
                        payloadJson = vpPayload.toString()
                    )
                }
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
                        vpJwt?.let { put("presentationJwt", it) }
                        vcJwt?.let { put("vcJwt", it) }
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
    fun submitPresentationToVerifier(jsonPayload: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val request = Json.parseToJsonElement(jsonPayload).jsonObject
                val walletState = walletStateStore.requireWalletState()
                val seed = walletStateStore.requireSeed()
                currentSeed = seed

                val vcJson = resolveVcJson(request)
                val vcObject = Json.parseToJsonElement(vcJson).jsonObject
                validateCredentialAgainstWallet(vcObject, walletState)

                val presentation = request.obj("presentation")
                    ?: throw IllegalArgumentException("presentation is required")
                val presentationJwt = request.text("presentationJwt")
                val didDocument = request.obj("didDocument")
                    ?: request.obj("did_documents")?.get(walletState.did)?.jsonObject
                    ?: throw IllegalArgumentException("didDocument is required")
                val endpoint = request.text("endpoint")
                    ?: request.text("verifierEndpoint")
                    ?: request.text("url")
                    ?: throw IllegalArgumentException("endpoint is required")
                val statusMode = request.text("status_mode") ?: "xrpl"
                val requireStatus = request["require_status"]?.jsonPrimitive?.booleanOrNull ?: true
                val policy = request.obj("policy") ?: defaultVerifierPolicy(vcObject)
                val challenge = presentation.obj("proof")?.text("challenge")
                    ?: throw IllegalArgumentException("presentation.proof.challenge is required")

                ensurePresentationMatchesWallet(presentation, didDocument, walletState)
                ensurePresentationContainsCredential(presentation, vcObject)
                ensureVerifierChallengeUsable(challenge, request.text("domain"))

                if (requireStatus) {
                    val status = vcObject.obj("credentialStatus")
                        ?: throw IllegalArgumentException("credentialStatus is required")
                    val issuerAccount = status.text("issuer") ?: accountFromDid(vcObject.text("issuer").orEmpty())
                    val holderAccount = status.text("subject") ?: walletState.account
                    val credentialType = status.text("credentialType")
                        ?: throw IllegalArgumentException("credentialStatus.credentialType is required")
                    val xrplStatus = xrplHelper.getCredentialStatus(
                        issuerAddress = issuerAccount,
                        holderAddress = holderAccount,
                        credentialTypeHex = credentialType
                    )
                    require(xrplStatus.active) {
                        xrplStatus.error ?: "XRPL Credential status is not active"
                    }
                }

                val requestBody = buildJsonObject {
                    if (!presentationJwt.isNullOrBlank()) {
                        put("presentation", presentationJwt)
                    } else {
                        put("presentation", presentation)
                    }
                    put(
                        "did_documents",
                        buildJsonObject {
                            put(walletState.did, didDocument)
                        }
                    )
                    put("policy", policy)
                    put("require_status", requireStatus)
                    put("status_mode", statusMode)
                }

                val response = postJson(endpoint, requestBody.toString())
                val responseJson = runCatching { JSONObject(response) }.getOrNull()
                val ok = responseJson?.optBoolean("ok") == true
                val errors = responseJson?.optJSONArray("errors")
                val details = responseJson?.optJSONObject("details")
                val failureInfo = if (ok) null else classifyVerifierFailure(responseJson, null)
                if (ok) {
                    markVerifierChallengeUsed(challenge)
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, if (ok) "Verifier submission completed" else "Verifier submission failed", Toast.LENGTH_LONG).show()
                    emitCallback("SUBMIT_TO_VERIFIER", ok) {
                        put("endpoint", endpoint)
                        put("status_mode", statusMode)
                        put("require_status", requireStatus)
                        if (responseJson != null) {
                            put("response", Json.parseToJsonElement(responseJson.toString()))
                        } else {
                            put("responseText", response)
                        }
                        if (errors != null) {
                            put("errors", Json.parseToJsonElement(errors.toString()))
                        }
                        if (details != null) {
                            put("details", Json.parseToJsonElement(details.toString()))
                        }
                        failureInfo?.let { info ->
                            put("errorCode", info.code)
                            put("errorTitle", info.title)
                            put("errorHint", info.hint)
                            put("error", info.title)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "submitPresentationToVerifier failed", e)
                val failureInfo = classifyVerifierFailure(null, e.message)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Verifier submission failed: ${e.message}", Toast.LENGTH_LONG).show()
                    emitCallback("SUBMIT_TO_VERIFIER", false) {
                        put("error", e.message ?: "Unknown error")
                        put("errorCode", failureInfo.code)
                        put("errorTitle", failureInfo.title)
                        put("errorHint", failureInfo.hint)
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
                val qrData = request.text("qrData") ?: request.text("data") ?: request.text("text")
                val qrPayload = parseJsonObjectOrEmpty(qrData.orEmpty())
                val qrInfo = buildQrRequestInfo(request, qrPayload, qrData)
                registerQrChallengeIfPresent(qrInfo)

                withContext(Dispatchers.Main) {
                    emitCallback("SCAN_QR_CODE", true) {
                        put("mode", "request_received")
                        put("requestId", requestId)
                        putQrRequestInfo(qrInfo)
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
                val qrPayload = parseJsonObjectOrEmpty(qrData.orEmpty())
                val qrInfo = buildQrRequestInfo(request, qrPayload, qrData)
                registerQrChallengeIfPresent(qrInfo)

                emitCallback("SCAN_QR_CODE", true) {
                    put("mode", "scanned")
                    put("requestId", requestId)
                    putQrRequestInfo(qrInfo)
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
        return (this[name] as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putQrRequestInfo(info: QrRequestInfo) {
        put("actionType", info.actionType)
        put("purpose", info.actionType)
        info.endpoint?.let { put("endpoint", it) }
        info.coreBaseUrl?.let { put("coreBaseUrl", it) }
        info.challenge?.let { put("challenge", it) }
        info.domain?.let { put("domain", it) }
        info.expiresAt?.let { put("expiresAt", it) }
        info.expiresInSec?.let { put("expiresInSec", it) }
        info.qrData?.let { put("qrData", it) }
        info.rawPayload?.let { put("rawPayload", it) }
    }

    private fun buildQrRequestInfo(
        request: JsonObject,
        qrPayload: JsonObject,
        qrData: String?
    ): QrRequestInfo {
        val actionType = normalizeQrAction(
            firstQrText(request, qrPayload, "actionType", "purpose", "type", "requestType", "kind")
        )
        val expiresInSec = firstQrText(request, qrPayload, "expiresInSec", "ttl", "ttlSec", "expires_in")
        val expiresAt = firstQrText(request, qrPayload, "expiresAt", "expires_at", "expiration")
            ?: expiresInSec
                ?.toLongOrNull()
                ?.let { Instant.now().plusSeconds(it).toString() }

        return QrRequestInfo(
            actionType = actionType,
            endpoint = firstQrText(request, qrPayload, "endpoint", "callbackUrl", "callback_url", "verifierEndpoint", "verificationEndpoint"),
            coreBaseUrl = firstQrText(request, qrPayload, "coreBaseUrl", "baseUrl", "core_base_url", "issuerBaseUrl"),
            challenge = firstQrText(request, qrPayload, "challenge", "nonce"),
            domain = firstQrText(request, qrPayload, "domain", "audience") ?: "kyvc.local",
            expiresAt = expiresAt,
            expiresInSec = expiresInSec,
            qrData = qrData,
            rawPayload = if (qrPayload.isNotEmpty()) qrPayload.toString() else null
        )
    }

    private fun firstQrText(request: JsonObject, qrPayload: JsonObject, vararg names: String): String? {
        return names.firstNotNullOfOrNull { name ->
            request.text(name) ?: qrPayload.text(name)
        }
    }

    private fun normalizeQrAction(rawAction: String?): String {
        val normalized = rawAction
            ?.uppercase(Locale.US)
            ?.replace("-", "_")
            ?.replace(" ", "_")
            .orEmpty()
        return when {
            normalized in setOf("VC_ISSUE", "ISSUE_VC", "CREDENTIAL_OFFER", "CREDENTIAL_ISSUE") -> "VC_ISSUE"
            normalized.contains("VC") && normalized.contains("ISSUE") -> "VC_ISSUE"
            normalized.contains("CREDENTIAL") && normalized.contains("OFFER") -> "VC_ISSUE"
            normalized in setOf("VP_REQUEST", "PRESENTATION_REQUEST", "VERIFY_REQUEST") -> "VP_REQUEST"
            normalized.contains("PRESENTATION") && normalized.contains("REQUEST") -> "VP_REQUEST"
            normalized.contains("VP") && normalized.contains("REQUEST") -> "VP_REQUEST"
            normalized in setOf("LOGIN", "LOGIN_REQUEST", "AUTH_REQUEST") -> "LOGIN_REQUEST"
            else -> "UNKNOWN"
        }
    }

    private fun registerQrChallengeIfPresent(info: QrRequestInfo) {
        val challenge = info.challenge ?: return
        val expiresAt = info.expiresAt ?: Instant.now().plusSeconds(300).toString()
        storeVerifierChallenge(
            challenge = challenge,
            domain = info.domain ?: "kyvc.local",
            issuedAt = nowUtcIso(),
            expiresAt = expiresAt,
            used = false
        )
    }

    private fun classifyVerifierFailure(responseJson: JSONObject?, fallbackMessage: String?): VerifierFailureInfo {
        val messages = mutableListOf<String>()
        fallbackMessage?.takeIf { it.isNotBlank() }?.let(messages::add)
        responseJson?.optJSONArray("errors")?.let { errors ->
            for (index in 0 until errors.length()) {
                errors.optString(index).takeIf { it.isNotBlank() }?.let(messages::add)
            }
        }
        val details = responseJson?.optJSONObject("details")
        details?.optJSONArray("policyErrors")?.let { errors ->
            for (index in 0 until errors.length()) {
                errors.optString(index).takeIf { it.isNotBlank() }?.let(messages::add)
            }
        }
        if (details?.has("credentialAccepted") == true && !details.optBoolean("credentialAccepted")) {
            messages.add("XRPL Credential status is not active")
        }

        val joined = messages.joinToString(" ").lowercase(Locale.US)
        return when {
            "not issued" in joined && "challenge" in joined -> VerifierFailureInfo(
                "VP_CHALLENGE_NOT_ISSUED",
                "Verifier Challenge가 등록되지 않았습니다",
                "실제 Challenge 요청을 먼저 실행한 뒤, 그 challenge로 VP를 다시 생성하세요."
            )
            "already used" in joined && "challenge" in joined -> VerifierFailureInfo(
                "VP_CHALLENGE_ALREADY_USED",
                "이미 사용된 Challenge입니다",
                "Challenge는 1회용입니다. 새 Challenge를 받은 뒤 VP를 다시 생성하세요."
            )
            "expired" in joined && "challenge" in joined -> VerifierFailureInfo(
                "VP_CHALLENGE_EXPIRED",
                "Challenge가 만료됐습니다",
                "새 Challenge를 발급받아 VP를 다시 생성하세요."
            )
            "domain mismatch" in joined -> VerifierFailureInfo(
                "VP_DOMAIN_MISMATCH",
                "VP domain이 일치하지 않습니다",
                "Challenge 발급 시 받은 domain과 VP 생성 domain을 같은 값으로 맞추세요."
            )
            "vp signature" in joined || ("presentation" in joined && "signature" in joined) -> VerifierFailureInfo(
                "VP_SIGNATURE_INVALID",
                "VP 서명 검증에 실패했습니다",
                "holder 인증 키와 DID Document의 holder-key-1 공개키가 같은 키쌍인지 확인하세요."
            )
            "vc signature" in joined -> VerifierFailureInfo(
                "VC_SIGNATURE_INVALID",
                "VC 서명 검증에 실패했습니다",
                "issuer가 발급한 VC JWT 원문을 변형하지 말고 그대로 저장/제출해야 합니다."
            )
            "xrpl credential status is not active" in joined || "credentialaccepted" in joined -> VerifierFailureInfo(
                "XRPL_STATUS_INACTIVE",
                "XRPL Credential이 아직 활성 상태가 아닙니다",
                "CredentialAccept 제출 후 상태 조회에서 active: true, accepted: true가 되는지 확인하세요."
            )
            "policy" in joined -> VerifierFailureInfo(
                "POLICY_REJECTED",
                "Verifier 정책 조건을 통과하지 못했습니다",
                "trustedIssuers, kycLevel, jurisdiction 값이 VC 내용과 맞는지 확인하세요."
            )
            "did resolution failed" in joined || "did document not found" in joined -> VerifierFailureInfo(
                "DID_DOCUMENT_NOT_FOUND",
                "DID Document를 찾지 못했습니다",
                "issuer DID Document가 core에 등록되어 있는지, holder DID Document가 제출 요청에 포함됐는지 확인하세요."
            )
            "timeout" in joined || "timed out" in joined -> VerifierFailureInfo(
                "VERIFIER_REQUEST_TIMEOUT",
                "Verifier 서버 응답 시간이 초과됐습니다",
                "dev-core 서버 또는 XRPL status 조회가 지연된 상태입니다. 네트워크/VPN 연결을 확인하고 잠시 뒤 다시 시도하세요."
            )
            else -> VerifierFailureInfo(
                "VERIFIER_REJECTED",
                "Verifier가 제출을 거부했습니다",
                messages.firstOrNull() ?: "응답의 errors/details를 확인하세요."
            )
        }
    }

    private fun classifyIssuerRequestFailure(message: String?): VerifierFailureInfo {
        val text = message.orEmpty().lowercase(Locale.US)
        return when {
            "timeout" in text || "timed out" in text -> VerifierFailureInfo(
                "ISSUER_REQUEST_TIMEOUT",
                "Issuer 서버 응답 시간이 초과됐습니다",
                "dev-core 서버 또는 XRPL devnet 처리가 지연된 상태입니다. 네트워크/VPN 연결을 확인하고 잠시 뒤 다시 시도하세요. 같은 오류가 반복되면 서버 로그에서 /issuer/credentials/kyc 처리 시간을 확인해야 합니다."
            )
            "issuer private key pem file could not be read" in text ||
                "issuer_private_key_pem" in text ||
                ".local-secrets/issuer-key.pem" in text -> VerifierFailureInfo(
                    "ISSUER_KEY_NOT_CONFIGURED",
                    "Issuer 서버 키 설정이 없습니다",
                    "Android holder 앱은 issuer private key/PEM을 보내면 안 됩니다. dev-core 서버에 issuer key 파일 또는 issuer 운영 설정이 등록되어야 실제 VC 발급이 가능합니다."
                )
            "400" in text -> VerifierFailureInfo(
                "ISSUER_BAD_REQUEST",
                "Issuer 요청이 서버에서 거부됐습니다",
                "holder_account, holder_did, claims, valid_from, valid_until 값과 dev-core 서버 설정을 확인하세요."
            )
            else -> VerifierFailureInfo(
                "ISSUER_REQUEST_FAILED",
                "Issuer 요청에 실패했습니다",
                message ?: "응답 로그를 확인하세요."
            )
        }
    }

    private fun JsonObject.obj(name: String): JsonObject? {
        return this[name] as? JsonObject
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putDidDocumentFromCore(
        baseUrl: String,
        did: String?,
        account: String?
    ) {
        if (did.isNullOrBlank() || account.isNullOrBlank()) return
        fetchJsonOrNull(resolveBackendEndpoint(baseUrl, null, "/dids/$account/diddoc.json"))
            ?.let { put(did, it) }
    }

    private fun parseJsonObjectOrEmpty(jsonPayload: String): JsonObject {
        if (jsonPayload.isBlank()) {
            return buildJsonObject { }
        }
        return runCatching { Json.parseToJsonElement(jsonPayload).jsonObject }.getOrElse {
            buildJsonObject { }
        }
    }

    private fun validateCredentialAgainstWallet(
        credential: JsonObject,
        walletState: WalletStateStore.WalletState?
    ) {
        val subject = credential.obj("credentialSubject")
            ?: throw IllegalArgumentException("credentialSubject is required")
        val status = credential.obj("credentialStatus")
            ?: throw IllegalArgumentException("credentialStatus is required")
        val holderDid = credential.text("holderDid") ?: subject.text("id").orEmpty()
        val holderAccount = requireValidXrplClassicAddress(
            "credentialStatus.subject",
            status.text("subject") ?: accountFromDid(holderDid)
        )
        val issuerDid = credential.text("issuerDid") ?: credential.text("issuer").orEmpty()
        val issuerAccount = requireValidXrplClassicAddress(
            "credentialStatus.issuer",
            status.text("issuer") ?: accountFromDid(issuerDid)
        )
        val subjectAccount = requireValidXrplClassicAddress(
            "credentialStatus.subject",
            status.text("subject") ?: accountFromDid(holderDid)
        )
        val credentialType = status.text("credentialType")

        if (walletState != null) {
            require(holderDid == walletState.did) {
                "holder DID mismatch: wallet=${walletState.did}, vc=$holderDid"
            }
            require(holderAccount == requireValidXrplClassicAddress("wallet account", walletState.account)) {
                "holder account mismatch: wallet=${walletState.account}, vc=$holderAccount"
            }
        }

        require(subjectAccount == holderAccount) {
            "credentialStatus.subject mismatch: status=$subjectAccount, vc=$holderAccount"
        }
        require(issuerAccount == requireValidXrplClassicAddress("issuer DID account", accountFromDid(issuerDid))) {
            "credentialStatus.issuer mismatch: status=$issuerAccount, issuerDid=$issuerDid"
        }
        require(!credentialType.isNullOrBlank()) { "credentialStatus.credentialType is required" }

        credential.text("validFrom")?.let { validFrom ->
            val validFromInstant = parseInstantOrNull(validFrom)
            if (validFromInstant != null) {
                require(!Instant.now().isBefore(validFromInstant)) { "VC is not yet valid" }
            }
        }
        credential.text("validUntil")
            ?.takeIf { it.isNotBlank() }
            ?.let { validUntil ->
                val validUntilInstant = parseInstantOrNull(validUntil)
                if (validUntilInstant != null) {
                    require(Instant.now().isBefore(validUntilInstant)) { "VC has expired" }
                }
            }

        if (status.text("credentialType").isNullOrBlank()) {
            throw IllegalArgumentException("credentialStatus.credentialType is required")
        }
    }

    private fun ensurePresentationMatchesWallet(
        presentation: JsonObject,
        didDocument: JsonObject,
        walletState: WalletStateStore.WalletState
    ) {
        val holder = presentation.text("holder") ?: throw IllegalArgumentException("presentation.holder is required")
        require(holder == walletState.did) { "presentation holder mismatch: wallet=${walletState.did}, vp=$holder" }
        val proof = presentation.obj("proof") ?: throw IllegalArgumentException("presentation.proof is required")
        val verificationMethod = proof.text("verificationMethod")
            ?: throw IllegalArgumentException("presentation.proof.verificationMethod is required")
        val authentication = didDocument["authentication"] as? JsonArray ?: JsonArray(emptyList())
        require(authentication.any { it.jsonPrimitive.contentOrNull == verificationMethod }) {
            "verificationMethod is not authorized for authentication"
        }
    }

    private fun ensurePresentationContainsCredential(
        presentation: JsonObject,
        vcObject: JsonObject
    ) {
        val presentationCredential = (presentation["verifiableCredential"] as? JsonArray)
            ?.firstOrNull()
            ?.jsonObject
            ?: throw IllegalArgumentException("presentation.verifiableCredential is required")
        val expectedStatus = vcObject.obj("credentialStatus")
            ?: throw IllegalArgumentException("credentialStatus is required")
        val actualStatus = presentationCredential.obj("credentialStatus")
            ?: throw IllegalArgumentException("presentation credentialStatus is required")

        fun requireSame(field: String, expected: String?, actual: String?) {
            require(expected == actual) {
                "presentation VC mismatch: $field expected=$expected, actual=$actual"
            }
        }

        requireSame("id", vcObject.text("id"), presentationCredential.text("id"))
        requireSame("issuer", vcObject.text("issuer"), presentationCredential.text("issuer"))
        requireSame("credentialStatus.issuer", expectedStatus.text("issuer"), actualStatus.text("issuer"))
        requireSame("credentialStatus.subject", expectedStatus.text("subject"), actualStatus.text("subject"))
        requireSame("credentialStatus.credentialType", expectedStatus.text("credentialType"), actualStatus.text("credentialType"))
        requireSame("credentialStatus.vcCoreHash", expectedStatus.text("vcCoreHash"), actualStatus.text("vcCoreHash"))
    }

    private fun defaultVerifierPolicy(vcObject: JsonObject): JsonObject {
        val credentialSubject = vcObject.obj("credentialSubject")
        val trustedIssuer = vcObject.text("issuer").orEmpty()
        val acceptedKycLevels = credentialSubject
            ?.text("kycLevel")
            ?.let { JsonArray(listOf(JsonPrimitive(it))) }
            ?: JsonArray(emptyList())
        val acceptedJurisdictions = credentialSubject
            ?.text("jurisdiction")
            ?.let { JsonArray(listOf(JsonPrimitive(it))) }
            ?: JsonArray(emptyList())

        return buildJsonObject {
            if (trustedIssuer.isNotBlank()) {
                put("trustedIssuers", JsonArray(listOf(JsonPrimitive(trustedIssuer))))
            } else {
                put("trustedIssuers", JsonArray(emptyList()))
            }
            put("acceptedKycLevels", acceptedKycLevels)
            put("acceptedJurisdictions", acceptedJurisdictions)
        }
    }

    private fun computeVcCoreHash(vcObject: JsonObject): String {
        val coreObject = buildJsonObject {
            vcObject.forEach { (key, value) ->
                if (key != "proof") {
                    put(key, value)
                }
            }
        }
        val canonical = JsonCanonicalizer(coreObject.toString()).encodedString
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun verifyIssuerProof(vcObject: JsonObject): IssuerProofVerification {
        val issuerDocument = vcObject.jsonObjectOrNull("issuerDidDocument")
            ?: vcObject.jsonObjectOrNull("issuerDocument")
            ?: vcObject.jsonObjectOrNull("issuer_document")
            ?: return IssuerProofVerification(supported = false, verified = false, status = "issuer DID Document missing", error = null)

        val proof = vcObject.obj("proof")
            ?: return IssuerProofVerification(supported = true, verified = false, status = "proof missing", error = "proof is missing")

        val verificationMethodId = proof.text("verificationMethod")
            ?: issuerDocument["verificationMethod"]
                ?.let { methods ->
                    (methods as? kotlinx.serialization.json.JsonArray)
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.text("id")
                }
            ?: return IssuerProofVerification(supported = true, verified = false, status = "verificationMethod missing", error = "issuer verificationMethod is missing")

        val method = findVerificationMethod(issuerDocument, verificationMethodId)
            ?: return IssuerProofVerification(supported = true, verified = false, status = "verificationMethod not found", error = "issuer verificationMethod not found")

        val publicKeyJwk = method.obj("publicKeyJwk")
            ?: return IssuerProofVerification(supported = true, verified = false, status = "publicKeyJwk missing", error = "issuer publicKeyJwk is missing")

        val signatureValue = proof.text("signature")
            ?: proof.text("proofValue")
            ?: proof.text("jws")
            ?: return IssuerProofVerification(supported = true, verified = false, status = "signature missing", error = "issuer proof signature is missing")

        val signatureBytes = decodeSignatureBytes(signatureValue)
            ?: return IssuerProofVerification(supported = true, verified = false, status = "signature format unsupported", error = "issuer proof signature format is unsupported")

        val canonicalVc = canonicalizeWithoutProof(vcObject)
        val publicKey = publicKeyFromJwk(publicKeyJwk)
            ?: return IssuerProofVerification(supported = true, verified = false, status = "public key invalid", error = "issuer public key is invalid")
        val verified = runCatching {
            val verifier = Signature.getInstance("SHA256withECDSA", "BC")
            verifier.initVerify(publicKey)
            verifier.update(canonicalVc.toByteArray(Charsets.UTF_8))
            verifier.verify(signatureBytes)
        }.getOrDefault(false)

        return if (verified) {
            IssuerProofVerification(
                supported = true,
                verified = true,
                status = "verified",
                error = null
            )
        } else {
            IssuerProofVerification(
                supported = true,
                verified = false,
                status = "signature invalid",
                error = "issuer proof signature verification failed"
            )
        }
    }

    private fun canonicalizeWithoutProof(vcObject: JsonObject): String {
        val coreObject = buildJsonObject {
            vcObject.forEach { (key, value) ->
                if (key != "proof") {
                    put(key, value)
                }
            }
        }
        return JsonCanonicalizer(coreObject.toString()).encodedString
    }

    private fun findVerificationMethod(issuerDocument: JsonObject, verificationMethodId: String): JsonObject? {
        val methods = issuerDocument["verificationMethod"] as? JsonArray ?: return null
        return methods.firstOrNull { element ->
            val method = element as? JsonObject ?: return@firstOrNull false
            method.text("id") == verificationMethodId
        } as? JsonObject
    }

    private fun publicKeyFromJwk(jwk: JsonObject): java.security.PublicKey? {
        val x = jwk.text("x") ?: return null
        val y = jwk.text("y") ?: return null
        val curveSpec = ECNamedCurveTable.getParameterSpec("secp256k1")
        val xBytes = Base64.decode(x, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val yBytes = Base64.decode(y, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val pointBytes = ByteArray(1 + xBytes.size + yBytes.size)
        pointBytes[0] = 0x04
        xBytes.copyInto(pointBytes, destinationOffset = 1)
        yBytes.copyInto(pointBytes, destinationOffset = 1 + xBytes.size)
        val point = curveSpec.curve.decodePoint(pointBytes)
        val keySpec = ECPublicKeySpec(point, curveSpec)
        return runCatching {
            KeyFactory.getInstance("EC", "BC").generatePublic(keySpec)
        }.getOrNull()
    }

    private fun decodeSignatureBytes(signatureValue: String): ByteArray? {
        if (signatureValue.isBlank()) return null
        val candidate = if (signatureValue.count { it == '.' } == 2) {
            signatureValue.substringAfterLast('.')
        } else {
            signatureValue
        }
        return runCatching {
            Base64.decode(candidate, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull()
    }

    private fun JsonObject.jsonObjectOrNull(name: String): JsonObject? {
        val element = this[name] ?: return null
        return when (element) {
            is JsonObject -> element
            is JsonPrimitive -> runCatching { Json.parseToJsonElement(element.content).jsonObject }.getOrNull()
            else -> null
        }
    }

    private fun markVerifierChallengeUsed(challenge: String) {
        val record = getVerifierChallengeRecord(challenge) ?: return
        storeVerifierChallenge(
            challenge = record.optString("challenge"),
            domain = record.optString("domain"),
            issuedAt = record.optString("issuedAt"),
            expiresAt = record.optString("expiresAt"),
            used = true
        )
    }

    private fun challengeDigest(challenge: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(challenge.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun ensureVerifierChallengeUsable(challenge: String, domain: String? = null) {
        val record = getVerifierChallengeRecord(challenge)
            ?: throw IllegalArgumentException("VP challenge was not issued by verifier")
        val storedDomain = record.optString("domain").takeIf { it.isNotBlank() }
        if (!domain.isNullOrBlank() && !storedDomain.isNullOrBlank()) {
            require(domain == storedDomain) { "VP domain mismatch" }
        }
        val expiresAt = record.optString("expiresAt").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("VP challenge expiration missing")
        val expiresInstant = parseInstantOrNull(expiresAt)
            ?: throw IllegalArgumentException("VP challenge expiration is invalid")
        require(Instant.now().isBefore(expiresInstant)) { "VP challenge expired" }
        require(!record.optBoolean("used", false)) { "VP challenge was already used" }
    }

    private fun storeVerifierChallenge(
        challenge: String,
        domain: String,
        issuedAt: String,
        expiresAt: String,
        used: Boolean
    ) {
        val record = JSONObject()
            .put("challenge", challenge)
            .put("domain", domain)
            .put("issuedAt", issuedAt)
            .put("expiresAt", expiresAt)
            .put("used", used)
            .put("updatedAt", nowUtcIso())
        verifierPrefs.edit()
            .putString("challenge:${challengeDigest(challenge)}", record.toString())
            .apply()
    }

    private fun getVerifierChallengeRecord(challenge: String): JSONObject? {
        val raw = verifierPrefs.getString("challenge:${challengeDigest(challenge)}", null) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private data class IssuerProofVerification(
        val supported: Boolean,
        val verified: Boolean,
        val status: String,
        val error: String?
    )

    private fun postJson(endpoint: String, body: String): String {
        return postJson(endpoint, body, "Verifier")
    }

    private fun postJson(endpoint: String, body: String, requestLabel: String): String {
        val url = endpoint.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid $requestLabel endpoint: $endpoint")
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("$requestLabel request failed (${response.code}): $responseBody")
            }
            return responseBody
        }
    }

    private fun fetchJsonOrNull(endpoint: String): JsonObject? {
        val url = endpoint.toHttpUrlOrNull() ?: return null
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Json.parseToJsonElement(it).jsonObject }
            }
        }.getOrNull()
    }

    private fun resolveBackendEndpoint(baseUrl: String, endpoint: String?, defaultPath: String): String {
        val trimmedEndpoint = endpoint?.trim().orEmpty()
        if (trimmedEndpoint.isNotBlank()) {
            return if (
                trimmedEndpoint.startsWith("http://", ignoreCase = true) ||
                trimmedEndpoint.startsWith("https://", ignoreCase = true)
            ) trimmedEndpoint else "https://$trimmedEndpoint"
        }
        val normalizedBase = if (
            baseUrl.startsWith("http://", ignoreCase = true) ||
            baseUrl.startsWith("https://", ignoreCase = true)
        ) baseUrl.trim() else "https://${baseUrl.trim()}"
        val baseHttpUrl = normalizedBase.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid backend base URL: $baseUrl")
        return baseHttpUrl.newBuilder()
            .addPathSegments(defaultPath.trim('/'))
            .build()
            .toString()
    }

    private fun parseInstantOrNull(value: String): Instant? {
        return try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun accountFromDid(did: String): String {
        val prefix = "did:xrpl:1:"
        return if (did.startsWith(prefix)) did.removePrefix(prefix) else ""
    }

    private fun parseSeed(seedValue: String): Seed {
        return try {
            Seed.fromBase58EncodedSecret(Base58EncodedSecret.of(seedValue.trim()))
        } catch (e: Exception) {
            throw IllegalArgumentException("issuerSeed is invalid: ${e.message}", e)
        }
    }

    private fun requireValidXrplClassicAddress(label: String, value: String): String {
        val address = value.trim()
        require(address.isNotBlank()) { "$label is required" }
        runCatching { Address.of(address) }.getOrElse {
            val hint = if (
                address.contains("testnet", ignoreCase = true) ||
                address.contains("example", ignoreCase = true) ||
                address.contains("placeholder", ignoreCase = true) ||
                address.contains("accountfortestnet", ignoreCase = true)
            ) {
                "This looks like sample data. Replace it with a real XRPL devnet classic address."
            } else {
                "Use the XRPL classic address itself, not a DID."
            }
            throw IllegalArgumentException("$label must be a real XRPL classic address (25-35 chars, starts with r): $address. $hint")
        }
        return address
    }

    private suspend fun resolveVcJson(request: JsonObject): String {
        request.text("vcJson")?.let { return parseCredentialEnvelope(it).payload.toString() }
        request.text("vcJwt")?.let { return decodeJwtPayload(it).toString() }
        request.text("credential")?.takeIf { isCompactJwt(it) }?.let { return decodeJwtPayload(it).toString() }
        request.obj("credential")?.let { return it.toString() }
        request.text("credentialId")
            ?.takeIf { it.isNotBlank() }
            ?.let { credentialId ->
                credentialRepository.getCredentialById(credentialId)?.vcJson?.let {
                    return parseCredentialEnvelope(it).payload.toString()
                }
            }
        throw IllegalArgumentException("vcJson, credential, or credentialId is required")
    }

    private suspend fun resolveVcJwt(request: JsonObject): String? {
        request.text("vcJwt")?.takeIf { isCompactJwt(it) }?.let { return it }
        request.text("vcJson")?.takeIf { isCompactJwt(it) }?.let { return it }
        request.text("credential")?.takeIf { isCompactJwt(it) }?.let { return it }
        request.text("credentialId")
            ?.takeIf { it.isNotBlank() }
            ?.let { credentialId ->
                credentialRepository.getCredentialById(credentialId)?.vcJson?.takeIf { isCompactJwt(it) }?.let {
                    return it
                }
            }
        return null
    }

    private fun parseCredentialEnvelope(raw: String): CredentialEnvelope {
        val trimmed = raw.trim()
        if (isCompactJwt(trimmed)) {
            return CredentialEnvelope(rawCredential = trimmed, vcJwt = trimmed, payload = decodeJwtPayload(trimmed))
        }
        val json = Json.parseToJsonElement(trimmed).jsonObject
        val jwt = json.text("vcJwt")
            ?: json.text("credential")?.takeIf { isCompactJwt(it) }
            ?: json.text("vc_jwt")
        if (!jwt.isNullOrBlank()) {
            return CredentialEnvelope(rawCredential = jwt, vcJwt = jwt, payload = decodeJwtPayload(jwt))
        }
        return CredentialEnvelope(rawCredential = trimmed, vcJwt = null, payload = json)
    }

    private fun validateCredentialJwt(
        envelope: CredentialEnvelope,
        issuerDidDocument: JsonObject?
    ) {
        val jwt = envelope.vcJwt ?: return
        val header = decodeJwtHeader(jwt)
        require(header.text("alg") == "ES256K") { "VC JWT alg must be ES256K" }
        require(header.text("typ") == "vc+jwt") { "VC JWT typ must be vc+jwt" }
        require(header.text("cty") == "vc") { "VC JWT cty must be vc" }
        val issuer = envelope.payload.text("issuer")
            ?: throw IllegalArgumentException("VC JWT payload.issuer is required")
        header.text("iss")?.let { iss ->
            require(iss == issuer) { "VC JWT header.iss mismatch: header=$iss, payload=$issuer" }
        }
        val kid = header.text("kid")
            ?: throw IllegalArgumentException("VC JWT kid is required")
        require(kid.startsWith("$issuer#")) {
            "VC JWT kid must belong to issuer DID: kid=$kid, issuer=$issuer"
        }
        if (issuerDidDocument != null) {
            verifyCredentialJwtSignature(jwt, kid, issuerDidDocument)
        }
    }

    private fun verifyCredentialJwtSignature(
        jwt: String,
        kid: String,
        issuerDidDocument: JsonObject
    ) {
        val method = findVerificationMethod(issuerDidDocument, kid)
            ?: throw IllegalArgumentException("VC JWT verification method not found: $kid")
        val publicKeyJwk = method.obj("publicKeyJwk")
            ?: throw IllegalArgumentException("VC JWT publicKeyJwk is missing")
        val publicKey = publicKeyFromJwk(publicKeyJwk)
            ?: throw IllegalArgumentException("VC JWT public key is invalid")
        val parts = jwt.split(".")
        require(parts.size == 3) { "compact JWT must have three segments" }
        val rawSignature = Base64.decode(parts[2], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        require(rawSignature.size == 64) { "VC JWT signature must be JOSE raw 64 bytes" }
        val derSignature = joseRaw64ToDer(rawSignature)
        val verified = runCatching {
            val verifier = Signature.getInstance("SHA256withECDSA", "BC")
            verifier.initVerify(publicKey)
            verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            verifier.verify(derSignature)
        }.getOrDefault(false)
        require(verified) { "VC JWT signature verification failed" }
    }

    private fun decodeJwtHeader(jwt: String): JsonObject {
        val parts = jwt.split(".")
        require(parts.size == 3) { "compact JWT must have three segments" }
        return Json.parseToJsonElement(decodeBase64UrlToString(parts[0])).jsonObject
    }

    private fun decodeJwtPayload(jwt: String): JsonObject {
        val parts = jwt.split(".")
        require(parts.size == 3) { "compact JWT must have three segments" }
        return Json.parseToJsonElement(decodeBase64UrlToString(parts[1])).jsonObject
    }

    private fun isCompactJwt(value: String): Boolean {
        val parts = value.trim().split(".")
        return parts.size == 3 && parts.all { it.isNotBlank() }
    }

    private fun decodeBase64UrlToString(value: String): String {
        val bytes = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun joseRaw64ToDer(rawSignature: ByteArray): ByteArray {
        require(rawSignature.size == 64) { "JOSE ECDSA signature must be 64 bytes" }
        val r = rawSignature.copyOfRange(0, 32).stripLeadingZeroesForDer()
        val s = rawSignature.copyOfRange(32, 64).stripLeadingZeroesForDer()
        val sequenceLength = 2 + r.size + 2 + s.size
        return byteArrayOf(0x30, sequenceLength.toByte(), 0x02, r.size.toByte()) +
            r +
            byteArrayOf(0x02, s.size.toByte()) +
            s
    }

    private fun ByteArray.stripLeadingZeroesForDer(): ByteArray {
        val strippedValue = dropWhile { it == 0.toByte() }.toByteArray()
        val stripped = if (strippedValue.isEmpty()) byteArrayOf(0) else strippedValue
        return if ((stripped[0].toInt() and 0x80) != 0) byteArrayOf(0) + stripped else stripped
    }

    private data class CredentialEnvelope(
        val rawCredential: String,
        val vcJwt: String?,
        val payload: JsonObject
    )

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
            put("authPublicKey", walletState.authPublicKey)
            put("did", walletState.did)
            put("didDocument", buildHolderDidDocument(walletState))
        }
    }

    private fun buildHolderDidDocument(walletState: WalletStateStore.WalletState): String {
        val publicKeyJwk = publicKeyJwk(walletState.authPublicKey)
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

    private data class VerifierFailureInfo(
        val code: String,
        val title: String,
        val hint: String
    )

    private data class QrRequestInfo(
        val actionType: String,
        val endpoint: String?,
        val coreBaseUrl: String?,
        val challenge: String?,
        val domain: String?,
        val expiresAt: String?,
        val expiresInSec: String?,
        val qrData: String?,
        val rawPayload: String?
    )

    private companion object {
        private const val TAG = "WalletBridge"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
