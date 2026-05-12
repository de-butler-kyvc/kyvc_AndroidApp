package com.example.kyvc_androidapp.bridge

import android.content.Context
import android.content.SharedPreferences
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import com.example.kyvc_androidapp.data.local.entity.CredentialEntity
import com.example.kyvc_androidapp.data.local.entity.HolderDocumentEntity
import com.example.kyvc_androidapp.data.repository.CredentialRepository
import com.example.kyvc_androidapp.data.repository.HolderDocumentRepository
import com.example.kyvc_androidapp.security.AppLockStore
import com.example.kyvc_androidapp.security.SecureDocumentStore
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
import kotlinx.serialization.json.longOrNull
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
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.lang.ref.WeakReference
import java.math.BigInteger
import java.security.KeyFactory
import java.time.Instant
import java.time.format.DateTimeParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.security.MessageDigest
import java.security.Signature
import android.util.Base64
import java.io.File
import java.util.concurrent.TimeUnit

class WalletBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val walletStateStore: WalletStateStore,
    private val xrplHelper: XrplClientHelper,
    private val credentialRepository: CredentialRepository,
    private val holderDocumentRepository: HolderDocumentRepository,
    private val secureDocumentStore: SecureDocumentStore,
    private val appLockStore: AppLockStore,
    private val launchQrScanner: (String) -> Unit,
    private val launchNativeAuth: (String, String) -> Unit,
    private val launchPinReset: (String) -> Unit,
    private val launchMnemonicBackup: (String, String) -> Unit,
    private val launchWalletRestore: (String) -> Unit,
    private val onSessionStatusChanged: (Boolean) -> Unit = {}
) {
    private val verifierPrefs: SharedPreferences = context.getSharedPreferences("kyvc-verifier", Context.MODE_PRIVATE)
    private val devicePrefs: SharedPreferences = context.getSharedPreferences("kyvc-device", Context.MODE_PRIVATE)
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
    fun listWallets(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            try {
                requireTrustedBridgeOrigin("LIST_WALLETS")
                withContext(Dispatchers.IO) {
                    val wallets = walletStateStore.listWallets()
                    val activeWallet = walletStateStore.getWalletStateOrNull()
                    withContext(Dispatchers.Main) {
                        emitCallback("LIST_WALLETS", true) {
                            put("activeAccount", activeWallet?.account ?: "")
                            put(
                                "wallets",
                                JsonArray(
                                    wallets.map { wallet ->
                                        buildJsonObject {
                                            put("account", wallet.account)
                                            put("did", wallet.did)
                                            put("name", wallet.name)
                                            put("derivationIndex", wallet.derivationIndex)
                                            put("mnemonicHash", wallet.mnemonic?.let { 
                                                MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                                                    .joinToString("") { b -> "%02x".format(b) }.substring(0, 8) 
                                            } ?: "standalone")
                                            put("hasMnemonic", !wallet.mnemonic.isNullOrBlank())
                                            put("isActive", wallet.account == activeWallet?.account)
                                        }
                                    }
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "listWallets failed", e)
                emitCallback("LIST_WALLETS", false) {
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun removeWallet(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            val request = parseJsonObjectOrEmpty(jsonPayload)
            try {
                requireTrustedBridgeOrigin("REMOVE_WALLET")
                validateBridgeRequest(
                    request = request,
                    allowedActions = setOf("REMOVE_WALLET"),
                    ttlSeconds = 30
                )
                val account = request.text("account")
                    ?: walletStateStore.getWalletStateOrNull()?.account
                    ?: throw IllegalArgumentException("account is required")
                
                withContext(Dispatchers.IO) {
                    val removed = walletStateStore.removeWallet(account)
                    require(removed) { "Wallet not found: $account" }
                    val nextWallet = walletStateStore.getWalletStateOrNull()
                    currentSeed = nextWallet?.let { walletStateStore.requireSeed() }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Wallet removed", Toast.LENGTH_SHORT).show()
                        if (nextWallet != null) {
                            emitCallback("REMOVE_WALLET", true) {
                                put("account", nextWallet.account)
                                put("publicKey", nextWallet.publicKey)
                                put("authPublicKey", nextWallet.authPublicKey)
                                put("did", nextWallet.did)
                                nextWallet.mnemonic?.let { put("mnemonic", it) }
                                put("didDocument", buildHolderDidDocument(nextWallet))
                                put("removedAccount", account)
                            }
                        } else {
                            emitCallback("REMOVE_WALLET", true) {
                                put("noWalletsLeft", true)
                                put("removedAccount", account)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "removeWallet failed", e)
                emitCallback("REMOVE_WALLET", false) {
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun setAccountName(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            val request = parseJsonObjectOrEmpty(jsonPayload)
            try {
                requireTrustedBridgeOrigin("SET_ACCOUNT_NAME")
                validateBridgeRequest(
                    request = request,
                    allowedActions = setOf("SET_ACCOUNT_NAME"),
                    ttlSeconds = 30
                )
                val account = request.text("account") ?: walletStateStore.getWalletStateOrNull()?.account
                val name = request.text("name") ?: throw IllegalArgumentException("name is required")
                
                if (account == null) throw IllegalStateException("No active account")
                
                withContext(Dispatchers.IO) {
                    walletStateStore.setAccountName(account, name)
                    val walletState = walletStateStore.requireWalletState()
                    withContext(Dispatchers.Main) {
                        emitWalletCallback("SET_ACCOUNT_NAME", true, walletState)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "setAccountName failed", e)
                emitCallback("SET_ACCOUNT_NAME", false) {
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun upgradeToMnemonic(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            val request = parseJsonObjectOrEmpty(jsonPayload)
            try {
                requireTrustedBridgeOrigin("UPGRADE_TO_MNEMONIC")
                validateBridgeRequest(
                    request = request,
                    allowedActions = setOf("UPGRADE_TO_MNEMONIC"),
                    ttlSeconds = 30
                )
                val account = request.text("account") ?: walletStateStore.getWalletStateOrNull()?.account
                
                if (account == null) throw IllegalStateException("No active account")
                
                withContext(Dispatchers.IO) {
                    val mnemonic = walletStateStore.upgradeToMnemonic(account)
                    val walletState = walletStateStore.requireWalletState()
                    withContext(Dispatchers.Main) {
                        emitWalletCallback("UPGRADE_TO_MNEMONIC", true, walletState)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "upgradeToMnemonic failed", e)
                emitCallback("UPGRADE_TO_MNEMONIC", false) {
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun deriveNextAccount(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            val request = parseJsonObjectOrEmpty(jsonPayload)
            try {
                requireTrustedBridgeOrigin("DERIVE_NEXT_ACCOUNT")
                validateBridgeRequest(
                    request = request,
                    allowedActions = setOf("DERIVE_NEXT_ACCOUNT"),
                    ttlSeconds = 30
                )
                val masterAccount = request.text("masterAccount") ?: walletStateStore.getWalletStateOrNull()?.account
                val customName = request.text("name")
                
                if (masterAccount == null) throw IllegalStateException("No active account")
                
                withContext(Dispatchers.IO) {
                    val walletState = walletStateStore.deriveNextAccount(masterAccount, customName)
                    currentSeed = walletStateStore.requireSeed()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "New account added: ${walletState.name}", Toast.LENGTH_SHORT).show()
                        emitWalletCallback("DERIVE_NEXT_ACCOUNT", true, walletState)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "deriveNextAccount failed", e)
                emitCallback("DERIVE_NEXT_ACCOUNT", false) {
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun switchWallet(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            try {
                requireTrustedBridgeOrigin("SWITCH_WALLET")
                val request = parseJsonObjectOrEmpty(jsonPayload)
                val account = request.text("account") ?: throw IllegalArgumentException("account is required")
                
                withContext(Dispatchers.IO) {
                    walletStateStore.switchWallet(account)
                    val walletState = walletStateStore.requireWalletState()
                    currentSeed = walletStateStore.requireSeed()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Switched to: ${walletState.account}", Toast.LENGTH_SHORT).show()
                        emitWalletCallback("SWITCH_WALLET", true, walletState)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "switchWallet failed", e)
                emitCallback("SWITCH_WALLET", false) {
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun getAuthStatus(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            try {
                requireTrustedBridgeOrigin("GET_AUTH_STATUS")
                emitCallback("GET_AUTH_STATUS", true) {
                    put("lockConfigured", appLockStore.hasAnyLock())
                    put("pinConfigured", appLockStore.hasPin())
                    put("patternConfigured", appLockStore.hasPattern())
                    put("biometricEnabled", appLockStore.isBiometricEnabled())
                    put("availableMethods", availableAuthMethods())
                    put("walletReady", runCatching { walletStateStore.requireWalletState() }.isSuccess)
                    putAuthAttemptState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "getAuthStatus failed", e)
                emitCallback("GET_AUTH_STATUS", false) {
                    putAuthAttemptState()
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun getDeviceInfo(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            val request = parseJsonObjectOrEmpty(jsonPayload)
            try {
                requireTrustedBridgeOrigin("GET_DEVICE_INFO")
                validateBridgeRequest(
                    request = request,
                    allowedActions = setOf("GET_DEVICE_INFO"),
                    ttlSeconds = 30
                )
                val walletState = withContext(Dispatchers.IO) {
                    walletStateStore.getWalletStateOrNull()
                }
                val deviceId = getOrCreateDeviceId()
                val deviceName = resolveDeviceName()
                val appVersion = resolveAppVersion()
                logBridgeFlow(
                    "vc.issue.deviceInfo.success",
                    mapOf(
                        "requestId" to request.text("requestId"),
                        "deviceId" to deviceId,
                        "deviceName" to deviceName,
                        "appVersion" to appVersion,
                        "walletReady" to (walletState != null),
                        "hasPublicKey" to !walletState?.authPublicKey.isNullOrBlank()
                    )
                )
                emitCallback("GET_DEVICE_INFO", true) {
                    request.text("requestId")?.let { put("requestId", it) }
                    put("deviceId", deviceId)
                    put("deviceName", deviceName)
                    put("os", "Android")
                    put("appVersion", appVersion)
                    put("publicKey", walletState?.authPublicKey ?: "")
                }
            } catch (e: Exception) {
                logBridgeFailure("vc.issue.deviceInfo.failed", request.text("requestId"), e)
                emitCallback("GET_DEVICE_INFO", false) {
                    request.text("requestId")?.let { put("requestId", it) }
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun completeEmailVerification(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            val request = parseJsonObjectOrEmpty(jsonPayload)
            try {
                requireTrustedBridgeOrigin("COMPLETE_EMAIL_VERIFICATION")
                validateBridgeRequest(
                    request = request,
                    allowedActions = setOf("COMPLETE_EMAIL_VERIFICATION"),
                    ttlSeconds = 60
                )
                appLockStore.resetAuthFailures()
                emitCallback("COMPLETE_EMAIL_VERIFICATION", true) {
                    request.text("requestId")?.let { put("requestId", it) }
                    putAuthAttemptState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "completeEmailVerification failed", e)
                emitCallback("COMPLETE_EMAIL_VERIFICATION", false) {
                    request.text("requestId")?.let { put("requestId", it) }
                    putAuthAttemptState()
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun requestNativeAuth(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            val request = parseJsonObjectOrEmpty(jsonPayload)
            val requestId = request.text("requestId")
            val method = request.text("method")?.lowercase(Locale.US)
            try {
                requireTrustedBridgeOrigin("REQUEST_NATIVE_AUTH")
                validateBridgeRequest(
                    request = request,
                    allowedActions = setOf("REQUEST_NATIVE_AUTH"),
                    ttlSeconds = 30
                )
                if (appLockStore.isEmailVerificationRequired()) {
                    emitCallback("REQUEST_NATIVE_AUTH", false) {
                        requestId?.let { put("requestId", it) }
                        method?.let { put("method", it) }
                        put("authenticated", false)
                        putAuthAttemptState()
                        put("error", "인증 5회 실패로 이메일 인증이 필요합니다.")
                    }
                    return@launch
                }
                require(method in AUTH_METHODS) {
                    "method must be one of ${AUTH_METHODS.joinToString(", ")}"
                }
                requireAuthMethodConfigured(method!!)
                launchNativeAuth(jsonPayload, method)
            } catch (e: Exception) {
                Log.e(TAG, "requestNativeAuth failed", e)
                emitCallback("REQUEST_NATIVE_AUTH", false) {
                    requestId?.let { put("requestId", it) }
                    method?.let { put("method", it) }
                    put("authenticated", false)
                    putAuthAttemptState()
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun requestPinReset(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            val request = parseJsonObjectOrEmpty(jsonPayload)
            val requestId = request.text("requestId")
            try {
                requireTrustedBridgeOrigin("REQUEST_PIN_RESET")
                validateBridgeRequest(
                    request = request,
                    allowedActions = setOf("REQUEST_PIN_RESET"),
                    ttlSeconds = 30
                )
                if (appLockStore.isEmailVerificationRequired()) {
                    emitCallback("REQUEST_PIN_RESET", false) {
                        requestId?.let { put("requestId", it) }
                        put("reset", false)
                        putAuthAttemptState()
                        put("error", "인증 5회 실패로 이메일 인증이 필요합니다.")
                    }
                    return@launch
                }
                launchPinReset(jsonPayload)
            } catch (e: Exception) {
                Log.e(TAG, "requestPinReset failed", e)
                emitCallback("REQUEST_PIN_RESET", false) {
                    requestId?.let { put("requestId", it) }
                    put("reset", false)
                    putAuthAttemptState()
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun requestMnemonicBackup(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            val request = parseJsonObjectOrEmpty(jsonPayload)
            val requestId = request.text("requestId")
            try {
                requireTrustedBridgeOrigin("REQUEST_MNEMONIC_BACKUP")
                validateBridgeRequest(
                    request = request,
                    allowedActions = setOf("REQUEST_MNEMONIC_BACKUP"),
                    ttlSeconds = 30
                )
                require(!appLockStore.isEmailVerificationRequired()) {
                    "이메일 인증이 필요한 상태에서는 비밀문구를 백업할 수 없습니다."
                }
                require(appLockStore.isSessionUnlocked()) {
                    "활성 인증 세션이 필요합니다. 먼저 네이티브 인증을 완료하세요."
                }
                withContext(Dispatchers.IO) {
                    val mnemonicValue = walletStateStore.exportMnemonicValue()
                    require(mnemonicValue.isNotBlank()) {
                        "현재 지갑에 저장된 비밀문구가 없습니다. 시드(Seed)로 복구했거나 구형 지갑인 경우 비밀문구가 존재하지 않습니다."
                    }
                    withContext(Dispatchers.Main) {
                        launchMnemonicBackup(jsonPayload, mnemonicValue)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "requestMnemonicBackup failed", e)
                emitCallback("REQUEST_MNEMONIC_BACKUP", false) {
                    requestId?.let { put("requestId", it) }
                    putAuthAttemptState()
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun requestWalletRestore(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            val request = parseJsonObjectOrEmpty(jsonPayload)
            val requestId = request.text("requestId")
            try {
                requireTrustedBridgeOrigin("REQUEST_WALLET_RESTORE")
                validateBridgeRequest(
                    request = request,
                    allowedActions = setOf("REQUEST_WALLET_RESTORE"),
                    ttlSeconds = 30
                )
                launchWalletRestore(jsonPayload)
            } catch (e: Exception) {
                Log.e(TAG, "requestWalletRestore failed", e)
                emitCallback("REQUEST_WALLET_RESTORE", false) {
                    requestId?.let { put("requestId", it) }
                    put("restored", false)
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun createWallet(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            try {
                // Check WebView URL on Main thread
                requireTrustedBridgeOrigin("CREATE_WALLET")

                withContext(Dispatchers.IO) {
                    val request = parseJsonObjectOrEmpty(jsonPayload)
                    val overwrite = request.text("overwrite")?.toBooleanStrictOrNull() ?: false
                    val walletState = walletStateStore.createWallet(overwrite = overwrite)
                    currentSeed = walletStateStore.requireSeed()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Wallet ready: ${walletState.account}", Toast.LENGTH_SHORT).show()
                        emitWalletCallback("CREATE_WALLET", true, walletState)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "createWallet failed", e)
                Toast.makeText(context, "Failed to create wallet: ${e.message}", Toast.LENGTH_LONG).show()
                emitCallback("CREATE_WALLET", false) {
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun getWalletInfo(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            try {
                // Check WebView URL on Main thread
                requireTrustedBridgeOrigin("GET_WALLET_INFO")

                withContext(Dispatchers.IO) {
                    require(appLockStore.isSessionUnlocked()) { "활성 인증 세션이 필요합니다." }
                    val walletState = walletStateStore.requireWalletState()
                    currentSeed = walletStateStore.requireSeed()
                    withContext(Dispatchers.Main) {
                        emitWalletCallback("GET_WALLET_INFO", true, walletState)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "getWalletInfo failed", e)
                emitCallback("GET_WALLET_INFO", false) {
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun getWalletAssets(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            try {
                // Check WebView URL on Main thread
                requireTrustedBridgeOrigin("GET_WALLET_ASSETS")

                withContext(Dispatchers.IO) {
                    val walletState = walletStateStore.requireWalletState()
                    val result = xrplHelper.getAccountAssets(walletState.account)
                    withContext(Dispatchers.Main) {
                        val inactiveAccount = !result.accountActivated &&
                            result.error?.let(::isInactiveXrplAccountMessage) == true
                        emitCallback("GET_WALLET_ASSETS", result.error == null || inactiveAccount) {
                            put("account", result.account)
                            put("accountActivated", result.accountActivated)
                            put("depositRequired", inactiveAccount)
                            result.xrpBalanceDrops?.let { put("xrpBalanceDrops", it) }
                            result.xrpBalanceXrp?.let { put("xrpBalanceXrp", it) }
                            result.ownerCount?.let { put("ownerCount", it) }
                            result.sequence?.let { put("sequence", it) }
                            put("trustLineCount", result.lines.size)
                            put(
                                "lines",
                                JsonArray(
                                    result.lines.map { line ->
                                        buildJsonObject {
                                            put("currency", line.currency)
                                            put("issuer", line.issuer)
                                            put("balance", line.balance)
                                            put("limit", line.limit)
                                            put("limitPeer", line.limitPeer)
                                        }
                                    }
                                )
                            )
                            put("checkedAtUtc", result.checkedAtUtc)
                            if (inactiveAccount) {
                                put("errorCode", "XRPL_ACCOUNT_NOT_ACTIVATED")
                                put("errorTitle", "XRPL 계정 활성화 필요")
                                put("errorHint", "이 주소로 XRP를 입금한 뒤 자산 조회를 다시 실행하세요.")
                            }
                            result.error?.let { put("error", it) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "getWalletAssets failed", e)
                emitCallback("GET_WALLET_ASSETS", false) {
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun getWalletDepositInfo(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            try {
                // Check WebView URL on Main thread
                requireTrustedBridgeOrigin("GET_WALLET_DEPOSIT_INFO")

                withContext(Dispatchers.IO) {
                    val walletState = walletStateStore.requireWalletState()
                    val receivePayload = walletState.account
                    withContext(Dispatchers.Main) {
                        emitCallback("GET_WALLET_DEPOSIT_INFO", true) {
                            put("account", walletState.account)
                            put("did", walletState.did)
                            put("receiveAddress", walletState.account)
                            put("qrPayload", receivePayload)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "getWalletDepositInfo failed", e)
                emitCallback("GET_WALLET_DEPOSIT_INFO", false) {
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun getWalletTransactions(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            try {
                // Check WebView URL on Main thread
                requireTrustedBridgeOrigin("GET_WALLET_TRANSACTIONS")

                withContext(Dispatchers.IO) {
                    val request = parseJsonObjectOrEmpty(jsonPayload)
                    val limit = request.text("limit")?.toIntOrNull() ?: 10
                    val walletState = walletStateStore.requireWalletState()
                    val result = xrplHelper.getAccountTransactions(walletState.account, limit)
                    withContext(Dispatchers.Main) {
                        val inactiveAccount = result.error?.let(::isInactiveXrplAccountMessage) == true
                        emitCallback("GET_WALLET_TRANSACTIONS", result.error == null || inactiveAccount) {
                            put("account", result.account)
                            put("accountActivated", !inactiveAccount)
                            put("depositRequired", inactiveAccount)
                            put("count", result.transactions.size)
                            put(
                                "transactions",
                                JsonArray(
                                    result.transactions.map { tx ->
                                        buildJsonObject {
                                            put("hash", tx.hash)
                                            put("transactionType", tx.transactionType)
                                            put("direction", tx.direction)
                                            put("account", tx.account)
                                            tx.destination?.let { put("destination", it) }
                                            tx.amountDrops?.let { put("amountDrops", it) }
                                            tx.amountXrp?.let { put("amountXrp", it) }
                                            tx.feeDrops?.let { put("feeDrops", it) }
                                            tx.feeXrp?.let { put("feeXrp", it) }
                                            tx.sequence?.let { put("sequence", it) }
                                            put("validated", tx.validated)
                                            tx.ledgerIndex?.let { put("ledgerIndex", it) }
                                            tx.result?.let { put("result", it) }
                                            tx.dateUtc?.let { put("dateUtc", it) }
                                        }
                                    }
                                )
                            )
                            put("checkedAtUtc", result.checkedAtUtc)
                            if (inactiveAccount) {
                                put("errorCode", "XRPL_ACCOUNT_NOT_ACTIVATED")
                                put("errorTitle", "XRPL 계정 활성화 필요")
                                put("errorHint", "이 주소로 XRP를 입금한 뒤 거래 내역을 다시 조회하세요.")
                            }
                            result.error?.let { put("error", it) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "getWalletTransactions failed", e)
                emitCallback("GET_WALLET_TRANSACTIONS", false) {
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun copyWalletAddress(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            try {
                // Check WebView URL on Main thread
                requireTrustedBridgeOrigin("COPY_WALLET_ADDRESS")

                val walletState = walletStateStore.requireWalletState()
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("KYvC Holder Address", walletState.account))
                Toast.makeText(context, "Holder address copied", Toast.LENGTH_SHORT).show()
                emitCallback("COPY_WALLET_ADDRESS", true) {
                    put("account", walletState.account)
                    put("copied", true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "copyWalletAddress failed", e)
                emitCallback("COPY_WALLET_ADDRESS", false) {
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun submitXrpPayment(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            try {
                // Check WebView URL on Main thread
                requireTrustedBridgeOrigin("SUBMIT_XRP_PAYMENT")

                withContext(Dispatchers.IO) {
                    require(!appLockStore.isEmailVerificationRequired()) {
                        "이메일 인증이 필요한 상태에서는 송금을 진행할 수 없습니다."
                    }
                    require(appLockStore.isSessionUnlocked()) {
                        "활성 인증 세션이 필요합니다. 먼저 네이티브 인증을 완료하세요."
                    }
                    require(appLockStore.consumeSensitiveActionAuthorization(SENSITIVE_REASON_XRP_PAYMENT)) {
                        "송금 전 재인증이 필요합니다."
                    }
                    val request = parseJsonObjectOrEmpty(jsonPayload)
                    val destinationAddress = request.text("destinationAddress")
                        ?: request.text("destination")
                        ?: throw IllegalArgumentException("destinationAddress is required")
                    val amountDrops = request.text("amountDrops")
                    val amountXrp = request.text("amountXrp")
                    require(!amountDrops.isNullOrBlank() || !amountXrp.isNullOrBlank()) {
                        "amountDrops or amountXrp is required"
                    }
                    val resolvedAmountDrops = amountDrops?.trim()?.takeIf { it.isNotBlank() }
                        ?: xrpAmountToDrops(amountXrp!!)
                    val walletState = walletStateStore.requireWalletState()
                    val seed = walletStateStore.requireSeed()
                    currentSeed = seed
                    val result = xrplHelper.submitXrpPayment(
                        seed = seed,
                        destinationAddress = destinationAddress,
                        amountDrops = amountDrops,
                        amountXrp = amountXrp
                    )
                    val engineResult = result.engineResult().toString()
                    val txHash = result.transactionResult().hash().value()
                    val success = engineResult == "tesSUCCESS"
                    withContext(Dispatchers.Main) {
                        emitCallback("SUBMIT_XRP_PAYMENT", success) {
                            put("sourceAccount", walletState.account)
                            put("destinationAddress", destinationAddress)
                            amountDrops?.let { put("requestedAmountDrops", it) }
                            amountXrp?.let { put("requestedAmountXrp", it) }
                            put("amountDrops", resolvedAmountDrops)
                            put("amountXrp", dropsToXrp(resolvedAmountDrops))
                            put("txHash", txHash)
                            put("engineResult", engineResult)
                            put("engineResultMessage", result.engineResultMessage())
                            if (!success) {
                                put("error", result.engineResultMessage())
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "submitXrpPayment failed", e)
                emitCallback("SUBMIT_XRP_PAYMENT", false) {
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun exportWalletSeed(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            val request = parseJsonObjectOrEmpty(jsonPayload)
            try {
                requireTrustedBridgeOrigin("EXPORT_WALLET_SEED")
                
                withContext(Dispatchers.IO) {
                    validateBridgeRequest(
                        request = request,
                        allowedActions = setOf("EXPORT_WALLET_SEED"),
                        ttlSeconds = 30
                    )
                    require(!appLockStore.isEmailVerificationRequired()) {
                        "이메일 인증이 필요한 상태에서는 seed를 내보낼 수 없습니다."
                    }
                    require(appLockStore.isSessionUnlocked()) {
                        "활성 인증 세션이 필요합니다. 먼저 네이티브 인증을 완료하세요."
                    }
                    val walletState = walletStateStore.requireWalletState()
                    val seedValue = walletStateStore.exportSeedValue()
                    
                    withContext(Dispatchers.Main) {
                        emitCallback("EXPORT_WALLET_SEED", true) {
                            request.text("requestId")?.let { put("requestId", it) }
                            put("account", walletState.account)
                            put("did", walletState.did)
                            put("seed", seedValue)
                            put("warning", "이 값은 민감정보입니다. 화면/로그/원격 전송에 남기지 마세요.")
                            putAuthAttemptState()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "exportWalletSeed failed", e)
                emitCallback("EXPORT_WALLET_SEED", false) {
                    request.text("requestId")?.let { put("requestId", it) }
                    putAuthAttemptState()
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun logout(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            val request = parseJsonObjectOrEmpty(jsonPayload)
            try {
                requireTrustedBridgeOrigin("LOGOUT")
                validateBridgeRequest(
                    request = request,
                    allowedActions = setOf("LOGOUT"),
                    ttlSeconds = 30
                )
                appLockStore.clearSession()
                walletStateStore.clearActiveWalletSelection()
                currentSeed = null
                onSessionStatusChanged(false)
                emitCallback("LOGOUT", true) {
                    request.text("requestId")?.let { put("requestId", it) }
                    put("walletDisconnected", true)
                    putAuthAttemptState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "logout failed", e)
                emitCallback("LOGOUT", false) {
                    request.text("requestId")?.let { put("requestId", it) }
                    putAuthAttemptState()
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun copyTextToClipboard(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            val request = parseJsonObjectOrEmpty(jsonPayload)
            try {
                // Check WebView URL on Main thread
                requireTrustedBridgeOrigin("COPY_TEXT_TO_CLIPBOARD")

                val text = request.text("text") ?: throw IllegalArgumentException("text is required")
                val label = request.text("label") ?: "KYvC"
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
                emitCallback("COPY_TEXT_TO_CLIPBOARD", true) {
                    request.text("requestId")?.let { put("requestId", it) }
                    put("label", label)
                    put("copied", true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "copyTextToClipboard failed", e)
                emitCallback("COPY_TEXT_TO_CLIPBOARD", false) {
                    request.text("requestId")?.let { put("requestId", it) }
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun restoreWallet(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            try {
                // Check WebView URL on Main thread
                requireTrustedBridgeOrigin("RESTORE_WALLET")

                withContext(Dispatchers.IO) {
                    val request = parseJsonObjectOrEmpty(jsonPayload)
                    val seedValue = request.text("seed") ?: request.text("holderSeed")
                    val mnemonicValue = request.text("mnemonic")
                    val authPrivateKeyHex = request.text("authPrivateKeyHex")
                    val overwrite = request.text("overwrite")?.toBooleanStrictOrNull() ?: false
                    val restoredWithExistingAuthKey = !authPrivateKeyHex.isNullOrBlank()
                    val accountsBefore = walletStateStore.listWallets().map { it.account }.toSet()
                    
                    val walletState = when {
                        !mnemonicValue.isNullOrBlank() -> {
                            walletStateStore.restoreWalletWithMnemonic(
                                mnemonicString = mnemonicValue,
                                overwrite = overwrite,
                                authPrivateKeyHex = authPrivateKeyHex
                            )
                        }
                        !seedValue.isNullOrBlank() -> {
                            walletStateStore.restoreWallet(
                                seedValue = seedValue,
                                overwrite = overwrite,
                                authPrivateKeyHex = authPrivateKeyHex
                            )
                        }
                        else -> throw IllegalArgumentException("seed or mnemonic is required")
                    }
                    val reusedExistingAccount = !overwrite && walletState.account in accountsBefore
                    val holderDidSetRegistrationRequired = !reusedExistingAccount && !restoredWithExistingAuthKey
                    
                    currentSeed = walletStateStore.requireSeed()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Wallet restored: ${walletState.account}", Toast.LENGTH_SHORT).show()
                        emitCallback("RESTORE_WALLET", true) {
                            put("restored", true)
                            put("reusedExistingAccount", reusedExistingAccount)
                            put("restoredWithExistingAuthKey", restoredWithExistingAuthKey)
                            put("holderDidSetRegistrationRequired", holderDidSetRegistrationRequired)
                            put(
                                "warning",
                                if (reusedExistingAccount) {
                                    "Existing wallet entry reused. Holder DIDSet 재등록은 필요하지 않습니다."
                                } else if (restoredWithExistingAuthKey) {
                                    "Seed와 holder auth key를 함께 복구했습니다. 기존 DID Document를 그대로 유지할 수 있습니다."
                                } else {
                                    "Holder auth key was regenerated. Re-register the holder DIDSet before verifier submission."
                                }
                            )
                            put("account", walletState.account)
                            put("publicKey", walletState.publicKey)
                            put("authPublicKey", walletState.authPublicKey)
                            put("did", walletState.did)
                            walletState.mnemonic?.let { put("mnemonic", it) }
                            put("didDocument", buildHolderDidDocument(walletState))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "restoreWallet failed", e)
                Toast.makeText(context, "Failed to restore wallet: ${e.message}", Toast.LENGTH_LONG).show()
                emitCallback("RESTORE_WALLET", false) {
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun exportWalletMnemonic(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            val request = parseJsonObjectOrEmpty(jsonPayload)
            try {
                requireTrustedBridgeOrigin("EXPORT_WALLET_MNEMONIC")

                withContext(Dispatchers.IO) {
                    validateBridgeRequest(
                        request = request,
                        allowedActions = setOf("EXPORT_WALLET_MNEMONIC"),
                        ttlSeconds = 30
                    )
                    require(!appLockStore.isEmailVerificationRequired()) {
                        "이메일 인증이 필요한 상태에서는 비밀문구를 내보낼 수 없습니다."
                    }
                    require(appLockStore.isSessionUnlocked()) {
                        "활성 인증 세션이 필요합니다. 먼저 네이티브 인증을 완료하세요."
                    }
                    val walletState = walletStateStore.requireWalletState()
                    val mnemonicValue = walletStateStore.exportMnemonicValue()

                    withContext(Dispatchers.Main) {
                        if (mnemonicValue.isBlank()) {
                            emitCallback("EXPORT_WALLET_MNEMONIC", false) {
                                request.text("requestId")?.let { put("requestId", it) }
                                putAuthAttemptState()
                                put("error", "현재 지갑에 저장된 비밀문구가 없습니다. 시드(Seed)로 복구했거나 구형 지갑인 경우 비밀문구가 존재하지 않습니다.")
                            }
                        } else {
                            emitCallback("EXPORT_WALLET_MNEMONIC", true) {
                                request.text("requestId")?.let { put("requestId", it) }
                                put("account", walletState.account)
                                put("did", walletState.did)
                                put("mnemonic", mnemonicValue)
                                put("warning", "이 값은 민감정보입니다. 화면/로그/원격 전송에 남기지 마세요.")
                                putAuthAttemptState()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "exportWalletMnemonic failed", e)
                emitCallback("EXPORT_WALLET_MNEMONIC", false) {
                    request.text("requestId")?.let { put("requestId", it) }
                    putAuthAttemptState()
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun submitHolderDidSet(jsonPayload: String) {
        scope.launch(Dispatchers.Main) {
            try {
                // Check WebView URL on Main thread
                requireTrustedBridgeOrigin("SUBMIT_HOLDER_DID_SET")

                withContext(Dispatchers.IO) {
                    val request = parseJsonObjectOrEmpty(jsonPayload)
                    val walletState = walletStateStore.requireWalletState()
                    val accountPrecheck = xrplHelper.getAccountAssets(walletState.account)
                    require(accountPrecheck.error == null) {
                        "holder account is not activated on XRPL testnet. Deposit XRP to this address first: ${walletState.account}"
                    }
                    val seed = walletStateStore.requireSeed()
                    currentSeed = seed
                    val didDocument = buildHolderDidDocument(walletState)
                    val dataHash = didDocumentDataHash(didDocument)
                    val didDocumentUri = request.text("didDocumentUri")
                        ?: request.text("uri")
                        ?: "kyvc:holder:${walletState.account}:diddoc.json"
                    val result = xrplHelper.submitDidSet(
                        seed = seed,
                        didDocumentUri = didDocumentUri,
                        didDocumentDataHashHex = dataHash
                    )
                    val txHash = result.transactionResult().hash().value()
                    val engineResult = result.engineResult()
                    val ledgerApplied = engineResult == "tesSUCCESS"
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Holder DIDSet submitted: $engineResult", Toast.LENGTH_LONG).show()
                        emitCallback("SUBMIT_HOLDER_DID_SET", ledgerApplied) {
                            put("holderAccount", walletState.account)
                            put("holderDid", walletState.did)
                            put("didDocumentUri", didDocumentUri)
                            put("dataHash", dataHash)
                            put("didDocument", Json.parseToJsonElement(didDocument))
                            put("txHash", txHash)
                            put("engineResult", engineResult)
                            put("engineResultMessage", result.engineResultMessage())
                            put("accepted", result.accepted())
                            put("applied", result.applied())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "submitHolderDidSet failed", e)
                Toast.makeText(context, "Holder DIDSet failed: ${e.message}", Toast.LENGTH_LONG).show()
                emitCallback("SUBMIT_HOLDER_DID_SET", false) {
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    @JavascriptInterface
    fun saveVC(jsonPayload: String) {
        scope.launch(Dispatchers.IO) {
            val request = parseJsonObjectOrEmpty(jsonPayload)
            val requestId = request.text("requestId")
            try {
                val metadata = request.obj("metadata") ?: buildJsonObject { }
                logBridgeFlow(
                    "vc.issue.saveVC.received",
                    mapOf(
                        "requestId" to requestId,
                        "credentialId" to (request.text("credentialId") ?: metadata.text("credentialId")),
                        "hasMetadata" to metadata.isNotEmpty(),
                        "hasSdJwt" to !firstText(request, "sdJwt", "sd_jwt").isNullOrBlank(),
                        "hasCredentialJwt" to !firstText(request, "credentialJwt", "credential_jwt").isNullOrBlank(),
                        "hasVcJwt" to !firstText(request, "vcJwt", "vc_jwt").isNullOrBlank(),
                        "hasVcJson" to !request.text("vcJson").isNullOrBlank(),
                        "hasCredentialText" to !request.text("credential").isNullOrBlank(),
                        "hasCredentialObject" to (request.obj("credential") != null),
                        "hasCredentialPayload" to (!request.text("credentialPayload").isNullOrBlank() || request.obj("credentialPayload") != null),
                        "payloadLen" to jsonPayload.length,
                        "payloadHash" to shortSha256(jsonPayload)
                    )
                )
                val envelope = resolveSaveCredentialEnvelope(request, jsonPayload)
                val normalizedCredential = normalizeCredentialPayloadForSave(
                    payload = envelope.payload,
                    request = request,
                    metadata = metadata,
                    walletState = walletStateStore.getWalletStateOrNull()
                )
                logBridgeFlow(
                    "vc.issue.saveVC.normalized",
                    mapOf(
                        "requestId" to requestId,
                        "format" to envelope.format,
                        "rawCredentialLen" to envelope.rawCredential.length,
                        "rawCredentialHash" to shortSha256(envelope.rawCredential),
                        "credentialId" to (firstText(request, "credentialId")
                            ?: firstText(metadata, "credentialId")
                            ?: firstText(normalizedCredential, "credentialId", "id", "jti")),
                        "issuerDid" to (normalizedCredential.text("issuerDid") ?: normalizedCredential.text("issuer")),
                        "issuerAccount" to normalizedCredential.text("issuerAccount"),
                        "holderDid" to normalizedCredential.text("holderDid"),
                        "holderAccount" to normalizedCredential.text("holderAccount"),
                        "credentialType" to normalizedCredential.text("credentialType"),
                        "hasStatus" to (normalizedCredential.obj("credentialStatus") != null),
                        "hasSubject" to (normalizedCredential.obj("credentialSubject") != null)
                    )
                )
                validateCredentialJwt(envelope, issuerDidDocument = null)
                validateCredentialAgainstWallet(normalizedCredential, walletStateStore.getWalletStateOrNull())
                val issuerProof = verifyIssuerProof(normalizedCredential)
                require(!issuerProof.supported || issuerProof.verified) {
                    issuerProof.error ?: "issuer proof verification failed"
                }
                val status = normalizedCredential.obj("credentialStatus")
                val subject = normalizedCredential.obj("credentialSubject")
                val issuerDid = normalizedCredential.text("issuerDid") ?: normalizedCredential.text("issuer").orEmpty()
                val holderDid = normalizedCredential.text("holderDid") ?: subject?.text("id").orEmpty()
                val entity = CredentialEntity(
                    credentialId = firstText(request, "credentialId")
                        ?: firstText(metadata, "credentialId")
                        ?: firstText(normalizedCredential, "credentialId", "id", "jti")
                        ?: "",
                    vcJson = envelope.rawCredential,
                    issuerDid = issuerDid,
                    issuerAccount = firstText(metadata, "issuerAccount")
                        ?: firstText(request, "issuerAccount", "issuerAddress", "issuer_account")
                        ?: normalizedCredential.text("issuerAccount")
                        ?: status?.text("issuer")
                        ?: accountFromDid(issuerDid),
                    holderDid = holderDid,
                    holderAccount = firstText(metadata, "holderXrplAddress", "holderAccount", "holder_account")
                        ?: firstText(request, "holderXrplAddress", "holderAccount", "holder_account")
                        ?: normalizedCredential.text("holderAccount")
                        ?: status?.text("subject")
                        ?: accountFromDid(holderDid),
                    credentialType = firstText(metadata, "credentialType")
                        ?: firstText(request, "credentialType", "credentialTypeHex", "credential_type")
                        ?: normalizedCredential.text("credentialType")
                        ?: status?.text("credentialType")
                        ?: "KYC_CREDENTIAL",
                    vcCoreHash = firstText(metadata, "vcHash", "vcCoreHash")
                        ?: firstText(request, "vcHash", "vcCoreHash")
                        ?: normalizedCredential.text("vcCoreHash")
                        ?: status?.text("vcCoreHash")
                        ?: computeVcCoreHash(normalizedCredential),
                    validFrom = firstText(metadata, "issuedAt", "validFrom")
                        ?: firstText(normalizedCredential, "validFrom", "issuanceDate", "issuedAt")
                        ?: nowUtcIso(),
                    validUntil = firstText(metadata, "expiresAt", "validUntil", "expirationDate")
                        ?: firstText(normalizedCredential, "validUntil", "expirationDate", "expiresAt")
                        ?: ""
                )
                require(entity.credentialId.isNotBlank()) { "credentialId or id is required" }
                require(entity.issuerAccount.isNotBlank()) { "issuerAccount is required" }
                require(entity.holderAccount.isNotBlank()) { "holderAccount is required" }
                require(entity.credentialType.isNotBlank()) { "credentialType is required" }
                credentialRepository.insertCredential(entity)
                logBridgeFlow(
                    "vc.issue.saveVC.saved",
                    mapOf(
                        "requestId" to requestId,
                        "credentialId" to entity.credentialId,
                        "issuerAccount" to entity.issuerAccount,
                        "holderAccount" to entity.holderAccount,
                        "credentialType" to entity.credentialType,
                        "validFrom" to entity.validFrom,
                        "validUntil" to entity.validUntil,
                        "format" to envelope.format
                    )
                )
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "VC Saved Successfully", Toast.LENGTH_SHORT).show()
                    emitCallback("SAVE_VC", true) {
                        requestId?.let { put("requestId", it) }
                        put("credentialId", entity.credentialId)
                        put("issuerDid", entity.issuerDid)
                        put("issuerAccount", entity.issuerAccount)
                        put("holderDid", entity.holderDid)
                        put("holderAccount", entity.holderAccount)
                        put("credentialType", entity.credentialType)
                        put("saved", true)
                        envelope.vcJwt?.let { put("vcJwt", it) }
                        envelope.sdJwt?.let { put("sdJwt", it) }
                        put("format", envelope.format)
                    }
                }
            } catch (e: Exception) {
                logBridgeFailure("vc.issue.saveVC.failed", requestId, e)
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to save VC: ${e.message}", Toast.LENGTH_LONG).show()
                    emitCallback("SAVE_VC", false) {
                        requestId?.let { put("requestId", it) }
                        put("error", e.message ?: "Unknown error")
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun registerDocumentEvidence(jsonPayload: String) {
        scope.launch(Dispatchers.IO) {
            try {
                requireTrustedBridgeOrigin("REGISTER_DOCUMENT_EVIDENCE")
                val request = Json.parseToJsonElement(jsonPayload).jsonObject
                val documentId = request.text("documentId")
                    ?: throw IllegalArgumentException("documentId is required")
                val documentType = request.text("documentType")
                    ?: throw IllegalArgumentException("documentType is required")
                val digestSRI = request.text("digestSRI")
                    ?: throw IllegalArgumentException("digestSRI is required")
                val mediaType = request.text("mediaType")
                    ?: throw IllegalArgumentException("mediaType is required")
                val byteSize = request.long("byteSize")
                    ?: throw IllegalArgumentException("byteSize is required")
                val hashInput = request.text("hashInput") ?: "original-file-bytes"
                val localPath = request.text("localPath")
                    ?: throw IllegalArgumentException("localPath is required")
                val sourceFile = File(localPath)
                require(sourceFile.exists() && sourceFile.isFile) { "localPath file not found" }

                val rawBytes = sourceFile.readBytes()
                require(rawBytes.size.toLong() == byteSize) {
                    "byteSize mismatch: evidence=$byteSize, file=${rawBytes.size}"
                }
                val recomputed = computeSha384SRI(rawBytes)
                require(sriEquals(recomputed, digestSRI)) {
                    "digestSRI mismatch: evidence=$digestSRI, file=$recomputed"
                }
                val storedBlob = secureDocumentStore.encryptAndStore(rawBytes, request.text("filename") ?: sourceFile.name)
                val credentialId = request.text("credentialId")
                val sdJwtJti = request.text("sdJwtJti") ?: credentialId
                val evidenceFor = request["evidenceFor"] as? JsonArray ?: JsonArray(emptyList())

                val entity = HolderDocumentEntity(
                    documentId = documentId,
                    documentType = documentType,
                    digestSRI = normalizeSri(digestSRI),
                    mediaType = mediaType,
                    byteSize = byteSize,
                    hashInput = hashInput,
                    encryptedBlobPath = storedBlob.blobPath,
                    originalFilename = storedBlob.originalFilename,
                    createdAt = nowUtcIso(),
                    importedAt = nowUtcIso(),
                    credentialId = credentialId,
                    sdJwtJti = sdJwtJti,
                    evidenceForJson = evidenceFor.toString()
                )
                holderDocumentRepository.upsert(entity)

                withContext(Dispatchers.Main) {
                    emitCallback("REGISTER_DOCUMENT_EVIDENCE", true) {
                        put("documentId", documentId)
                        put("documentType", documentType)
                        put("digestSRI", normalizeSri(digestSRI))
                        put("mediaType", mediaType)
                        put("byteSize", byteSize)
                        put("credentialId", credentialId.orEmpty())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "registerDocumentEvidence failed", e)
                withContext(Dispatchers.Main) {
                    emitCallback("REGISTER_DOCUMENT_EVIDENCE", false) {
                        put("errorCode", "DOC_STORE_FAILED")
                        put("errorTitle", "문서 저장 실패")
                        put("error", e.message ?: "Unknown error")
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun checkCredentialStatus(jsonPayload: String) {
        scope.launch(Dispatchers.IO) {
            val request = parseJsonObjectOrEmpty(jsonPayload)
            val requestId = request.text("requestId")
            val requestedCredentialId = request.text("credentialId")
            try {
                logBridgeFlow(
                    "vc.issue.status.received",
                    mapOf(
                        "requestId" to requestId,
                        "credentialId" to requestedCredentialId,
                        "hasIssuerAccount" to !firstText(request, "issuerAccount", "issuerAddress", "issuer", "issuer_account").isNullOrBlank(),
                        "hasHolderAccount" to !firstText(request, "holderAccount", "holderXrplAddress", "holder", "subject", "subjectAccount", "holder_account").isNullOrBlank(),
                        "hasCredentialType" to !firstText(request, "credentialType", "credentialTypeHex", "credential_type").isNullOrBlank(),
                        "hasCredentialStatus" to (request.obj("credentialStatus") != null),
                        "hasVcJson" to !request.text("vcJson").isNullOrBlank(),
                        "hasSdJwt" to !firstText(request, "sdJwt", "sd_jwt").isNullOrBlank(),
                        "payloadLen" to jsonPayload.length,
                        "payloadHash" to shortSha256(jsonPayload)
                    )
                )
                val savedCredential = requestedCredentialId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { credentialRepository.getCredentialById(it) }
                val directIssuerAccount = xrplAccountFromText(
                    firstText(request, "issuerAccount", "issuerAddress", "issuer", "issuer_account")
                )
                val directHolderAccount = xrplAccountFromText(
                    firstText(
                        request,
                        "holderAccount",
                        "holderXrplAddress",
                        "holder",
                        "subject",
                        "subjectAccount",
                        "holder_account"
                    )
                )
                val directCredentialType = firstText(request, "credentialType", "credentialTypeHex", "credential_type")
                val walletState = walletStateStore.getWalletStateOrNull()
                val vc = if (
                    directIssuerAccount.isNullOrBlank() ||
                    directHolderAccount.isNullOrBlank() ||
                    directCredentialType.isNullOrBlank()
                ) {
                    Json.parseToJsonElement(resolveVcJson(request)).jsonObject.also {
                        validateCredentialAgainstWallet(it, walletState)
                    }
                } else {
                    null
                }
                val status = request.obj("credentialStatus")
                    ?: request.obj("credential")?.obj("credentialStatus")
                    ?: vc?.obj("credentialStatus")

                val issuerAccount = directIssuerAccount
                    ?: xrplAccountFromText(status?.text("issuer"))
                    ?: savedCredential?.issuerAccount
                    ?: firstText(request, "issuerDid")?.let(::accountFromDid)
                val holderAccount = directHolderAccount
                    ?: xrplAccountFromText(status?.text("subject"))
                    ?: savedCredential?.holderAccount
                    ?: walletState?.account
                    ?: throw IllegalArgumentException("holderAccount is required")
                val credentialType = directCredentialType
                    ?: status?.text("credentialType")
                    ?: savedCredential?.credentialType
                    ?: throw IllegalArgumentException("credentialType is required")

                require(!issuerAccount.isNullOrBlank()) { "issuerAccount is required" }
                logBridgeFlow(
                    "vc.issue.status.query",
                    mapOf(
                        "requestId" to requestId,
                        "credentialId" to (savedCredential?.credentialId ?: requestedCredentialId),
                        "savedCredentialFound" to (savedCredential != null),
                        "issuerAccount" to issuerAccount,
                        "holderAccount" to holderAccount,
                        "credentialType" to credentialType
                    )
                )
                walletState?.let { activeWallet ->
                    require(holderAccount == activeWallet.account) {
                        "holder account mismatch: wallet=${activeWallet.account}, request=$holderAccount"
                    }
                }

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
                logBridgeFlow(
                    "vc.issue.status.result",
                    mapOf(
                        "requestId" to requestId,
                        "credentialId" to (savedCredential?.credentialId ?: requestedCredentialId),
                        "found" to result.found,
                        "accepted" to result.accepted,
                        "active" to result.active,
                        "credentialIndex" to result.credentialIndex,
                        "flags" to result.flags,
                        "expiration" to result.expiration,
                        "error" to result.error
                    )
                )

                withContext(Dispatchers.Main) {
                    emitCallback("CHECK_CREDENTIAL_STATUS", true) {
                        requestId?.let { put("requestId", it) }
                        put("credentialId", savedCredential?.credentialId ?: requestedCredentialId.orEmpty())
                        put("credentialIndex", result.credentialIndex)
                        put("found", result.found)
                        put("credentialEntryFound", result.found)
                        put("active", result.active)
                        put("accepted", result.accepted)
                        put("credentialAccepted", result.accepted)
                        put("issuerAccount", result.issuerAccount)
                        put("holderAccount", result.holderAccount)
                        put("credentialType", result.credentialType)
                        put("checkedAtUtc", result.checkedAtUtc)
                        savedCredential?.credentialAcceptHash?.let { put("txHash", it) }
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
                logBridgeFailure("vc.issue.status.failed", requestId, e, mapOf("credentialId" to requestedCredentialId))
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Credential status check failed: ${e.message}", Toast.LENGTH_LONG).show()
                    emitCallback("CHECK_CREDENTIAL_STATUS", false) {
                        requestId?.let { put("requestId", it) }
                        requestedCredentialId?.let { put("credentialId", it) }
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
    fun getCredentialSummaries(jsonPayload: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val credentials = credentialRepository.getAllCredentialsOnce()
                withContext(Dispatchers.Main) {
                    emitCallback("GET_CREDENTIAL_SUMMARIES", true) {
                        put("count", credentials.size)
                        put(
                            "credentials",
                            JsonArray(
                                credentials.map { credential ->
                                    buildCredentialSummary(credential)
                                }
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "getCredentialSummaries failed", e)
                withContext(Dispatchers.Main) {
                    emitCallback("GET_CREDENTIAL_SUMMARIES", false) {
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
        scope.launch(Dispatchers.Main) {
            val request = parseJsonObjectOrEmpty(jsonPayload)
            try {
                requireTrustedBridgeOrigin("SUBMIT_TO_XRPL")
                logBridgeFlow(
                    "vc.issue.xrpl.received",
                    mapOf(
                        "requestId" to request.text("requestId"),
                        "credentialId" to request.text("credentialId"),
                        "hasIssuerAccount" to !firstText(request, "issuerAccount", "issuerAddress", "issuer", "issuer_account").isNullOrBlank(),
                        "hasHolderAccount" to !firstText(request, "holderAccount", "holderXrplAddress", "holder", "subject", "subjectAccount", "holder_account").isNullOrBlank(),
                        "hasCredentialType" to !firstText(request, "credentialType", "credentialTypeHex", "credential_type").isNullOrBlank(),
                        "hasCredentialStatus" to (request.obj("credentialStatus") != null),
                        "hasDirectVc" to (request.obj("credential") != null || !request.text("vcJson").isNullOrBlank() || !request.text("sdJwt").isNullOrBlank()),
                        "payloadLen" to jsonPayload.length,
                        "payloadHash" to shortSha256(jsonPayload)
                    )
                )

                withContext(Dispatchers.IO) {
                    val seed = walletStateStore.requireSeed()
                    currentSeed = seed
                    val walletState = walletStateStore.requireWalletState()

                    val savedCredential = request.text("credentialId")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { credentialRepository.getCredentialById(it) }
                    val hasDirectLedgerFields = !firstText(
                        request,
                        "issuerAccount",
                        "issuerAddress",
                        "issuer",
                        "issuer_account"
                    ).isNullOrBlank() &&
                        !firstText(
                            request,
                            "holderAccount",
                            "holderXrplAddress",
                            "holder",
                            "subject",
                            "subjectAccount",
                            "holder_account"
                        ).isNullOrBlank() &&
                        !firstText(
                            request,
                            "credentialType",
                            "credentialTypeHex",
                            "credential_type"
                        ).isNullOrBlank()
                    val vcObject = if (hasDirectLedgerFields) {
                        null
                    } else {
                        Json.parseToJsonElement(resolveVcJson(request)).jsonObject.also {
                            validateCredentialAgainstWallet(it, walletStateStore.getWalletStateOrNull())
                        }
                    }
                    val vcStatus = request.obj("credentialStatus")
                        ?: request.obj("credential")?.obj("credentialStatus")
                        ?: vcObject?.obj("credentialStatus")

                    val issuerAccount = xrplAccountFromText(
                        firstText(request, "issuerAccount", "issuerAddress", "issuer", "issuer_account")
                            ?: vcStatus?.text("issuer")
                            ?: savedCredential?.issuerAccount
                            ?: firstText(request, "issuerDid")?.let(::accountFromDid)
                    )
                    val credentialType = firstText(request, "credentialType", "credentialTypeHex", "credential_type")
                        ?: vcStatus?.text("credentialType")
                        ?: savedCredential?.credentialType
                    val expectedHolderAccount = xrplAccountFromText(
                        firstText(
                            request,
                            "holderAccount",
                            "holderXrplAddress",
                            "holder",
                            "subject",
                            "subjectAccount",
                            "holder_account"
                        )
                            ?: vcStatus?.text("subject")
                            ?: savedCredential?.holderAccount
                            ?: walletState.account
                    )

                    require(!issuerAccount.isNullOrBlank()) { "issuerAccount is required" }
                    require(!credentialType.isNullOrBlank()) { "credentialType is required" }

                    val holderAccount = seed.deriveKeyPair().publicKey().deriveAddress().value()
                    logBridgeFlow(
                        "vc.issue.xrpl.resolved",
                        mapOf(
                            "requestId" to request.text("requestId"),
                            "credentialId" to (savedCredential?.credentialId ?: request.text("credentialId")),
                            "savedCredentialFound" to (savedCredential != null),
                            "hasDirectLedgerFields" to hasDirectLedgerFields,
                            "issuerAccount" to issuerAccount,
                            "holderAccount" to holderAccount,
                            "expectedHolderAccount" to expectedHolderAccount,
                            "credentialType" to credentialType
                        )
                    )
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
                    logBridgeFlow(
                        "vc.issue.xrpl.result",
                        mapOf(
                            "requestId" to request.text("requestId"),
                            "credentialId" to (savedCredential?.credentialId ?: request.text("credentialId")),
                            "txHash" to txHash,
                            "engineResult" to engineResult,
                            "accepted" to result.accepted(),
                            "applied" to result.applied(),
                            "ledgerApplied" to ledgerApplied,
                            "alreadyExists" to alreadyExists
                        )
                    )

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
                            request.text("requestId")?.let { put("requestId", it) }
                            put("credentialId", savedCredential?.credentialId ?: request.text("credentialId").orEmpty())
                            put("holderAccount", holderAccount)
                            put("issuerAccount", issuerAccount)
                            put("credentialType", credentialType)
                            put("txHash", txHash)
                            put("credentialAcceptHash", txHash)
                            put("engineResult", engineResult)
                            put("engineResultMessage", result.engineResultMessage())
                            put("accepted", result.accepted())
                            put("acceptedAt", nowUtcIso())
                            put("applied", result.applied())
                            put("ledgerApplied", ledgerApplied)
                            put("alreadyExists", alreadyExists)
                        }
                    }
                }
            } catch (e: Exception) {
                logBridgeFailure(
                    "vc.issue.xrpl.failed",
                    request.text("requestId"),
                    e,
                    mapOf("credentialId" to request.text("credentialId"))
                )
                emitCallback("SUBMIT_TO_XRPL", false) {
                    request.text("requestId")?.let { put("requestId", it) }
                    request.text("credentialId")?.let { put("credentialId", it) }
                    firstText(request, "issuerAccount", "issuerAddress", "issuer", "issuer_account")?.let {
                        put("issuerAccount", xrplAccountFromText(it) ?: it)
                    }
                    firstText(request, "holderAccount", "holderXrplAddress", "holder", "subject", "subjectAccount", "holder_account")?.let {
                        put("holderAccount", xrplAccountFromText(it) ?: it)
                    }
                    firstText(request, "credentialType", "credentialTypeHex", "credential_type")?.let {
                        put("credentialType", it)
                    }
                    if (isInactiveXrplAccountMessage(e.message.orEmpty())) {
                        put("errorCode", "XRPL_ACCOUNT_NOT_ACTIVATED")
                        put("errorHint", "Fund the holder and issuer accounts on XRPL testnet before CredentialAccept")
                    }
                    put("error", e.message ?: "Unknown error")
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
                    ?: throw IllegalArgumentException("coreBaseUrl is required")
                val holderState = walletStateStore.requireWalletState()
                val holderAccount = request.text("holderAccount") ?: holderState.account
                val holderDid = request.text("holderDid") ?: holderState.did
                val claims = request.obj("claims") ?: buildDefaultSdJwtClaims(request)
                val validFrom = request.text("validFrom") ?: nowUtcIso()
                val validUntil = request.text("validUntil")
                    ?: request.text("expirationDate")
                    ?: Instant.now().plusSeconds(30L * 24L * 60L * 60L).toString()
                val payload = buildJsonObject {
                    put("format", request.text("format") ?: "dc+sd-jwt")
                    put("holder_account", holderAccount)
                    put("holder_did", holderDid)
                    put("holder_key_id", request.text("holderKeyId") ?: request.text("holder_key_id") ?: "holder-key-1")
                    put("claims", claims)
                    put("valid_from", validFrom)
                    put("valid_until", validUntil)
                }.toString()

                val endpoint = resolveBackendEndpoint(baseUrl, request.text("endpoint"), "/issuer/credentials/kyc")
                val responseBody = postJson(endpoint, payload, "Issuer request")
                val response = Json.parseToJsonElement(responseBody).jsonObject
                val credentialValue = response.text("credential")
                    ?: response.text("vc_jwt")
                    ?: response.text("vcJwt")
                    ?: response.text("sdJwt")
                    ?: response.text("sd_jwt")
                val envelope = credentialValue?.let { parseCredentialEnvelope(it) }
                val credential = envelope?.payload
                    ?: response.obj("credential")
                    ?: response.obj("credential_json")
                    ?: response.obj("vc")
                    ?: response.obj("data")
                    ?: response
                envelope?.let {
                    val issuerDid = credential.text("issuer")
                    val issuerAccount = accountFromDid(issuerDid.orEmpty()).takeIf { account -> account.isNotBlank() }
                        ?: credential.obj("credentialStatus")?.text("issuer")
                    val issuerDidDocument = fetchJsonOrNull(
                        resolveBackendEndpoint(baseUrl, null, "/dids/$issuerAccount/diddoc.json")
                    )
                    validateCredentialJwt(
                        it,
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
                        put("format", envelope?.format ?: response.text("format").orEmpty())
                        envelope?.vcJwt?.let { put("vcJwt", it) }
                        envelope?.sdJwt?.let { put("sdJwt", it) }
                        if (envelope != null) {
                            put("disclosureCount", envelope.disclosures.size)
                        }
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
                    ?: throw IllegalArgumentException("coreBaseUrl is required")
                val audience = request.text("aud")
                    ?: request.text("audience")
                    ?: request.text("domain")
                    ?: baseUrl
                val payload = buildJsonObject {
                    put("aud", audience)
                    put("presentationDefinition", request.obj("presentationDefinition") ?: defaultSdJwtPresentationDefinition())
                }.toString()
                val endpoint = resolveBackendEndpoint(baseUrl, request.text("endpoint"), "/verifier/presentations/challenges")
                val responseBody = postJson(endpoint, payload, "Verifier challenge request")
                val response = Json.parseToJsonElement(responseBody).jsonObject
                val challenge = response.text("nonce")
                    ?: response.text("challenge")
                    ?: throw IllegalStateException("nonce or challenge missing")
                val domain = response.text("aud") ?: response.text("domain") ?: audience
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
                        put("nonce", challenge)
                        put("domain", domain)
                        put("aud", domain)
                        put("issuedAt", issuedAt)
                        put("expiresAt", expiresAt)
                        put("endpoint", endpoint)
                        response.obj("presentationDefinition")?.let { put("presentationDefinition", it) }
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
                    ?: throw IllegalArgumentException("coreBaseUrl is required")
                val walletState = walletStateStore.requireWalletState()
                val vcJson = resolveVcJson(request)
                val vcJwt = resolveVcJwt(request)
                val sdJwt = resolveSdJwt(request)
                val credential = Json.parseToJsonElement(vcJson).jsonObject
                val requireStatus = request["require_status"]?.jsonPrimitive?.booleanOrNull ?: true
                val statusMode = request.text("status_mode") ?: "xrpl"
                val didDocuments = buildJsonObject {
                    put(walletState.did, Json.parseToJsonElement(buildHolderDidDocument(walletState)))
                    val issuerDid = credential.text("issuer")
                    val issuerAccount = accountFromDid(issuerDid.orEmpty()).takeIf { account -> account.isNotBlank() }
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
                    if (sdJwt != null) {
                        put("format", "dc+sd-jwt")
                        put("credential", sdJwt)
                    } else if (vcJwt != null) {
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
                val sdJwt = resolveSdJwt(request)
                validateCredentialAgainstWallet(vcObject, walletState)
                ensureVerifierChallengeUsable(challenge, domain)

                if (sdJwt != null) {
                    val sdCredential = parseSdJwtCredential(sdJwt)
                    val selectedDisclosures = selectedSdJwtDisclosures(request, sdCredential.disclosures)
                    val selectedSdJwt = buildSelectedSdJwt(sdCredential.issuerJwt, selectedDisclosures)
                    val selectedDocumentEvidence = extractDocumentEvidenceFromSelectedDisclosures(selectedDisclosures)
                    val attachmentPlan = resolveAttachmentPlan(
                        selectedDocumentEvidence = selectedDocumentEvidence,
                        forceDocumentTypes = request.obj("documentSubmissionPolicy")
                            ?.array("alwaysRequiredDocumentTypes")
                            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                            ?.toSet()
                            ?: emptySet()
                    )
                    val attachmentManifest = JsonArray(
                        attachmentPlan.attachments.map { attachment ->
                            buildJsonObject {
                                put("requirementId", attachment.requirementId)
                                put("documentId", attachment.documentId)
                                put("attachmentRef", attachment.attachmentRef)
                                put("documentType", attachment.documentType)
                                put("digestSRI", attachment.digestSRI)
                                put("mediaType", attachment.mediaType)
                                put("byteSize", attachment.byteSize)
                            }
                        }
                    )
                    val sdHash = base64UrlNoPadding(
                        MessageDigest.getInstance("SHA-256")
                            .digest(selectedSdJwt.toByteArray(Charsets.US_ASCII))
                    )
                    val kbHeader = buildJsonObject {
                        put("alg", "ES256K")
                        put("typ", "kb+jwt")
                        put("kid", "${walletState.did}#holder-key-1")
                    }
                    val kbPayload = buildJsonObject {
                        put("iat", Instant.now().epochSecond)
                        put("aud", domain)
                        put("nonce", challenge)
                        put("sd_hash", sdHash)
                    }
                    val kbJwt = vpSigner.signCompactJws(
                        privateKeyScalar = authPrivateKey,
                        protectedHeaderJson = kbHeader.toString(),
                        payloadJson = kbPayload.toString()
                    )
                    val sdJwtKb = "$selectedSdJwt~$kbJwt"
                    val definitionId = request.text("definitionId")
                        ?: request.obj("presentationDefinition")?.text("id")
                        ?: "kyvc-sd-jwt-presentation-v1"
                    val presentation = buildJsonObject {
                        put("format", "kyvc-sd-jwt-presentation-v1")
                        put("definitionId", definitionId)
                        put("aud", domain)
                        put("nonce", challenge)
                        put("sdJwtKb", sdJwtKb)
                        put("attachmentManifest", attachmentManifest)
                    }
                    val didDocument = buildHolderDidDocument(walletState)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "SD-JWT KB presentation signed", Toast.LENGTH_SHORT).show()
                        emitCallback("SIGN_MESSAGE", true) {
                            put("format", "kyvc-sd-jwt-presentation-v1")
                            put("holder", walletState.did)
                            put("challenge", challenge)
                            put("nonce", challenge)
                            put("domain", domain)
                            put("aud", domain)
                            put("sdHash", sdHash)
                            put("sdJwtKb", sdJwtKb)
                            put("presentation", presentation)
                            put("didDocument", Json.parseToJsonElement(didDocument))
                            put("disclosureCount", sdCredential.disclosures.size)
                            put("selectedDisclosureCount", selectedDisclosures.size)
                            put("attachmentManifest", attachmentManifest)
                            put("attachmentReady", attachmentPlan.blockers.isEmpty())
                            if (attachmentPlan.blockers.isNotEmpty()) {
                                put("attachmentBlockers", JsonArray(attachmentPlan.blockers.map { JsonPrimitive(it) }))
                                put("errorCode", "ATTACHMENT_MISSING")
                            }
                        }
                    }
                    return@launch
                }

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
                val sdJwtKb = request.text("sdJwtKb") ?: presentation.text("sdJwtKb")
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
                if (!sdJwtKb.isNullOrBlank() || presentation.text("format") == "kyvc-sd-jwt-presentation-v1") {
                    val nonce = request.text("nonce")
                        ?: request.text("challenge")
                        ?: presentation.text("nonce")
                        ?: throw IllegalArgumentException("presentation.nonce is required")
                    val aud = request.text("aud")
                        ?: request.text("domain")
                        ?: presentation.text("aud")
                        ?: throw IllegalArgumentException("presentation.aud is required")
                    ensureVerifierChallengeUsable(nonce, aud)
                    if (requireStatus) {
                        requireActiveCredentialStatus(vcObject, walletState)
                    }
                    val attachmentManifestInput = presentation["attachmentManifest"] as? JsonArray ?: JsonArray(emptyList())
                    val attachmentPlan = prepareAttachmentSubmission(attachmentManifestInput)
                    if (attachmentPlan.blockers.isNotEmpty()) {
                        throw IllegalStateException("document attachment required but unavailable: ${attachmentPlan.blockers.joinToString("; ")}")
                    }
                    val requestBody = buildJsonObject {
                        put("format", "kyvc-sd-jwt-presentation-v1")
                        put(
                            "presentation",
                            buildJsonObject {
                                put("format", "kyvc-sd-jwt-presentation-v1")
                                presentation.text("definitionId")?.let { put("definitionId", it) }
                                put("aud", aud)
                                put("nonce", nonce)
                                if (!sdJwtKb.isNullOrBlank()) {
                                    put("sdJwtKb", sdJwtKb)
                                } else {
                                    put("sdJwtKb", presentation.text("sdJwtKb").orEmpty())
                                }
                                put("attachmentManifest", attachmentPlan.manifestJson)
                            }
                        )
                        put(
                            "did_documents",
                            buildJsonObject {
                                put(walletState.did, didDocument)
                            }
                        )
                        put("require_status", requireStatus)
                        put("status_mode", statusMode)
                    }

                    val response = if (attachmentPlan.attachments.isEmpty()) {
                        postJson(endpoint, requestBody.toString())
                    } else {
                        postMultipart(
                            endpoint = endpoint,
                            presentationPayload = requestBody,
                            attachments = attachmentPlan.attachments
                        )
                    }
                    val responseJson = runCatching { JSONObject(response) }.getOrNull()
                    val ok = responseJson?.optBoolean("ok") == true
                    val errors = responseJson?.optJSONArray("errors")
                    val details = responseJson?.optJSONObject("details")
                    val failureInfo = if (ok) null else classifyVerifierFailure(responseJson, null)
                    if (ok) {
                        markVerifierChallengeUsed(nonce)
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, if (ok) "Verifier submission completed" else "Verifier submission failed", Toast.LENGTH_LONG).show()
                        emitCallback("SUBMIT_TO_VERIFIER", ok) {
                            put("format", "kyvc-sd-jwt-presentation-v1")
                            put("endpoint", endpoint)
                            put("status_mode", statusMode)
                            put("require_status", requireStatus)
                            put("attachmentCount", attachmentPlan.attachments.size)
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
                    return@launch
                }
                val challenge = presentation.obj("proof")?.text("challenge")
                    ?: throw IllegalArgumentException("presentation.proof.challenge is required")

                ensurePresentationMatchesWallet(presentation, didDocument, walletState)
                ensurePresentationContainsCredential(presentation, vcObject)
                ensureVerifierChallengeUsable(challenge, request.text("domain"))

                if (requireStatus) {
                    requireActiveCredentialStatus(vcObject, walletState)
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
        scanQRCodeInternal(
            jsonPayload = jsonPayload,
            forcedActionType = null,
            callbackAction = "SCAN_QR_CODE",
            qrMode = null
        )
    }

    @JavascriptInterface
    fun scanIssueQrCode(jsonPayload: String) {
        scanQRCodeInternal(
            jsonPayload = jsonPayload,
            forcedActionType = "VC_ISSUE",
            callbackAction = "SCAN_ISSUE_QR_CODE",
            qrMode = "issue"
        )
    }

    @JavascriptInterface
    fun scanPresentationQrCode(jsonPayload: String) {
        scanQRCodeInternal(
            jsonPayload = jsonPayload,
            forcedActionType = "VP_REQUEST",
            callbackAction = "SCAN_PRESENTATION_QR_CODE",
            qrMode = "presentation"
        )
    }

    private fun scanQRCodeInternal(
        jsonPayload: String,
        forcedActionType: String?,
        callbackAction: String,
        qrMode: String?
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val request = enrichQrScanRequest(
                    request = parseJsonObjectOrEmpty(jsonPayload),
                    forcedActionType = forcedActionType,
                    callbackAction = callbackAction,
                    qrMode = qrMode
                )
                val requestId = request.text("requestId") ?: "qr-${System.currentTimeMillis()}"
                val qrData = request.text("qrData") ?: request.text("data") ?: request.text("text")
                val qrPayload = parseJsonObjectOrEmpty(qrData.orEmpty())
                val qrInfo = buildQrRequestInfo(request, qrPayload, qrData)
                logBridgeFlow(
                    "vc.issue.qr.request",
                    mapOf(
                        "callbackAction" to callbackAction,
                        "requestId" to requestId,
                        "mode" to (qrMode ?: "generic"),
                        "forcedActionType" to forcedActionType,
                        "hasInlineQr" to !qrData.isNullOrBlank(),
                        "qrLen" to (qrData?.length ?: 0),
                        "qrHash" to shortSha256(qrData),
                        "actionType" to qrInfo.actionType,
                        "hasEndpoint" to !qrInfo.endpoint.isNullOrBlank(),
                        "hasCoreBaseUrl" to !qrInfo.coreBaseUrl.isNullOrBlank(),
                        "hasChallenge" to !qrInfo.challenge.isNullOrBlank(),
                        "expiresAt" to qrInfo.expiresAt
                    )
                )
                registerQrChallengeIfPresent(qrInfo)

                withContext(Dispatchers.Main) {
                    emitCallback(callbackAction, true) {
                        put("mode", if (qrData.isNullOrBlank()) "request_received" else "web_supplied")
                        put("requestId", requestId)
                        putQrRequestInfo(qrInfo)
                    }
                    if (qrData.isNullOrBlank()) {
                        logBridgeFlow(
                            "vc.issue.qr.launchNative",
                            mapOf(
                                "callbackAction" to callbackAction,
                                "requestId" to requestId,
                                "mode" to (qrMode ?: "generic"),
                                "actionType" to qrInfo.actionType
                            )
                        )
                        launchQrScanner(request.toString())
                    }
                }
            } catch (e: Exception) {
                logBridgeFailure("vc.issue.qr.request.failed", null, e, mapOf("callbackAction" to callbackAction))
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "QR scan failed: ${e.message}", Toast.LENGTH_LONG).show()
                    emitCallback(callbackAction, false) {
                        put("error", e.message ?: "Unknown error")
                    }
                }
            }
        }
    }

    fun onQrScanResult(requestJson: String?, qrData: String?, errorMessage: String?) {
        scope.launch(Dispatchers.Main) {
            try {
                val request = parseJsonObjectOrEmpty(requestJson ?: "{}")
                val callbackAction = request.text("_callbackAction") ?: "SCAN_QR_CODE"
                if (!errorMessage.isNullOrBlank()) {
                    val requestId = request.text("requestId") ?: "qr-${System.currentTimeMillis()}"
                    val qrInfo = buildQrRequestInfo(request, buildJsonObject { }, null)
                    logBridgeFlow(
                        "vc.issue.qr.cancelled",
                        mapOf(
                            "callbackAction" to callbackAction,
                            "requestId" to requestId,
                            "mode" to (request.text("_qrMode") ?: "generic"),
                            "actionType" to qrInfo.actionType,
                            "error" to errorMessage
                        )
                    )
                    emitCallback(callbackAction, false) {
                        put("requestId", requestId)
                        put("mode", request.text("_qrMode") ?: "cancelled")
                        putQrRequestInfo(qrInfo)
                        put("error", errorMessage)
                        requestJson?.let { put("request", it) }
                    }
                    return@launch
                }

                val requestId = request.text("requestId") ?: "qr-${System.currentTimeMillis()}"
                val qrPayload = parseJsonObjectOrEmpty(qrData.orEmpty())
                val qrInfo = buildQrRequestInfo(request, qrPayload, qrData)
                logBridgeFlow(
                    "vc.issue.qr.scanned",
                    mapOf(
                        "callbackAction" to callbackAction,
                        "requestId" to requestId,
                        "mode" to (request.text("_qrMode") ?: "generic"),
                        "actionType" to qrInfo.actionType,
                        "qrLen" to (qrData?.length ?: 0),
                        "qrHash" to shortSha256(qrData),
                        "hasJsonPayload" to qrPayload.isNotEmpty(),
                        "hasEndpoint" to !qrInfo.endpoint.isNullOrBlank(),
                        "hasCoreBaseUrl" to !qrInfo.coreBaseUrl.isNullOrBlank(),
                        "hasChallenge" to !qrInfo.challenge.isNullOrBlank(),
                        "expiresAt" to qrInfo.expiresAt
                    )
                )
                registerQrChallengeIfPresent(qrInfo)

                emitCallback(callbackAction, true) {
                    put("mode", "scanned")
                    put("requestId", requestId)
                    putQrRequestInfo(qrInfo)
                }
            } catch (e: Exception) {
                logBridgeFailure("vc.issue.qr.result.failed", null, e)
                emitCallback("SCAN_QR_CODE", false) {
                    put("error", e.message ?: "Unknown error")
                }
            }
        }
    }

    private fun enrichQrScanRequest(
        request: JsonObject,
        forcedActionType: String?,
        callbackAction: String,
        qrMode: String?
    ): JsonObject {
        return buildJsonObject {
            request.forEach { (key, value) -> put(key, value) }
            put("_callbackAction", callbackAction)
            qrMode?.let { put("_qrMode", it) }
            if (forcedActionType != null && request.text("actionType").isNullOrBlank()) {
                put("actionType", forcedActionType)
            }
        }
    }

    fun onNativeAuthResult(
        requestJson: String?,
        method: String?,
        success: Boolean,
        errorMessage: String?
    ) {
        val request = parseJsonObjectOrEmpty(requestJson ?: "{}")
        val requestId = request.text("requestId")
        val resolvedMethod = method ?: request.text("method") ?: "unknown"

        if (!success) {
            emitCallback("REQUEST_NATIVE_AUTH", false) {
                requestId?.let { put("requestId", it) }
                put("method", resolvedMethod)
                request.text("reason")?.let { put("reason", it) }
                put("authenticated", false)
                putAuthAttemptState()
                put("error", errorMessage ?: "Authentication failed")
            }
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                request.text("reason")
                    ?.takeIf { it in SENSITIVE_AUTH_REASONS }
                    ?.let(appLockStore::markSensitiveActionAuthorized)
                
                // Refresh general session on any successful native auth
                appLockStore.markSessionUnlocked()
                
                val backendResponse = performBackendRequestIfPresent(request)
                withContext(Dispatchers.Main) {
                    onSessionStatusChanged(true)
                    emitCallback("REQUEST_NATIVE_AUTH", true) {
                        requestId?.let { put("requestId", it) }
                        put("method", resolvedMethod)
                        request.text("reason")?.let { put("reason", it) }
                        put("authenticated", true)
                        putAuthAttemptState()
                        backendResponse?.json?.let { put("backendResponse", it) }
                        backendResponse?.rawText?.let { put("backendResponseText", it) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onNativeAuthResult backend request failed", e)
                withContext(Dispatchers.Main) {
                    emitCallback("REQUEST_NATIVE_AUTH", false) {
                        requestId?.let { put("requestId", it) }
                        put("method", resolvedMethod)
                        request.text("reason")?.let { put("reason", it) }
                        put("authenticated", true)
                        putAuthAttemptState()
                        put("error", e.message ?: "Backend request failed")
                    }
                }
            }
        }
    }

    fun onPinResetResult(
        requestJson: String?,
        success: Boolean,
        errorMessage: String?
    ) {
        val request = parseJsonObjectOrEmpty(requestJson ?: "{}")
        val requestId = request.text("requestId")

        if (success) {
            onSessionStatusChanged(true)
        }

        emitCallback("REQUEST_PIN_RESET", success) {
            requestId?.let { put("requestId", it) }
            request.text("reason")?.let { put("reason", it) }
            put("reset", success)
            putAuthAttemptState()
            if (!success) {
                put("error", errorMessage ?: "PIN reset cancelled")
            }
        }
    }

    fun onMnemonicBackupResult(
        requestJson: String?,
        confirmed: Boolean,
        errorMessage: String?
    ) {
        val request = parseJsonObjectOrEmpty(requestJson ?: "{}")
        emitCallback("REQUEST_MNEMONIC_BACKUP", confirmed) {
            request.text("requestId")?.let { put("requestId", it) }
            put("confirmed", confirmed)
            putAuthAttemptState()
            if (!confirmed) {
                put("error", errorMessage ?: "사용자가 복구 문구 백업을 취소했습니다.")
            }
        }
    }

    fun submitNativeWalletRestore(
        requestJson: String?,
        mnemonic: String
    ) {
        val request = parseJsonObjectOrEmpty(requestJson ?: "{}")
        scope.launch(Dispatchers.IO) {
            try {
                val normalizedMnemonic = mnemonic.trim().split(Regex("\\s+")).joinToString(" ")
                require(normalizedMnemonic.isNotBlank()) { "mnemonic is required" }
                val overwrite = request.text("overwrite")?.toBooleanStrictOrNull() ?: true
                val autoRegisterDidSet = request.text("autoRegisterDidSet")?.toBooleanStrictOrNull() ?: false
                val accountsBefore = walletStateStore.listWallets().map { it.account }.toSet()
                val walletState = walletStateStore.restoreWalletWithMnemonic(
                    mnemonicString = normalizedMnemonic,
                    overwrite = overwrite
                )
                val reusedExistingAccount = !overwrite && walletState.account in accountsBefore
                val holderDidSetRegistrationRequired = !reusedExistingAccount
                currentSeed = walletStateStore.requireSeed()
                withContext(Dispatchers.Main) {
                    emitCallback("REQUEST_WALLET_RESTORE", true) {
                        request.text("requestId")?.let { put("requestId", it) }
                        put("restored", true)
                        put("reusedExistingAccount", reusedExistingAccount)
                        put("holderDidSetRegistrationRequired", holderDidSetRegistrationRequired)
                        put("autoRegisterDidSet", autoRegisterDidSet)
                        put("warning", if (reusedExistingAccount) {
                            "Existing wallet entry reused. Holder DIDSet 재등록은 필요하지 않습니다."
                        } else {
                            "Holder auth key was regenerated. Re-register the holder DIDSet before verifier submission."
                        })
                        put("account", walletState.account)
                        put("publicKey", walletState.publicKey)
                        put("authPublicKey", walletState.authPublicKey)
                        put("did", walletState.did)
                        put("didDocument", buildHolderDidDocument(walletState))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "submitNativeWalletRestore failed", e)
                withContext(Dispatchers.Main) {
                    emitCallback("REQUEST_WALLET_RESTORE", false) {
                        request.text("requestId")?.let { put("requestId", it) }
                        put("restored", false)
                        put("error", e.message ?: "Unknown error")
                    }
                }
            }
        }
    }

    fun onWalletRestoreCancelled(requestJson: String?) {
        val request = parseJsonObjectOrEmpty(requestJson ?: "{}")
        emitCallback("REQUEST_WALLET_RESTORE", false) {
            request.text("requestId")?.let { put("requestId", it) }
            put("restored", false)
            put("error", "사용자가 지갑 복구를 취소했습니다.")
        }
    }

    private fun JsonObject.text(name: String): String? {
        return (this[name] as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    }

    private fun firstText(source: JsonObject?, vararg names: String): String? {
        if (source == null) return null
        return names.firstNotNullOfOrNull { name -> source.text(name) }
    }

    private fun logBridgeFlow(event: String, fields: Map<String, Any?> = emptyMap()) {
        Log.i(TAG, buildLogMessage(event, fields))
    }

    private fun logBridgeFailure(
        event: String,
        requestId: String?,
        error: Exception,
        fields: Map<String, Any?> = emptyMap()
    ) {
        Log.e(
            TAG,
            buildLogMessage(
                event,
                fields + mapOf(
                    "requestId" to requestId,
                    "errorClass" to error::class.java.simpleName,
                    "error" to error.message
                )
            )
        )
    }

    private fun buildLogMessage(event: String, fields: Map<String, Any?>): String {
        val details = fields.entries.joinToString(" ") { (key, value) ->
            "$key=${sanitizeLogValue(value)}"
        }
        return if (details.isBlank()) event else "$event $details"
    }

    private fun sanitizeLogValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is Boolean, is Number -> value.toString()
            else -> value.toString()
                .replace('\r', '_')
                .replace('\n', '_')
                .replace('\t', '_')
                .take(MAX_LOG_VALUE_LENGTH)
        }
    }

    private fun shortSha256(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(LOG_HASH_LENGTH)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putIfNotBlank(name: String, value: String?) {
        if (!value.isNullOrBlank()) {
            put(name, value)
        }
    }

    private fun getOrCreateDeviceId(): String {
        val existing = devicePrefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        devicePrefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    private fun resolveDeviceName(): String {
        val name = listOf(Build.MANUFACTURER, Build.MODEL)
            .mapNotNull { it?.trim()?.takeIf { value -> value.isNotBlank() } }
            .joinToString(" ")
            .trim()
        return name.ifBlank { "Android Device" }
    }

    private fun resolveAppVersion(): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "1.0"
    }

    private fun xrplAccountFromText(value: String?): String? {
        val normalized = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return if (normalized.startsWith("did:xrpl:1:")) accountFromDid(normalized) else normalized
    }

    private fun xrplDidFromText(value: String?): String? {
        val normalized = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return normalized.takeIf { it.startsWith("did:xrpl:1:") }
    }

    private fun resolveSaveCredentialEnvelope(request: JsonObject, rawPayload: String): CredentialEnvelope {
        val rawCredential = firstText(request, "sdJwt", "sd_jwt")
            ?: firstText(request, "credentialJwt", "credential_jwt")
            ?: firstText(request, "vcJwt", "vc_jwt")
            ?: request.text("vcJson")
            ?: request.text("credentialPayload")
            ?: request.text("credential")
            ?: request.obj("credentialPayload")?.toString()
            ?: request.obj("credential")?.toString()
            ?: rawPayload.takeIf { isDirectCredentialPayload(request) }
            ?: throw IllegalArgumentException("credential, sdJwt, vcJwt, or vcJson is required")
        return parseCredentialEnvelope(rawCredential)
    }

    private fun isDirectCredentialPayload(request: JsonObject): Boolean {
        return request.obj("credentialStatus") != null ||
            request.obj("credentialSubject") != null ||
            request.text("issuer") != null ||
            request.text("issuerDid") != null
    }

    private fun normalizeCredentialPayloadForSave(
        payload: JsonObject,
        request: JsonObject,
        metadata: JsonObject,
        walletState: WalletStateStore.WalletState?
    ): JsonObject {
        val payloadStatus = payload.obj("credentialStatus")
        val payloadSubject = payload.obj("credentialSubject")
        val issuerAccount = xrplAccountFromText(
            firstText(metadata, "issuerAccount")
                ?: firstText(request, "issuerAccount", "issuerAddress", "issuer_account")
                ?: payload.text("issuerAccount")
                ?: payloadStatus?.text("issuer")
                ?: firstText(metadata, "issuerDid")
                ?: firstText(request, "issuerDid")
                ?: payload.text("issuerDid")
                ?: payload.text("issuer")
        )
        val holderAccount = xrplAccountFromText(
            firstText(metadata, "holderXrplAddress", "holderAccount", "holder_account")
                ?: firstText(request, "holderXrplAddress", "holderAccount", "holder_account")
                ?: payload.text("holderAccount")
                ?: payloadStatus?.text("subject")
                ?: firstText(metadata, "holderDid")
                ?: firstText(request, "holderDid")
                ?: payload.text("holderDid")
                ?: payloadSubject?.text("id")
                ?: walletState?.account
        )
        val issuerDid = firstText(metadata, "issuerDid")
            ?: firstText(request, "issuerDid")
            ?: payload.text("issuerDid")
            ?: xrplDidFromText(payload.text("issuer"))
            ?: issuerAccount?.let { "did:xrpl:1:$it" }
        val holderDid = firstText(metadata, "holderDid")
            ?: firstText(request, "holderDid")
            ?: payload.text("holderDid")
            ?: xrplDidFromText(payloadSubject?.text("id"))
            ?: holderAccount?.let { "did:xrpl:1:$it" }
            ?: walletState?.did
        val credentialType = firstText(metadata, "credentialType")
            ?: firstText(request, "credentialType", "credentialTypeHex", "credential_type")
            ?: payload.text("credentialType")
            ?: payloadStatus?.text("credentialType")
            ?: "KYC_CREDENTIAL"
        val vcCoreHash = firstText(metadata, "vcHash", "vcCoreHash")
            ?: firstText(request, "vcHash", "vcCoreHash")
            ?: payload.text("vcCoreHash")
            ?: payloadStatus?.text("vcCoreHash")
        val credentialId = firstText(request, "credentialId")
            ?: firstText(metadata, "credentialId")
            ?: firstText(payload, "credentialId", "id", "jti")
            ?: firstText(request, "id")
        val validFrom = firstText(metadata, "issuedAt", "validFrom")
            ?: firstText(payload, "validFrom", "issuanceDate", "issuedAt")
        val validUntil = firstText(metadata, "expiresAt", "validUntil", "expirationDate")
            ?: firstText(payload, "validUntil", "expirationDate", "expiresAt")

        return buildJsonObject {
            payload.forEach { (key, value) -> put(key, value) }
            putIfNotBlank("credentialId", credentialId)
            putIfNotBlank("id", payload.text("id") ?: credentialId)
            putIfNotBlank("issuer", issuerDid)
            putIfNotBlank("issuerDid", issuerDid)
            putIfNotBlank("issuerAccount", issuerAccount)
            putIfNotBlank("holderDid", holderDid)
            putIfNotBlank("holderAccount", holderAccount)
            putIfNotBlank("credentialType", credentialType)
            putIfNotBlank("vcCoreHash", vcCoreHash)
            putIfNotBlank("validFrom", validFrom)
            putIfNotBlank("validUntil", validUntil)
            put(
                "credentialSubject",
                buildJsonObject {
                    payloadSubject?.forEach { (key, value) -> put(key, value) }
                    putIfNotBlank("id", payloadSubject?.text("id") ?: holderDid)
                }
            )
            put(
                "credentialStatus",
                buildJsonObject {
                    payloadStatus?.forEach { (key, value) -> put(key, value) }
                    putIfNotBlank("issuer", issuerAccount)
                    putIfNotBlank("subject", holderAccount)
                    putIfNotBlank("credentialType", credentialType)
                    putIfNotBlank("vcCoreHash", vcCoreHash)
                    firstText(metadata, "credentialStatusId")?.let { put("statusId", it) }
                }
            )
        }
    }

    private fun buildCredentialSummary(credential: CredentialEntity): JsonObject {
        val envelope = runCatching { parseCredentialEnvelope(credential.vcJson) }.getOrNull()
        val payload = envelope?.payload ?: buildJsonObject { }
        val status = credentialLocalStatus(credential)
        val credentialKind = credentialKindLabel(payload, credential.credentialType)
        return buildJsonObject {
            put("credentialId", credential.credentialId)
            put("status", status.code)
            put("statusLabel", status.label)
            put("issuedAt", credential.validFrom)
            put("validFrom", credential.validFrom)
            put("expiresAt", credential.validUntil)
            put("validUntil", credential.validUntil)
            put("issuerDid", credential.issuerDid)
            put("issuerAccount", credential.issuerAccount)
            put("holderDid", credential.holderDid)
            put("holderAccount", credential.holderAccount)
            put("credentialType", credential.credentialType)
            put("credentialKind", credentialKind)
            put("format", envelope?.format ?: payload.text("format") ?: "unknown")
            payload.text("vct")?.let { put("vct", it) }
            put("accepted", !credential.acceptedAt.isNullOrBlank())
            credential.acceptedAt?.let { put("acceptedAt", it) }
            credential.credentialAcceptHash?.let { put("credentialAcceptHash", it) }
            credential.revokedOrInactiveAt?.let { put("revokedOrInactiveAt", it) }
        }
    }

    private data class CredentialLocalStatus(
        val code: String,
        val label: String
    )

    private fun credentialLocalStatus(credential: CredentialEntity): CredentialLocalStatus {
        val now = Instant.now()
        val validFrom = parseInstantOrNull(credential.validFrom)
        val validUntil = parseInstantOrNull(credential.validUntil)
        return when {
            !credential.revokedOrInactiveAt.isNullOrBlank() -> CredentialLocalStatus("inactive", "비활성")
            validUntil != null && !now.isBefore(validUntil) -> CredentialLocalStatus("expired", "만료")
            validFrom != null && now.isBefore(validFrom) -> CredentialLocalStatus("notYetValid", "시작 전")
            !credential.acceptedAt.isNullOrBlank() -> CredentialLocalStatus("active", "활성")
            else -> CredentialLocalStatus("issued", "발급됨")
        }
    }

    private fun credentialKindLabel(payload: JsonObject, fallback: String): String {
        payload.text("vct")?.let { return it }
        val types = payload.array("type")
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        return types.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: fallback
    }

    private fun isInactiveXrplAccountMessage(message: String): Boolean {
        val normalized = message.lowercase(Locale.US)
        return "actnotfound" in normalized ||
            "account not found" in normalized ||
            "not activated" in normalized ||
            "not active on xrpl" in normalized
    }

    private fun xrpAmountToDrops(amountXrp: String): String {
        val normalized = amountXrp.trim()
        require(normalized.matches(Regex("^\\d+(\\.\\d{1,6})?$"))) {
            "amountXrp must be a positive XRP string with up to 6 decimal places"
        }
        val parts = normalized.split(".")
        val whole = parts[0]
        val fractional = parts.getOrNull(1).orEmpty().padEnd(6, '0')
        val drops = (whole + fractional).trimStart('0').ifEmpty { "0" }
        require(drops != "0") { "amountXrp must be greater than 0" }
        return drops
    }

    private fun dropsToXrp(drops: String): String {
        val padded = drops.trim().padStart(7, '0')
        val whole = padded.dropLast(6)
        val fractional = padded.takeLast(6).trimEnd('0')
        return if (fractional.isEmpty()) whole else "$whole.$fractional"
    }

    private fun availableAuthMethods(): JsonArray {
        val methods = mutableListOf<JsonPrimitive>()
        if (appLockStore.hasPin()) {
            methods += JsonPrimitive("pin")
        }
        if (appLockStore.hasPattern()) {
            methods += JsonPrimitive("pattern")
        }
        if (appLockStore.isBiometricEnabled()) {
            methods += JsonPrimitive("biometric")
        }
        return JsonArray(methods)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putAuthAttemptState() {
        put("failedAttempts", appLockStore.getFailedAuthAttempts())
        put("remainingAttempts", appLockStore.getRemainingAuthAttempts())
        put("failureThreshold", appLockStore.getAuthFailureThreshold())
        put("emailVerificationRequired", appLockStore.isEmailVerificationRequired())
        put("sessionUnlocked", appLockStore.isSessionUnlocked())
        put("sessionRemainingMs", appLockStore.getSessionRemainingMillis())
        put("xrpPaymentAuthReady", appLockStore.isSensitiveActionAuthorized(SENSITIVE_REASON_XRP_PAYMENT))
        put("xrpPaymentAuthRemainingMs", appLockStore.getSensitiveActionRemainingMillis(SENSITIVE_REASON_XRP_PAYMENT))
        appLockStore.getSessionExpiresAtMillis()?.let { put("sessionExpiresAtMs", it) }
    }

    private fun requireAuthMethodConfigured(method: String) {
        when (method) {
            "pin" -> require(appLockStore.hasPin()) { "PIN login is not configured" }
            "pattern" -> require(appLockStore.hasPattern()) { "Pattern login is not configured" }
            "biometric" -> require(appLockStore.isBiometricEnabled()) { "Biometric login is not enabled" }
        }
    }

    private fun requireTrustedBridgeOrigin(action: String) {
        val currentUrl = webViewRef?.get()?.url ?: throw IllegalStateException("WebView is not attached")
        val trusted = currentUrl.startsWith("file:///android_asset/") ||
            TRUSTED_BRIDGE_HOSTS.any { host ->
                currentUrl.startsWith("https://$host") || currentUrl.startsWith("http://$host")
            }
        require(trusted) { "Bridge origin is not allowed for $action: $currentUrl" }
    }

    private fun validateBridgeRequest(
        request: JsonObject,
        allowedActions: Set<String>,
        ttlSeconds: Long
    ) {
        val requestId = request.text("requestId")
            ?: throw IllegalArgumentException("requestId is required")
        require(runCatching { UUID.fromString(requestId) }.isSuccess) {
            "requestId must be a UUID"
        }
        val action = request.text("action")
            ?: throw IllegalArgumentException("action is required")
        require(action in allowedActions) {
            "action is not allowed: $action"
        }
        val issuedAt = parseInstantOrNull(request.text("issuedAt").orEmpty())
            ?: throw IllegalArgumentException("issuedAt must be an ISO-8601 UTC timestamp")
        val now = Instant.now()
        require(!issuedAt.isAfter(now.plusSeconds(5))) {
            "issuedAt is in the future"
        }
        require(!issuedAt.isBefore(now.minusSeconds(ttlSeconds))) {
            "request expired"
        }
    }

    private suspend fun performBackendRequestIfPresent(request: JsonObject): BackendResponse? {
        val backendRequest = request.obj("backendRequest") ?: return null
        val baseUrl = backendRequest.text("baseUrl")
            ?: request.text("coreBaseUrl")
            ?: request.text("baseUrl")
            ?: throw IllegalArgumentException("backendRequest.baseUrl is required")
        val endpoint = resolveBackendEndpoint(
            baseUrl = baseUrl,
            endpoint = backendRequest.text("endpoint"),
            defaultPath = backendRequest.text("defaultPath") ?: "/auth/login"
        )
        val bodyJson = backendRequest["body"]?.jsonObject ?: buildJsonObject { }
        val responseText = postJson(endpoint, bodyJson.toString(), "Native auth backend request")
        val responseJson = runCatching { Json.parseToJsonElement(responseText).jsonObject }.getOrNull()
        return BackendResponse(json = responseJson, rawText = responseText)
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
            domain = firstQrText(request, qrPayload, "aud", "audience", "domain") ?: "kyvc.local",
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
            "nonce" in joined && "not issued" in joined -> VerifierFailureInfo(
                "KB_NONCE_NOT_ISSUED",
                "Verifier Nonce가 등록되지 않았습니다",
                "실제 Nonce 요청을 먼저 실행한 뒤, 그 nonce로 KB-JWT를 다시 생성하세요."
            )
            "nonce" in joined && "already used" in joined -> VerifierFailureInfo(
                "KB_NONCE_ALREADY_USED",
                "이미 사용된 Nonce입니다",
                "Nonce는 1회용입니다. 새 Nonce를 받은 뒤 KB-JWT를 다시 생성하세요."
            )
            "nonce" in joined && "expired" in joined -> VerifierFailureInfo(
                "KB_NONCE_EXPIRED",
                "Nonce가 만료됐습니다",
                "새 Nonce를 발급받아 KB-JWT를 다시 생성하세요."
            )
            "aud mismatch" in joined -> VerifierFailureInfo(
                "KB_AUD_MISMATCH",
                "KB-JWT audience가 일치하지 않습니다",
                "Nonce 발급 시 받은 aud와 KB-JWT 생성 aud를 같은 값으로 맞추세요."
            )
            "sd_hash mismatch" in joined || "sd hash mismatch" in joined -> VerifierFailureInfo(
                "KB_SD_HASH_MISMATCH",
                "SD-JWT disclosure 묶음이 변경됐습니다",
                "KB-JWT 생성 뒤 disclosure set이 바뀌면 제출할 수 없습니다. Nonce를 다시 받고 KB-JWT를 재생성하세요."
            )
            "kb-jwt signature" in joined || "holder binding" in joined -> VerifierFailureInfo(
                "KB_SIGNATURE_INVALID",
                "KB-JWT 서명 검증에 실패했습니다",
                "holder authentication key와 DID Document의 holder-key-1 공개키가 같은 키쌍인지 확인하세요."
            )
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
                "core 서버 또는 XRPL status 조회가 지연된 상태입니다. 네트워크/VPN 연결을 확인하고 잠시 뒤 다시 시도하세요."
            )
            else -> VerifierFailureInfo(
                "VERIFIER_REJECTED",
                "Verifier가 제출을 거부했습니다",
                messages.firstOrNull() ?: "응답의 errors/details를 확인하세요."
            )
        }
    }

    private suspend fun resolveAttachmentPlan(
        selectedDocumentEvidence: List<DocumentEvidenceClaim>,
        forceDocumentTypes: Set<String> = emptySet()
    ): AttachmentPlan {
        val blockers = mutableListOf<String>()
        val attachments = mutableListOf<AttachmentFileRef>()
        selectedDocumentEvidence.forEachIndexed { index, evidence ->
            val requirementId = "document-evidence-${index + 1}"
            val matched = findDocumentMatch(evidence)
            if (matched == null) {
                blockers.add("missing document for ${evidence.documentId}/${evidence.documentType}")
                return@forEachIndexed
            }
            val raw = runCatching { secureDocumentStore.loadDecrypted(matched.encryptedBlobPath) }.getOrElse {
                blockers.add("stored file read failed for ${evidence.documentId}")
                return@forEachIndexed
            }
            val recalculated = computeSha384SRI(raw)
            if (!sriEquals(recalculated, evidence.digestSRI)) {
                blockers.add("digest mismatch for ${evidence.documentId}")
                return@forEachIndexed
            }
            val ref = "doc-${UUID.randomUUID().toString().replace("-", "").take(12)}"
            attachments += AttachmentFileRef(
                requirementId = requirementId,
                attachmentRef = ref,
                documentId = evidence.documentId,
                documentType = evidence.documentType,
                digestSRI = normalizeSri(evidence.digestSRI),
                mediaType = evidence.mediaType,
                byteSize = evidence.byteSize,
                sourceEntity = matched
            )
        }
        // future policy hook: always-required document type
        forceDocumentTypes.forEach { forcedType ->
            if (attachments.none { it.documentType == forcedType }) {
                blockers.add("forced documentType missing: $forcedType")
            }
        }
        return AttachmentPlan(attachments = attachments, blockers = blockers)
    }

    private suspend fun prepareAttachmentSubmission(attachmentManifestInput: JsonArray): PreparedAttachmentSubmission {
        val blockers = mutableListOf<String>()
        val attachments = mutableListOf<AttachmentFileRef>()
        attachmentManifestInput.forEach { entry ->
            val item = entry as? JsonObject ?: return@forEach
            val attachmentRef = item.text("attachmentRef")
            val documentId = item.text("documentId")
            val documentType = item.text("documentType")
            val digestSRI = item.text("digestSRI")
            val mediaType = item.text("mediaType")
            val byteSize = item.long("byteSize")
            if (attachmentRef.isNullOrBlank() || documentId.isNullOrBlank() || documentType.isNullOrBlank() ||
                digestSRI.isNullOrBlank() || mediaType.isNullOrBlank() || byteSize == null
            ) {
                blockers.add("attachmentManifest item incomplete")
                return@forEach
            }
            val match = findDocumentMatch(
                DocumentEvidenceClaim(
                    documentId = documentId,
                    documentType = documentType,
                    digestSRI = digestSRI,
                    mediaType = mediaType,
                    byteSize = byteSize
                )
            )
            if (match == null) {
                blockers.add("no local file for manifest docId=$documentId")
                return@forEach
            }
            val raw = runCatching { secureDocumentStore.loadDecrypted(match.encryptedBlobPath) }.getOrNull()
            if (raw == null) {
                blockers.add("cannot read local file docId=$documentId")
                return@forEach
            }
            val recalculated = computeSha384SRI(raw)
            if (!sriEquals(recalculated, digestSRI)) {
                blockers.add("digest mismatch docId=$documentId")
                return@forEach
            }
            attachments += AttachmentFileRef(
                requirementId = item.text("requirementId") ?: "document-evidence",
                attachmentRef = attachmentRef,
                documentId = documentId,
                documentType = documentType,
                digestSRI = normalizeSri(digestSRI),
                mediaType = mediaType,
                byteSize = byteSize,
                sourceEntity = match
            )
        }
        return PreparedAttachmentSubmission(
            attachments = attachments,
            blockers = blockers,
            manifestJson = JsonArray(
                attachments.map { attachment ->
                    buildJsonObject {
                        put("requirementId", attachment.requirementId)
                        put("documentId", attachment.documentId)
                        put("attachmentRef", attachment.attachmentRef)
                        put("documentType", attachment.documentType)
                        put("digestSRI", attachment.digestSRI)
                        put("mediaType", attachment.mediaType)
                        put("byteSize", attachment.byteSize)
                    }
                }
            )
        )
    }

    private suspend fun findDocumentMatch(evidence: DocumentEvidenceClaim): HolderDocumentEntity? {
        holderDocumentRepository.findByDocumentId(evidence.documentId)?.let { candidate ->
            if (candidate.documentType == evidence.documentType && sriEquals(candidate.digestSRI, evidence.digestSRI)) {
                return candidate
            }
        }
        holderDocumentRepository.findByTypeAndDigest(evidence.documentType, normalizeSri(evidence.digestSRI))?.let {
            return it
        }
        return null
    }

    private fun extractDocumentEvidenceFromSelectedDisclosures(selectedDisclosures: List<String>): List<DocumentEvidenceClaim> {
        val output = mutableListOf<DocumentEvidenceClaim>()
        selectedDisclosures.forEach { disclosure ->
            val decoded = runCatching { Json.parseToJsonElement(decodeBase64UrlToString(disclosure)) }.getOrNull()
            val arr = decoded as? JsonArray ?: return@forEach
            val value = arr.getOrNull(1) ?: arr.getOrNull(2) ?: return@forEach
            collectDocumentEvidence(value, output)
        }
        return output.distinctBy { "${it.documentId}|${it.documentType}|${normalizeSri(it.digestSRI)}" }
    }

    private fun collectDocumentEvidence(element: kotlinx.serialization.json.JsonElement, out: MutableList<DocumentEvidenceClaim>) {
        when (element) {
            is JsonObject -> {
                if (element.keys.contains("documentId") && element.keys.contains("documentType") && element.keys.contains("digestSRI")) {
                    val docId = element.text("documentId")
                    val docType = element.text("documentType")
                    val digest = element.text("digestSRI")
                    val mediaType = element.text("mediaType")
                    val byteSize = element.long("byteSize")
                    if (!docId.isNullOrBlank() && !docType.isNullOrBlank() && !digest.isNullOrBlank() &&
                        !mediaType.isNullOrBlank() && byteSize != null
                    ) {
                        out += DocumentEvidenceClaim(
                            documentId = docId,
                            documentType = docType,
                            digestSRI = digest,
                            mediaType = mediaType,
                            byteSize = byteSize
                        )
                    }
                }
                element.values.forEach { collectDocumentEvidence(it, out) }
            }
            is JsonArray -> element.forEach { collectDocumentEvidence(it, out) }
            else -> Unit
        }
    }

    private fun computeSha384SRI(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-384").digest(bytes)
        val base64 = Base64.encodeToString(digest, Base64.NO_WRAP)
        return "sha384-$base64"
    }

    private fun normalizeSri(value: String): String {
        val v = value.trim()
        if (!v.startsWith("sha384-", ignoreCase = true)) return v
        val payload = v.substringAfter("-")
            .replace('-', '+')
            .replace('_', '/')
            .trimEnd('=')
        return "sha384-$payload"
    }

    private fun sriEquals(left: String, right: String): Boolean {
        return normalizeSri(left).equals(normalizeSri(right), ignoreCase = true)
    }

    private fun postMultipart(
        endpoint: String,
        presentationPayload: JsonObject,
        attachments: List<AttachmentFileRef>
    ): String {
        val url = endpoint.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid Verifier endpoint: $endpoint")
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        builder.addFormDataPart("presentation", presentationPayload.toString())
        attachments.forEach { attachment ->
            val decrypted = secureDocumentStore.loadDecrypted(attachment.sourceEntity.encryptedBlobPath)
            val tempFile = File.createTempFile("kyvc-upload-", ".bin", context.cacheDir).apply {
                writeBytes(decrypted)
                deleteOnExit()
            }
            val media = attachment.mediaType.toMediaTypeOrNull() ?: OCTET_STREAM
            builder.addFormDataPart(
                attachment.attachmentRef,
                attachment.sourceEntity.originalFilename,
                tempFile.readBytes().toRequestBody(media)
            )
        }
        val request = Request.Builder()
            .url(url)
            .post(builder.build())
            .build()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Verifier request failed (${response.code}): $responseBody")
            }
            return responseBody
        }
    }

    private fun classifyIssuerRequestFailure(message: String?): VerifierFailureInfo {
        val text = message.orEmpty().lowercase(Locale.US)
        return when {
            "timeout" in text || "timed out" in text -> VerifierFailureInfo(
                "ISSUER_REQUEST_TIMEOUT",
                "Issuer 서버 응답 시간이 초과됐습니다",
                "core 서버 또는 XRPL testnet 처리가 지연된 상태입니다. 네트워크/VPN 연결을 확인하고 잠시 뒤 다시 시도하세요. 같은 오류가 반복되면 서버 로그에서 /issuer/credentials/kyc 처리 시간을 확인해야 합니다."
            )
            "issuer private key pem file could not be read" in text ||
                "issuer_private_key_pem" in text ||
                ".local-secrets/issuer-key.pem" in text -> VerifierFailureInfo(
                    "ISSUER_KEY_NOT_CONFIGURED",
                    "Issuer 서버 키 설정이 없습니다",
                    "Android holder 앱은 issuer private key/PEM을 보내면 안 됩니다. core 서버에 issuer key 파일 또는 issuer 운영 설정이 등록되어야 실제 VC 발급이 가능합니다."
                )
            "400" in text -> VerifierFailureInfo(
                "ISSUER_BAD_REQUEST",
                "Issuer 요청이 서버에서 거부됐습니다",
                "holder_account, holder_did, claims, valid_from, valid_until 값과 core 서버 설정을 확인하세요."
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

    private fun JsonObject.array(name: String): JsonArray? {
        return this[name] as? JsonArray
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

    private fun buildDefaultSdJwtClaims(request: JsonObject): JsonObject {
        val jurisdiction = request.text("jurisdiction") ?: "KR"
        return buildJsonObject {
            put(
                "kyc",
                buildJsonObject {
                    put("jurisdiction", jurisdiction)
                    put("assuranceLevel", request.text("assuranceLevel") ?: request.text("kycLevel") ?: "STANDARD")
                }
            )
            put(
                "legalEntity",
                buildJsonObject {
                    put("type", request.text("legalEntityType") ?: "STOCK_COMPANY")
                    put("name", request.text("corporateName") ?: "KYvC Labs")
                    put("registrationNumber", request.text("businessNumber") ?: "110111-1234567")
                }
            )
            put(
                "representative",
                buildJsonObject {
                    put("name", request.text("representativeName") ?: "Kim Holder")
                    put("birthDate", request.text("representativeBirthDate") ?: "1980-01-01")
                    put("nationality", request.text("representativeNationality") ?: jurisdiction)
                }
            )
            put(
                "beneficialOwners",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("name", request.text("beneficialOwner") ?: "Owner One")
                            put("birthDate", request.text("beneficialOwnerBirthDate") ?: "1975-02-03")
                            put("nationality", request.text("beneficialOwnerNationality") ?: jurisdiction)
                            put("ownershipPercentage", request.text("ownershipPercentage")?.toIntOrNull() ?: 35)
                        }
                    )
                )
            )
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

    private suspend fun requireActiveCredentialStatus(
        vcObject: JsonObject,
        walletState: WalletStateStore.WalletState
    ) {
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

    private fun defaultSdJwtPresentationDefinition(): JsonObject {
        return buildJsonObject {
            put("id", "wallet-direct-kyc-test-v1")
            put("acceptedFormat", "dc+sd-jwt")
            put(
                "acceptedVct",
                JsonArray(listOf(JsonPrimitive("https://kyvc.example/vct/legal-entity-kyc-v1")))
            )
            put("acceptedJurisdictions", JsonArray(listOf(JsonPrimitive("KR"))))
            put("minimumAssuranceLevel", "STANDARD")
            put(
                "requiredDisclosures",
                JsonArray(
                    listOf(
                        "legalEntity.type",
                        "representative.name",
                        "representative.birthDate",
                        "representative.nationality",
                        "beneficialOwners[].name",
                        "beneficialOwners[].birthDate",
                        "beneficialOwners[].nationality"
                    ).map { JsonPrimitive(it) }
                )
            )
            put("documentRules", JsonArray(emptyList()))
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
                "This looks like sample data. Replace it with a real XRPL testnet classic address."
            } else {
                "Use the XRPL classic address itself, not a DID."
            }
            throw IllegalArgumentException("$label must be a real XRPL classic address (25-35 chars, starts with r): $address. $hint")
        }
        return address
    }

    private suspend fun resolveVcJson(request: JsonObject): String {
        request.text("sdJwt")?.let { return parseCredentialEnvelope(it).payload.toString() }
        request.text("sd_jwt")?.let { return parseCredentialEnvelope(it).payload.toString() }
        request.text("credentialJwt")?.takeIf { isSdJwt(it) }?.let { return parseCredentialEnvelope(it).payload.toString() }
        request.text("credential_jwt")?.takeIf { isSdJwt(it) }?.let { return parseCredentialEnvelope(it).payload.toString() }
        request.text("vcJson")?.let { return parseCredentialEnvelope(it).payload.toString() }
        request.text("credential")?.takeIf { isSdJwt(it) }?.let { return parseCredentialEnvelope(it).payload.toString() }
        request.text("credentialJwt")?.takeIf { isCompactJwt(it) }?.let { return decodeJwtPayload(it).toString() }
        request.text("credential_jwt")?.takeIf { isCompactJwt(it) }?.let { return decodeJwtPayload(it).toString() }
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
        request.text("credentialJwt")?.takeIf { isCompactJwt(it) }?.let { return it }
        request.text("credential_jwt")?.takeIf { isCompactJwt(it) }?.let { return it }
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

    private suspend fun resolveSdJwt(request: JsonObject): String? {
        request.text("sdJwt")?.takeIf { isSdJwt(it) }?.let { return it }
        request.text("sd_jwt")?.takeIf { isSdJwt(it) }?.let { return it }
        request.text("credentialJwt")?.takeIf { isSdJwt(it) }?.let { return it }
        request.text("credential_jwt")?.takeIf { isSdJwt(it) }?.let { return it }
        request.text("credential")?.takeIf { isSdJwt(it) }?.let { return it }
        request.text("vcJson")?.let { raw ->
            val envelope = runCatching { parseCredentialEnvelope(raw) }.getOrNull()
            envelope?.sdJwt?.let { return it }
        }
        request.text("credentialId")
            ?.takeIf { it.isNotBlank() }
            ?.let { credentialId ->
                credentialRepository.getCredentialById(credentialId)?.vcJson?.takeIf { isSdJwt(it) }?.let {
                    return it
                }
            }
        return null
    }

    private fun parseCredentialEnvelope(raw: String): CredentialEnvelope {
        val trimmed = raw.trim()
        if (isSdJwt(trimmed)) {
            val sdJwt = parseSdJwtCredential(trimmed)
            return CredentialEnvelope(
                rawCredential = trimmed,
                vcJwt = null,
                sdJwt = trimmed,
                payload = sdJwt.payload,
                issuerJwt = sdJwt.issuerJwt,
                disclosures = sdJwt.disclosures,
                format = "dc+sd-jwt"
            )
        }
        if (isCompactJwt(trimmed)) {
            return CredentialEnvelope(rawCredential = trimmed, vcJwt = trimmed, sdJwt = null, payload = decodeJwtPayload(trimmed))
        }
        val json = Json.parseToJsonElement(trimmed).jsonObject
        val sdJwtValue = json.text("sdJwt")
            ?: json.text("sd_jwt")
            ?: json.text("credentialJwt")?.takeIf { isSdJwt(it) }
            ?: json.text("credential_jwt")?.takeIf { isSdJwt(it) }
            ?: json.text("credential")?.takeIf { isSdJwt(it) }
        if (!sdJwtValue.isNullOrBlank()) {
            val sdJwt = parseSdJwtCredential(sdJwtValue)
            return CredentialEnvelope(
                rawCredential = sdJwtValue,
                vcJwt = null,
                sdJwt = sdJwtValue,
                payload = sdJwt.payload,
                issuerJwt = sdJwt.issuerJwt,
                disclosures = sdJwt.disclosures,
                format = "dc+sd-jwt"
            )
        }
        val jwt = json.text("credentialJwt")?.takeIf { isCompactJwt(it) }
            ?: json.text("credential_jwt")?.takeIf { isCompactJwt(it) }
            ?: json.text("vcJwt")
            ?: json.text("credential")?.takeIf { isCompactJwt(it) }
            ?: json.text("vc_jwt")
        if (!jwt.isNullOrBlank()) {
            return CredentialEnvelope(rawCredential = jwt, vcJwt = jwt, sdJwt = null, payload = decodeJwtPayload(jwt))
        }
        return CredentialEnvelope(rawCredential = trimmed, vcJwt = null, sdJwt = null, payload = json)
    }

    private fun validateCredentialJwt(
        envelope: CredentialEnvelope,
        issuerDidDocument: JsonObject?
    ) {
        if (envelope.sdJwt != null) {
            validateSdJwtCredential(envelope, issuerDidDocument)
            return
        }
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

    private fun validateSdJwtCredential(
        envelope: CredentialEnvelope,
        issuerDidDocument: JsonObject?
    ) {
        val issuerJwt = envelope.issuerJwt ?: envelope.sdJwt?.substringBefore("~")
            ?: throw IllegalArgumentException("SD-JWT issuer JWT is required")
        val header = decodeJwtHeader(issuerJwt)
        val payload = decodeJwtPayload(issuerJwt)
        require(header.text("alg") == "ES256K") { "SD-JWT issuer JWT alg must be ES256K" }
        require(header.text("typ") == "dc+sd-jwt") { "SD-JWT issuer JWT typ must be dc+sd-jwt" }
        val issuer = payload.text("iss")
            ?: throw IllegalArgumentException("SD-JWT payload.iss is required")
        header.text("iss")?.let { iss ->
            require(iss == issuer) { "SD-JWT header.iss mismatch: header=$iss, payload=$issuer" }
        }
        val kid = header.text("kid")
            ?: throw IllegalArgumentException("SD-JWT issuer JWT kid is required")
        require(kid.startsWith("$issuer#")) {
            "SD-JWT issuer JWT kid must belong to issuer DID: kid=$kid, issuer=$issuer"
        }
        val subject = payload.text("sub")
            ?: throw IllegalArgumentException("SD-JWT payload.sub is required")
        val cnfKid = payload.obj("cnf")?.text("kid")
            ?: throw IllegalArgumentException("SD-JWT payload.cnf.kid is required")
        require(cnfKid.startsWith("$subject#")) {
            "SD-JWT payload.cnf.kid must belong to holder DID: kid=$cnfKid, sub=$subject"
        }
        val status = payload.obj("credentialStatus")
            ?: throw IllegalArgumentException("SD-JWT credentialStatus is required")
        require(status.text("type") == "XRPLCredentialStatus") {
            "SD-JWT credentialStatus.type must be XRPLCredentialStatus"
        }
        require(!status.text("credentialType").isNullOrBlank()) {
            "SD-JWT credentialStatus.credentialType is required"
        }
        validateJwtTimeWindow(payload)
        validateSdJwtDisclosures(envelope.disclosures)
        if (issuerDidDocument != null) {
            verifyCredentialJwtSignature(issuerJwt, kid, issuerDidDocument)
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

    private fun isSdJwt(value: String): Boolean {
        val parts = value.trim().split("~")
        return parts.size >= 2 && isCompactJwt(parts.first())
    }

    private fun parseSdJwtCredential(raw: String): SdJwtCredential {
        val parts = raw.trim().split("~").filter { it.isNotBlank() }
        require(parts.size >= 2) { "SD-JWT credential must include issuer JWT and at least one disclosure" }
        val issuerJwt = parts.first()
        require(isCompactJwt(issuerJwt)) { "SD-JWT issuer JWT must be compact JWS" }
        val disclosures = parts.drop(1)
        validateSdJwtDisclosures(disclosures)
        val issuerPayload = decodeJwtPayload(issuerJwt)
        return SdJwtCredential(
            issuerJwt = issuerJwt,
            disclosures = disclosures,
            header = decodeJwtHeader(issuerJwt),
            issuerPayload = issuerPayload,
            payload = normalizeSdJwtPayload(issuerPayload)
        )
    }

    private fun normalizeSdJwtPayload(payload: JsonObject): JsonObject {
        val issuerDid = payload.text("iss").orEmpty()
        val holderDid = payload.text("sub").orEmpty()
        val status = payload.obj("credentialStatus")
        val issuerAccount = accountFromDid(issuerDid)
        val holderAccount = accountFromDid(holderDid)
        return buildJsonObject {
            put("format", "dc+sd-jwt")
            put("id", payload.text("jti").orEmpty())
            put("credentialId", payload.text("jti").orEmpty())
            put("issuer", issuerDid)
            put("issuerDid", issuerDid)
            put("holderDid", holderDid)
            put("vct", payload.text("vct").orEmpty())
            put("validFrom", payload.long("iat")?.let { Instant.ofEpochSecond(it).toString() }.orEmpty())
            put("validUntil", payload.long("exp")?.let { Instant.ofEpochSecond(it).toString() }.orEmpty())
            put(
                "credentialSubject",
                buildJsonObject {
                    put("id", holderDid)
                }
            )
            put(
                "credentialStatus",
                buildJsonObject {
                    put("type", status?.text("type") ?: "XRPLCredentialStatus")
                    status?.text("statusId")?.let { put("statusId", it) }
                    status?.text("id")?.let { put("id", it) }
                    put("issuer", issuerAccount)
                    put("subject", holderAccount)
                    put("credentialType", status?.text("credentialType").orEmpty())
                    status?.text("vcCoreHash")?.let { put("vcCoreHash", it) }
                }
            )
        }
    }

    private fun JsonObject.long(name: String): Long? {
        return (this[name] as? JsonPrimitive)?.longOrNull
    }

    private fun validateJwtTimeWindow(payload: JsonObject) {
        payload.long("iat")?.let { iat ->
            require(Instant.now().epochSecond >= iat) { "SD-JWT is not yet valid" }
        }
        payload.long("exp")?.let { exp ->
            require(Instant.now().epochSecond < exp) { "SD-JWT has expired" }
        }
    }

    private fun validateSdJwtDisclosures(disclosures: List<String>) {
        val digests = mutableSetOf<String>()
        disclosures.forEach { disclosure ->
            require(disclosure.isNotBlank()) { "SD-JWT disclosure is blank" }
            runCatching {
                val decoded = decodeBase64UrlToString(disclosure)
                val element = Json.parseToJsonElement(decoded)
                require(element is JsonArray) { "SD-JWT disclosure must decode to JSON array" }
            }.getOrElse { error ->
                throw IllegalArgumentException("SD-JWT disclosure is malformed: ${error.message}", error)
            }
            val digest = disclosureDigest(disclosure)
            require(digests.add(digest)) { "duplicate SD-JWT disclosure" }
        }
    }

    private fun disclosureDigest(disclosure: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(disclosure.toByteArray(Charsets.US_ASCII))
        return base64UrlNoPadding(digest)
    }

    private fun selectedSdJwtDisclosures(request: JsonObject, allDisclosures: List<String>): List<String> {
        val selected = request["selectedDisclosures"] as? JsonArray ?: return allDisclosures
        val values = selected.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { value -> value.isNotBlank() } }
        return if (values.isEmpty()) allDisclosures else values
    }

    private fun buildSelectedSdJwt(issuerJwt: String, disclosures: List<String>): String {
        return listOf(issuerJwt).plus(disclosures).joinToString("~")
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
        val sdJwt: String? = null,
        val payload: JsonObject,
        val issuerJwt: String? = null,
        val disclosures: List<String> = emptyList(),
        val format: String = if (sdJwt != null) "dc+sd-jwt" else if (vcJwt != null) "vc+jwt" else "json"
    )

    private data class SdJwtCredential(
        val issuerJwt: String,
        val disclosures: List<String>,
        val header: JsonObject,
        val issuerPayload: JsonObject,
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
            walletState.mnemonic?.let { put("mnemonic", it) }
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

    private fun didDocumentDataHash(didDocument: String): String {
        val canonical = JsonCanonicalizer(didDocument).encodedString
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "1220" + digest.joinToString("") { "%02X".format(it) }
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

    private data class DocumentEvidenceClaim(
        val documentId: String,
        val documentType: String,
        val digestSRI: String,
        val mediaType: String,
        val byteSize: Long
    )

    private data class AttachmentFileRef(
        val requirementId: String,
        val attachmentRef: String,
        val documentId: String,
        val documentType: String,
        val digestSRI: String,
        val mediaType: String,
        val byteSize: Long,
        val sourceEntity: HolderDocumentEntity
    )

    private data class AttachmentPlan(
        val attachments: List<AttachmentFileRef>,
        val blockers: List<String>
    )

    private data class PreparedAttachmentSubmission(
        val attachments: List<AttachmentFileRef>,
        val blockers: List<String>,
        val manifestJson: JsonArray
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

    private data class BackendResponse(
        val json: JsonObject?,
        val rawText: String
    )

    private companion object {
        private const val TAG = "WalletBridge"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val AUTH_METHODS = setOf("pin", "pattern", "biometric")
        private const val SENSITIVE_REASON_XRP_PAYMENT = "xrp-payment"
        private const val KEY_DEVICE_ID = "device_id"
        private const val LOG_HASH_LENGTH = 12
        private const val MAX_LOG_VALUE_LENGTH = 160
        private val SENSITIVE_AUTH_REASONS = setOf(SENSITIVE_REASON_XRP_PAYMENT)
        private val TRUSTED_BRIDGE_HOSTS = setOf(
            "dev-kyvc.khuoo.synology.me",
            "dev-core-kyvc.khuoo.synology.me",
            "demo.kyvc.local",
            "dev-api-kyvc.khuoo.synology.me"
        )
    }
}
