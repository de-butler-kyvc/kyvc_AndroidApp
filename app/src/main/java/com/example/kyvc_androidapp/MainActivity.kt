package com.example.kyvc_androidapp

import android.app.Activity
import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.net.http.SslError
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.kyvc_androidapp.auth.BiometricAuthScreen
import com.example.kyvc_androidapp.auth.UnlockActivity
import com.example.kyvc_androidapp.bridge.WalletBridge
import com.example.kyvc_androidapp.ui.main.MainViewModel
import com.example.kyvc_androidapp.ui.theme.Kyvc_AndroidAppTheme
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private enum class AuthMode {
    Pin,
    Pattern
}

private enum class CredentialNativeScreen {
    IssueComplete,
    IssueConfirm,
    CredentialDetail,
    CredentialSubmit;

    companion object {
        fun from(value: String): CredentialNativeScreen {
            return when (value) {
                "issueComplete" -> IssueComplete
                "issueConfirm", "issueConfirm1", "issueConfirm2" -> IssueConfirm
                "credentialDetail" -> CredentialDetail
                "credentialSubmit" -> CredentialSubmit
                else -> CredentialDetail
            }
        }
    }
}

private data class CredentialNativeScreenRequest(
    val screen: CredentialNativeScreen,
    val requestJson: String
)

private data class IssueConfirmBalanceSnapshot(
    val currentBalanceXrp: String,
    val networkFeeXrp: String,
    val usableBalanceXrp: String,
    val balanceWarning: String
)

private data class VpLoginCredentialPickerRequest(
    val title: String,
    val options: List<Pair<String, String>>,
    val onSelected: (String?) -> Unit
)

class MainActivity : FragmentActivity() {
    private val mainViewModel: MainViewModel by viewModels()
    private lateinit var bridge: WalletBridge
    private lateinit var biometricExecutor: Executor
    private var isUnlocked = mutableStateOf(false)
    private val qrOverlayRequestJson = mutableStateOf<String?>(null)
    private val biometricOverlayRequestJson = mutableStateOf<String?>(null)
    private val pinResetRequestJson = mutableStateOf<String?>(null)
    private val mnemonicBackupRequest = mutableStateOf<Pair<String, String>?>(null)
    private val walletRestoreRequestJson = mutableStateOf<String?>(null)
    private val credentialNativeScreenRequest = mutableStateOf<CredentialNativeScreenRequest?>(null)
    private val vpLoginCredentialPickerRequest = mutableStateOf<VpLoginCredentialPickerRequest?>(null)
    private val vpLoginCompletionMessage = mutableStateOf<String?>(null)
    private var pendingQrRequestJson: String? = null
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val request = pendingQrRequestJson
            pendingQrRequestJson = null
            if (granted && !request.isNullOrBlank()) {
                qrOverlayRequestJson.value = request
            } else if (!request.isNullOrBlank()) {
                bridge.onQrScanResult(
                    requestJson = request,
                    qrData = null,
                    errorMessage = "Camera permission denied"
                )
            }
        }
    private val unlockLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val requestJson = result.data?.getStringExtra(UnlockActivity.EXTRA_REQUEST_JSON)
            val method = result.data?.getStringExtra(UnlockActivity.EXTRA_METHOD)
            val error = result.data?.getStringExtra(UnlockActivity.EXTRA_ERROR)
            if (requestJson.isNullOrBlank()) {
                if (result.resultCode == Activity.RESULT_OK) {
                    mainViewModel.appContainer.appLockStore.markSessionUnlocked()
                    isUnlocked.value = true
                }
            } else {
                bridge.onNativeAuthResult(
                    requestJson = requestJson,
                    method = method,
                    success = result.resultCode == Activity.RESULT_OK,
                    errorMessage = error
                )
            }
        }

    private suspend fun deleteStoredCredential(credentialId: String): Boolean = withContext(Dispatchers.IO) {
        if (credentialId.isBlank()) return@withContext false
        val credential = mainViewModel.appContainer.credentialRepository.getCredentialById(credentialId)
            ?: return@withContext false
        mainViewModel.appContainer.holderDocumentRepository.deleteByCredentialId(credentialId)
        mainViewModel.appContainer.credentialRepository.deleteCredential(credential)
        true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        val container = mainViewModel.appContainer
        biometricExecutor = ContextCompat.getMainExecutor(this)
        bridge = WalletBridge(
            context = this,
            scope = lifecycleScope,
            walletStateStore = container.walletStateStore,
            xrplHelper = container.xrplHelper,
            credentialRepository = container.credentialRepository,
            holderDocumentRepository = container.holderDocumentRepository,
            secureDocumentStore = container.secureDocumentStore,
            appLockStore = container.appLockStore,
            launchQrScanner = { requestJson ->
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    qrOverlayRequestJson.value = requestJson
                } else {
                    pendingQrRequestJson = requestJson
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            launchNativeAuth = { requestJson, method ->
                if (method.equals("biometric", ignoreCase = true)) {
                    biometricOverlayRequestJson.value = requestJson
                } else {
                    val intent = Intent(this, UnlockActivity::class.java).apply {
                        putExtra(UnlockActivity.EXTRA_REQUEST_JSON, requestJson)
                        putExtra(UnlockActivity.EXTRA_METHOD, method)
                    }
                    unlockLauncher.launch(intent)
                }
            },
            launchPinReset = { requestJson ->
                pinResetRequestJson.value = requestJson
            },
            launchMnemonicBackup = { requestJson, mnemonic ->
                mnemonicBackupRequest.value = requestJson to mnemonic
            },
            launchWalletRestore = { requestJson ->
                walletRestoreRequestJson.value = requestJson
            },
            launchCredentialNativeScreen = { screen, requestJson ->
                credentialNativeScreenRequest.value = CredentialNativeScreenRequest(
                    screen = CredentialNativeScreen.from(screen),
                    requestJson = requestJson
                )
            },
            launchVpLoginCredentialPicker = { title, options, onSelected ->
                vpLoginCredentialPickerRequest.value = VpLoginCredentialPickerRequest(title, options, onSelected)
            },
            launchVpLoginCompletion = { message ->
                vpLoginCompletionMessage.value = message
            },
            onSessionStatusChanged = { unlocked ->
                isUnlocked.value = unlocked
            }
        )
        setContent {
            Kyvc_AndroidAppTheme {
                var activeWebView by remember { mutableStateOf<WebView?>(null) }
                val webBackScope = rememberCoroutineScope()
                AuthenticatedApp(
                    unlockedState = isUnlocked,
                    canUseBiometric = canUseBiometric(),
                    onBiometricAuth = ::showBiometricPrompt
                ) { unlocked ->
                    BackHandler(enabled = unlocked) {
                        val backupRequest = mnemonicBackupRequest.value
                        if (backupRequest != null) {
                            mnemonicBackupRequest.value = null
                            bridge.onMnemonicBackupResult(
                                requestJson = backupRequest.first,
                                confirmed = false,
                                errorMessage = "사용자가 복구 문구 백업을 취소했습니다."
                            )
                            return@BackHandler
                        }
                        val restoreRequest = walletRestoreRequestJson.value
                        if (!restoreRequest.isNullOrBlank()) {
                            walletRestoreRequestJson.value = null
                            bridge.onWalletRestoreCancelled(restoreRequest)
                            return@BackHandler
                        }
                        val credentialRequest = credentialNativeScreenRequest.value
                        if (credentialRequest != null) {
                            credentialNativeScreenRequest.value = null
                            bridge.onCredentialNativeScreenResult(
                                requestJson = credentialRequest.requestJson,
                                fallbackAction = credentialRequest.screen.fallbackAction(),
                                screen = credentialRequest.screen.name,
                                ok = false,
                                result = "cancel",
                                errorMessage = "사용자가 화면을 닫았습니다."
                            )
                            return@BackHandler
                        }
                        val pinResetRequest = pinResetRequestJson.value
                        if (!pinResetRequest.isNullOrBlank()) {
                            pinResetRequestJson.value = null
                            bridge.onPinResetResult(
                                requestJson = pinResetRequest,
                                success = false,
                                errorMessage = "사용자가 PIN 재설정을 취소했습니다."
                            )
                            return@BackHandler
                        }
                        val qrRequest = qrOverlayRequestJson.value
                        if (!qrRequest.isNullOrBlank()) {
                            qrOverlayRequestJson.value = null
                            bridge.onQrScanResult(
                                requestJson = qrRequest,
                                qrData = null,
                                errorMessage = "QR scan cancelled"
                            )
                            return@BackHandler
                        }
                        val biometricRequest = biometricOverlayRequestJson.value
                        if (!biometricRequest.isNullOrBlank()) {
                            biometricOverlayRequestJson.value = null
                            bridge.onNativeAuthResult(
                                requestJson = biometricRequest,
                                method = "biometric",
                                success = false,
                                errorMessage = "사용자가 인증을 취소했습니다."
                            )
                            return@BackHandler
                        }
                        val webView = activeWebView
                        if (webView != null && webView.canGoBack()) {
                            webBackScope.launch {
                                webView.goBack()
                            }
                        } else {
                            finish()
                        }
                    }
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        if (unlocked) {
                            WebViewScreen(
                                url = PRIMARY_WEB_URL,
                                fallbackUrl = LOCAL_TEST_WEB_URL,
                                bridge = bridge,
                                onWebViewCreated = { activeWebView = it },
                                modifier = Modifier.padding(innerPadding)
                            )
                            val qrRequest = qrOverlayRequestJson.value
                            if (!qrRequest.isNullOrBlank()) {
                                val qrCopy = remember(qrRequest) { qrOverlayCopy(qrRequest) }
                                QrScannerOverlay(
                                    title = qrCopy.title,
                                    subtitle = qrCopy.subtitle,
                                    footer = qrCopy.footer,
                                    onClose = {
                                        qrOverlayRequestJson.value = null
                                        bridge.onQrScanResult(
                                            requestJson = qrRequest,
                                            qrData = null,
                                            errorMessage = "QR scan cancelled"
                                        )
                                    },
                                    onScanned = { raw ->
                                        qrOverlayRequestJson.value = null
                                        bridge.onQrScanResult(
                                            requestJson = qrRequest,
                                            qrData = raw,
                                            errorMessage = null
                                        )
                                    }
                                )
                            }
                            val biometricRequest = biometricOverlayRequestJson.value
                            if (!biometricRequest.isNullOrBlank()) {
                                BiometricAuthScreen(
                                    onStartAuth = { setStatus ->
                                        showBiometricPrompt(
                                            title = "생체인증",
                                            subtitle = "등록한 생체정보로 로그인합니다.",
                                            onSuccess = {
                                                setStatus("인증이 완료되었습니다", false)
                                                biometricOverlayRequestJson.value = null
                                                bridge.onNativeAuthResult(
                                                    requestJson = biometricRequest,
                                                    method = "biometric",
                                                    success = true,
                                                    errorMessage = null
                                                )
                                            },
                                            onError = { message ->
                                                setStatus(message, true)
                                            }
                                        )
                                    },
                                    onCancel = {
                                        biometricOverlayRequestJson.value = null
                                        bridge.onNativeAuthResult(
                                            requestJson = biometricRequest,
                                            method = "biometric",
                                            success = false,
                                            errorMessage = "사용자가 인증을 취소했습니다."
                                        )
                                    },
                                    onPinLogin = {
                                        biometricOverlayRequestJson.value = null
                                        val intent = Intent(this, UnlockActivity::class.java).apply {
                                            putExtra(UnlockActivity.EXTRA_REQUEST_JSON, biometricRequest)
                                            putExtra(UnlockActivity.EXTRA_METHOD, "pin")
                                        }
                                        unlockLauncher.launch(intent)
                                    }
                                )
                            }
                            val pinResetRequest = pinResetRequestJson.value
                            if (!pinResetRequest.isNullOrBlank()) {
                                var resetPin by remember(pinResetRequest) { mutableStateOf("") }
                                var resetStatus by remember(pinResetRequest) {
                                    mutableStateOf<String?>("새 PIN 4자리를 입력하세요")
                                }
                                PinSetupScreen(
                                    pin = resetPin,
                                    statusMessage = resetStatus,
                                    onDigit = { digit ->
                                        if (resetPin.length < SETUP_PIN_LENGTH) {
                                            resetPin += digit
                                            resetStatus = "새 PIN 4자리를 입력하세요"
                                        }
                                    },
                                    onBackspace = {
                                        if (resetPin.isNotEmpty()) {
                                            resetPin = resetPin.dropLast(1)
                                            resetStatus = "새 PIN 4자리를 입력하세요"
                                        }
                                    },
                                    onCancel = {
                                        pinResetRequestJson.value = null
                                        bridge.onPinResetResult(
                                            requestJson = pinResetRequest,
                                            success = false,
                                            errorMessage = "사용자가 PIN 재설정을 취소했습니다."
                                        )
                                    }
                                )
                                LaunchedEffect(resetPin) {
                                    if (resetPin.length == SETUP_PIN_LENGTH) {
                                        resetStatus = "PIN을 저장하고 있습니다"
                                        mainViewModel.appContainer.appLockStore.setPin(resetPin)
                                        mainViewModel.appContainer.appLockStore.resetAuthFailures()
                                        mainViewModel.appContainer.appLockStore.markSessionUnlocked()
                                        isUnlocked.value = true
                                        pinResetRequestJson.value = null
                                        bridge.onPinResetResult(
                                            requestJson = pinResetRequest,
                                            success = true,
                                            errorMessage = null
                                        )
                                    }
                                }
                            }
                            val backupRequest = mnemonicBackupRequest.value
                            if (backupRequest != null) {
                                MnemonicBackupScreen(
                                    mnemonic = backupRequest.second,
                                    onBack = {
                                        mnemonicBackupRequest.value = null
                                        bridge.onMnemonicBackupResult(
                                            requestJson = backupRequest.first,
                                            confirmed = false,
                                            errorMessage = "사용자가 복구 문구 백업을 취소했습니다."
                                        )
                                    },
                                    onConfirm = {
                                        mnemonicBackupRequest.value = null
                                        bridge.onMnemonicBackupResult(
                                            requestJson = backupRequest.first,
                                            confirmed = true,
                                            errorMessage = null
                                        )
                                    }
                                )
                            }
                            val restoreRequest = walletRestoreRequestJson.value
                            if (!restoreRequest.isNullOrBlank()) {
                                WalletRestoreScreen(
                                    onBack = {
                                        walletRestoreRequestJson.value = null
                                        bridge.onWalletRestoreCancelled(restoreRequest)
                                    },
                                    onConfirm = { mnemonic ->
                                        walletRestoreRequestJson.value = null
                                        bridge.submitNativeWalletRestore(
                                            requestJson = restoreRequest,
                                            mnemonic = mnemonic
                                        )
                                    }
                                )
                            }
                            val nativeCredentialRequest = credentialNativeScreenRequest.value
                            if (nativeCredentialRequest != null) {
                                val baseUiData = remember(nativeCredentialRequest.requestJson) {
                                    CredentialUiData.from(nativeCredentialRequest.requestJson)
                                }
                                var issueConfirmBalance by remember(nativeCredentialRequest.requestJson) {
                                    mutableStateOf<IssueConfirmBalanceSnapshot?>(null)
                                }
                                var storedClaimRows by remember(nativeCredentialRequest.requestJson) {
                                    mutableStateOf<List<Triple<String, String, String>>>(emptyList())
                                }
                                LaunchedEffect(nativeCredentialRequest.screen, nativeCredentialRequest.requestJson) {
                                    issueConfirmBalance = null
                                    storedClaimRows = emptyList()
                                    if (nativeCredentialRequest.screen == CredentialNativeScreen.IssueConfirm) {
                                        issueConfirmBalance = loadIssueConfirmBalance(baseUiData)
                                    }
                                    if (nativeCredentialRequest.screen == CredentialNativeScreen.CredentialDetail) {
                                        storedClaimRows = loadStoredCredentialClaimRows(baseUiData)
                                    }
                                }
                                var uiData = baseUiData
                                if (nativeCredentialRequest.screen == CredentialNativeScreen.IssueConfirm) {
                                    uiData = issueConfirmBalance?.let {
                                        uiData.copy(
                                            currentBalanceXrp = it.currentBalanceXrp,
                                            networkFeeXrp = it.networkFeeXrp,
                                            usableBalanceXrp = it.usableBalanceXrp,
                                            balanceWarning = it.balanceWarning
                                        )
                                    } ?: uiData.copy(balanceWarning = "잔액 조회 중")
                                }
                                if (nativeCredentialRequest.screen == CredentialNativeScreen.CredentialDetail && storedClaimRows.isNotEmpty()) {
                                    uiData = uiData.copy(storedClaimRows = storedClaimRows)
                                }
                                NativeCredentialFlowScreen(
                                    screen = nativeCredentialRequest.screen,
                                    data = uiData,
                                    onBack = {
                                        credentialNativeScreenRequest.value = null
                                        bridge.onCredentialNativeScreenResult(
                                            requestJson = nativeCredentialRequest.requestJson,
                                            fallbackAction = nativeCredentialRequest.screen.fallbackAction(),
                                            screen = nativeCredentialRequest.screen.name,
                                            ok = false,
                                            result = "cancel",
                                            errorMessage = "사용자가 화면을 닫았습니다."
                                        )
                                    },
                                    onResult = { result ->
                                        if (nativeCredentialRequest.screen == CredentialNativeScreen.CredentialDetail &&
                                            result == "delete"
                                        ) {
                                            lifecycleScope.launch {
                                                val credentialId = baseUiData.credentialId
                                                val deleted = runCatching {
                                                    deleteStoredCredential(credentialId)
                                                }.getOrDefault(false)
                                                credentialNativeScreenRequest.value = null
                                                bridge.onCredentialNativeScreenResult(
                                                    requestJson = nativeCredentialRequest.requestJson,
                                                    fallbackAction = nativeCredentialRequest.screen.fallbackAction(),
                                                    screen = nativeCredentialRequest.screen.name,
                                                    ok = deleted,
                                                    result = result,
                                                    errorMessage = if (deleted) null else "삭제할 증명서를 찾을 수 없습니다."
                                                )
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    if (deleted) "증명서를 삭제했습니다." else "증명서 삭제에 실패했습니다.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        } else {
                                            credentialNativeScreenRequest.value = null
                                            bridge.onCredentialNativeScreenResult(
                                                requestJson = nativeCredentialRequest.requestJson,
                                                fallbackAction = nativeCredentialRequest.screen.fallbackAction(),
                                                screen = nativeCredentialRequest.screen.name,
                                                ok = true,
                                                result = result,
                                                errorMessage = null
                                            )
                                        }
                                    }
                                )
                            }
                            vpLoginCredentialPickerRequest.value?.let { pickerRequest ->
                                VpLoginCredentialPickerDialog(
                                    request = pickerRequest,
                                    onClose = {
                                        vpLoginCredentialPickerRequest.value = null
                                        pickerRequest.onSelected(null)
                                    },
                                    onSelected = { credentialId ->
                                        vpLoginCredentialPickerRequest.value = null
                                        pickerRequest.onSelected(credentialId)
                                    }
                                )
                            }
                            vpLoginCompletionMessage.value?.let { message ->
                                VpLoginCompletionDialog(
                                    message = message,
                                    onClose = { vpLoginCompletionMessage.value = null }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadIssueConfirmBalance(baseData: CredentialUiData): IssueConfirmBalanceSnapshot {
        return withContext(Dispatchers.IO) {
            runCatching {
                val container = mainViewModel.appContainer
                val walletState = container.walletStateStore.requireWalletState()
                val assets = container.xrplHelper.getAccountAssets(walletState.account)
                val currentBalance = assets.xrpBalanceXrp
                val currentBalanceLabel = currentBalance?.let(::formatXrpLabel) ?: baseData.currentBalanceXrp
                val networkFeeAmount = parseXrpFeeAmount(baseData.networkFeeXrp)
                val networkFeeLabel = formatXrpAmountLabel(networkFeeAmount)
                val usableBalanceLabel = currentBalance
                    ?.let { calculateUsableXrp(it, networkFeeAmount) }
                    ?.let(::formatXrpLabel)
                    ?: baseData.usableBalanceXrp
                val warning = when {
                    !assets.accountActivated -> "* 계정 활성화가 필요합니다"
                    assets.error != null -> "* 잔액 조회를 완료하지 못했습니다"
                    currentBalance?.let { parseXrpAmount(it) <= BigDecimal.ZERO } == true -> "* 잔액이 부족합니다"
                    currentBalance?.let { parseXrpAmount(it) <= networkFeeAmount } == true -> "* 네트워크 수수료보다 잔액이 부족합니다"
                    else -> ""
                }
                IssueConfirmBalanceSnapshot(
                    currentBalanceXrp = currentBalanceLabel,
                    networkFeeXrp = networkFeeLabel,
                    usableBalanceXrp = usableBalanceLabel,
                    balanceWarning = warning
                )
            }.getOrElse {
                IssueConfirmBalanceSnapshot(
                    currentBalanceXrp = baseData.currentBalanceXrp,
                    networkFeeXrp = formatXrpAmountLabel(parseXrpFeeAmount(baseData.networkFeeXrp)),
                    usableBalanceXrp = baseData.usableBalanceXrp,
                    balanceWarning = "* 잔액 조회를 완료하지 못했습니다"
                )
            }
        }
    }

    private suspend fun loadStoredCredentialClaimRows(baseData: CredentialUiData): List<Triple<String, String, String>> {
        val credentialId = baseData.credentialId.takeIf { it.isNotBlank() } ?: return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val credential = mainViewModel.appContainer.credentialRepository.getCredentialById(credentialId)
                    ?: return@withContext emptyList()
                storedCredentialClaims(
                    sdJwt = credential.sdJwt,
                    vcJwt = credential.vcJwt,
                    vcJson = credential.vcJson,
                    selectiveDisclosureJson = credential.selectiveDisclosureJson
                )
            }.getOrDefault(emptyList())
        }
    }

    private fun canUseBiometric(): Boolean {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK
        return BiometricManager.from(this).canAuthenticate(authenticators) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun showBiometricPrompt(
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .setNegativeButtonText("취소")
            .build()

        val prompt = BiometricPrompt(
            this,
            biometricExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    onError("지문을 다시 확인해주세요.")
                }
            }
        )
        prompt.authenticate(promptInfo)
    }

    private fun launchLocalNativeAuth(method: String) {
        val intent = Intent(this, UnlockActivity::class.java).apply {
            putExtra(UnlockActivity.EXTRA_REQUEST_JSON, "")
            putExtra(UnlockActivity.EXTRA_METHOD, method)
        }
        unlockLauncher.launch(intent)
    }

    @Composable
    private fun AuthenticatedApp(
        unlockedState: androidx.compose.runtime.MutableState<Boolean>,
        canUseBiometric: Boolean,
        onBiometricAuth: (
            title: String,
            subtitle: String,
            onSuccess: () -> Unit,
            onError: (String) -> Unit
        ) -> Unit,
        content: @Composable (unlocked: Boolean) -> Unit
    ) {
        LaunchedEffect(Unit) {
            unlockedState.value = true
        }
        content(true)
        return

        val appLockStore = mainViewModel.appContainer.appLockStore
        val lifecycleOwner = LocalLifecycleOwner.current
        var unlocked by unlockedState
        var lockStateLoaded by rememberSaveable { mutableStateOf(false) }
        var setupMode by rememberSaveable { mutableStateOf(false) }
        var authMode by rememberSaveable { mutableStateOf(AuthMode.Pin) }
        val showBiometricInAppLock = false
        var pin by rememberSaveable { mutableStateOf("") }
        var pinConfirm by rememberSaveable { mutableStateOf("") }
        var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
        var patternConfirmMode by rememberSaveable { mutableStateOf(false) }
        val pattern by remember { mutableStateOf(mutableStateListOf<Int>()) }
        val patternConfirm by remember { mutableStateOf(mutableStateListOf<Int>()) }

        LaunchedEffect(Unit) {
            setupMode = !appLockStore.hasAnyLock()
            unlocked = !setupMode && appLockStore.isSessionUnlocked() && !appLockStore.isEmailVerificationRequired()
            authMode = when {
                appLockStore.hasPin() -> AuthMode.Pin
                appLockStore.hasPattern() -> AuthMode.Pattern
                else -> AuthMode.Pin
            }
            lockStateLoaded = true
        }

        LaunchedEffect(unlocked) {
            if (unlocked) {
                val remaining = appLockStore.getSessionRemainingMillis()
                if (remaining > 0L) {
                    delay(remaining)
                }
                if (!setupMode && !appLockStore.isSessionUnlocked()) {
                    unlocked = false
                    errorMessage = "세션이 만료되어 다시 인증이 필요합니다."
                }
            }
        }

        DisposableEffect(lifecycleOwner, setupMode) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && !setupMode) {
                    if (appLockStore.isEmailVerificationRequired()) {
                        unlocked = false
                        appLockStore.clearSession()
                        errorMessage = "인증 5회 실패로 이메일 인증이 필요합니다."
                    } else if (!appLockStore.isSessionUnlocked()) {
                        unlocked = false
                        errorMessage = if (appLockStore.hasAnyLock()) {
                            "세션이 만료되어 다시 인증이 필요합니다."
                        } else {
                            null
                        }
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        BackHandler(enabled = !unlocked) {
            finish()
        }

        if (!lockStateLoaded) {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {}
            return
        }

        if (unlocked) {
            content(true)
            return
        }

        if (setupMode && authMode == AuthMode.Pin) {
            Surface(modifier = Modifier.fillMaxSize()) {
                PinSetupScreen(
                    pin = pin,
                    statusMessage = errorMessage,
                    onDigit = { digit ->
                        if (pin.length < SETUP_PIN_LENGTH) {
                            pin += digit
                            errorMessage = null
                        }
                    },
                    onBackspace = {
                        if (pin.isNotEmpty()) {
                            pin = pin.dropLast(1)
                            errorMessage = null
                        }
                    },
                    onCancel = {
                        if (appLockStore.hasAnyLock()) {
                            setupMode = false
                            pin = ""
                            pinConfirm = ""
                            errorMessage = null
                        } else {
                            finish()
                        }
                    }
                )
                LaunchedEffect(pin) {
                    if (pin.length == SETUP_PIN_LENGTH) {
                        appLockStore.setPin(pin)
                        appLockStore.markSessionUnlocked()
                        unlocked = true
                        setupMode = false
                        pin = ""
                        pinConfirm = ""
                        errorMessage = null
                    }
                }
            }
            return
        }

        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 460.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = if (setupMode) "잠금 방식 설정" else "앱 잠금 해제",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (setupMode) {
                                "PIN 또는 패턴을 설정하고, 필요하면 지문 로그인을 추가합니다."
                            } else {
                                if (appLockStore.isEmailVerificationRequired()) {
                                    "인증 5회 실패로 이메일 인증이 필요합니다."
                                } else {
                                    "등록된 잠금 방식으로 지갑을 엽니다. 남은 시도 ${appLockStore.getRemainingAuthAttempts()}회"
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (setupMode) {
                            AuthModeSelector(
                                currentMode = authMode,
                                pinEnabled = true,
                                patternEnabled = true,
                                onModeSelected = {
                                    authMode = it
                                    errorMessage = null
                                    pin = ""
                                    pinConfirm = ""
                                    pattern.clear()
                                    patternConfirm.clear()
                                    patternConfirmMode = false
                                }
                            )

                            if (authMode == AuthMode.Pattern) {
                                PatternSection(
                                    setupMode = setupMode,
                                    pattern = pattern,
                                    patternConfirm = patternConfirm,
                                    patternConfirmMode = patternConfirmMode,
                                    onPointTapped = { point ->
                                        val target = if (patternConfirmMode) patternConfirm else pattern
                                        if (!target.contains(point)) {
                                            target.add(point)
                                            errorMessage = null
                                        }
                                    },
                                    onClear = {
                                        pattern.clear()
                                        patternConfirm.clear()
                                        patternConfirmMode = false
                                        errorMessage = null
                                    },
                                    onMoveToConfirm = {
                                        patternConfirmMode = true
                                        patternConfirm.clear()
                                        errorMessage = null
                                    }
                                )
                            }

                            errorMessage?.let { message ->
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            if (authMode == AuthMode.Pattern) {
                                Button(
                                    onClick = {
                                        when {
                                            pattern.size < MIN_PATTERN_POINTS -> errorMessage = "패턴은 최소 4개의 점을 연결해야 합니다."
                                            !patternConfirmMode -> errorMessage = "패턴 확인 단계로 이동해주세요."
                                            patternConfirm.size < MIN_PATTERN_POINTS -> errorMessage = "확인용 패턴을 다시 입력해주세요."
                                            pattern.toList() != patternConfirm.toList() -> errorMessage = "패턴 확인 값이 일치하지 않습니다."
                                            else -> {
                                                appLockStore.setPattern(pattern.toList())
                                                unlocked = true
                                                setupMode = false
                                                pattern.clear()
                                                patternConfirm.clear()
                                                patternConfirmMode = false
                                                errorMessage = null
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("패턴 저장")
                                }
                            } else {
                                Text(
                                    text = "PIN은 전체 화면 키패드에서 4자리 입력 시 자동 저장됩니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = !appLockStore.isEmailVerificationRequired(),
                                    onClick = { launchLocalNativeAuth("pin") },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("PIN 로그인")
                                }
                                Button(
                                    enabled = !appLockStore.isEmailVerificationRequired() && canUseBiometric && appLockStore.isBiometricEnabled(),
                                    onClick = { launchLocalNativeAuth("biometric") },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("지문 로그인")
                                }
                            }

                            TextButton(
                                enabled = !appLockStore.isEmailVerificationRequired(),
                                onClick = {
                                    appLockStore.clearPin()
                                    setupMode = true
                                    authMode = AuthMode.Pin
                                    pin = ""
                                    pinConfirm = ""
                                    errorMessage = "테스트용 PIN 재설정을 시작합니다. 새 PIN을 입력하세요."
                                },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("PIN 재설정 (테스트)")
                            }
                        }

                        if (setupMode && showBiometricInAppLock && canUseBiometric) {
                            TextButton(
                                onClick = {
                                    if (authMode == AuthMode.Pin) {
                                        when {
                                            pin.length !in 4..8 -> {
                                                errorMessage = "PIN을 먼저 올바르게 입력해주세요."
                                                return@TextButton
                                            }
                                            pin != pinConfirm -> {
                                                errorMessage = "PIN 확인 값이 일치하지 않습니다."
                                                return@TextButton
                                            }
                                        }
                                        appLockStore.setPin(pin)
                                    } else {
                                        when {
                                            pattern.size < MIN_PATTERN_POINTS -> {
                                                errorMessage = "패턴은 최소 4개의 점을 연결해야 합니다."
                                                return@TextButton
                                            }
                                            !patternConfirmMode || pattern.toList() != patternConfirm.toList() -> {
                                                errorMessage = "패턴 확인을 먼저 완료해주세요."
                                                return@TextButton
                                            }
                                        }
                                        appLockStore.setPattern(pattern.toList())
                                    }
                                    onBiometricAuth(
                                        "지문 로그인 활성화",
                                        "이 기기에서 지문 로그인을 사용합니다.",
                                        {
                                            appLockStore.setBiometricEnabled(true)
                                            appLockStore.markSessionUnlocked()
                                            unlocked = true
                                            setupMode = false
                                            pin = ""
                                            pinConfirm = ""
                                            pattern.clear()
                                            patternConfirm.clear()
                                            patternConfirmMode = false
                                            errorMessage = null
                                        },
                                        { message ->
                                            appLockStore.setBiometricEnabled(false)
                                            unlocked = true
                                            setupMode = false
                                            pin = ""
                                            pinConfirm = ""
                                            pattern.clear()
                                            patternConfirm.clear()
                                            patternConfirmMode = false
                                            errorMessage = if (message == "취소") null else message
                                        }
                                    )
                                },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("저장 후 지문 사용")
                            }
                        } else if (!setupMode && showBiometricInAppLock && canUseBiometric && !appLockStore.isBiometricEnabled()) {
                            TextButton(
                                enabled = !appLockStore.isEmailVerificationRequired(),
                                onClick = {
                                    onBiometricAuth(
                                        "지문 로그인 활성화",
                                        "이 기기에서 지문 로그인을 사용합니다.",
                                        {
                                            appLockStore.setBiometricEnabled(true)
                                            appLockStore.resetAuthFailures()
                                            appLockStore.markSessionUnlocked()
                                            unlocked = true
                                            pin = ""
                                            pattern.clear()
                                            errorMessage = null
                                        },
                                        { message ->
                                            errorMessage = message
                                        }
                                    )
                                },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("지문 로그인 활성화")
                            }
                        }

                        Text(
                            text = "PIN/패턴 정보는 기기 키스토어로 보호된 저장소에 암호화되어 보관됩니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val MIN_PATTERN_POINTS = 4
        private const val PRIMARY_WEB_URL = "https://dev-kyvc.khuoo.synology.me/m/"
        private const val LOCAL_TEST_WEB_URL = "file:///android_asset/index.html"
    }
}

private const val SETUP_PIN_LENGTH = 4
private const val WEB_HOST = "dev-kyvc.khuoo.synology.me"
private const val WEBVIEW_TAG = "KYVC-WebView"
private val WEBVIEW_DIAGNOSTICS_SCRIPT = """
(function() {
  function summarize(selector) {
    var el = selector === 'body' ? document.body : document.querySelector(selector);
    if (!el) return { selector: selector, found: false };
    var style = window.getComputedStyle(el);
    var rect = el.getBoundingClientRect();
    return {
      selector: selector,
      found: true,
      textLength: (el.innerText || '').length,
      rect: {
        x: Math.round(rect.x),
        y: Math.round(rect.y),
        width: Math.round(rect.width),
        height: Math.round(rect.height)
      },
      display: style.display,
      visibility: style.visibility,
      opacity: style.opacity,
      position: style.position,
      overflow: style.overflow,
      background: style.backgroundColor,
      color: style.color,
      transform: style.transform,
      zIndex: style.zIndex
    };
  }
  return JSON.stringify({
    href: location.href,
    title: document.title,
    viewport: {
      innerWidth: window.innerWidth,
      innerHeight: window.innerHeight,
      devicePixelRatio: window.devicePixelRatio
    },
    documentSize: {
      bodyClientWidth: document.body ? document.body.clientWidth : null,
      bodyClientHeight: document.body ? document.body.clientHeight : null,
      scrollWidth: document.documentElement ? document.documentElement.scrollWidth : null,
      scrollHeight: document.documentElement ? document.documentElement.scrollHeight : null
    },
    elements: [
      summarize('html'),
      summarize('body'),
      summarize('.m-shell'),
      summarize('.view.wallet-dark.intro'),
      summarize('.intro-copy'),
      summarize('.intro-cards'),
      summarize('.primary')
      ,summarize('.signup-view'),
      summarize('.signup-content'),
      summarize('.content.scroll'),
      summarize('.signup-steps'),
      summarize('.input-box'),
      summarize('.input-box input'),
      summarize('.bottom-action')
    ]
  });
})();
""".trimIndent()

@Composable
private fun QrScannerOverlay(
    title: String,
    subtitle: String,
    footer: String?,
    onClose: () -> Unit,
    onScanned: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val delivered = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(Unit) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = BarcodeScanning.getClient(options)
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { useCase ->
                useCase.setAnalyzer(analysisExecutor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage == null || delivered.get()) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    scanner.process(inputImage)
                        .addOnSuccessListener { barcodes ->
                            if (delivered.get()) return@addOnSuccessListener
                            val raw = barcodes.firstOrNull()?.rawValue
                            if (!raw.isNullOrBlank() && delivered.compareAndSet(false, true)) {
                                onScanned(raw)
                            }
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                }
            }
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        } catch (_: Exception) {
            onClose()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF071227))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xEE071227))
        )
        Text(
            text = "KYvC Scan",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 22.dp),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 22.dp, top = 14.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.34f))
                .clickable { onClose() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "<",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(128.dp))
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = subtitle,
                color = Color(0xFFE5E7EB),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(58.dp))
            ScanFrame(previewView = previewView)
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = "화면 밝기를 높이고 QR 전체가 프레임 안에 들어오게 맞춰주세요.",
                color = Color(0xFFD1D5DB),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center
            )
            if (!footer.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    text = footer,
                    color = Color(0xFFD5DAE3),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ScanFrame(previewView: PreviewView) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.76f)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(28.dp))
            .border(1.dp, Color.White.copy(alpha = 0.36f), RoundedCornerShape(28.dp)),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.08f))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .height(1.dp)
                .background(Color(0xFF7AA7FF).copy(alpha = 0.72f))
        )
        ScanCorner(Modifier.align(Alignment.TopStart), top = true, start = true)
        ScanCorner(Modifier.align(Alignment.TopEnd), top = true, start = false)
        ScanCorner(Modifier.align(Alignment.BottomStart), top = false, start = true)
        ScanCorner(Modifier.align(Alignment.BottomEnd), top = false, start = false)
    }
}

@Composable
private fun ScanCorner(modifier: Modifier, top: Boolean, start: Boolean) {
    Box(
        modifier = modifier
            .size(58.dp)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .align(if (top) Alignment.TopCenter else Alignment.BottomCenter)
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(Color(0xFF7AA7FF))
        )
        Box(
            modifier = Modifier
                .align(if (start) Alignment.CenterStart else Alignment.CenterEnd)
                .size(width = 4.dp, height = 42.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(Color(0xFF7AA7FF))
        )
    }
}

private data class QrOverlayCopy(
    val title: String,
    val subtitle: String,
    val footer: String?
)

private fun qrOverlayCopy(requestJson: String): QrOverlayCopy {
    val request = runCatching { JSONObject(requestJson) }.getOrNull()
    val mode = request?.optString("_qrMode").orEmpty()
    val actionType = request?.optString("actionType")
        ?.uppercase()
        ?.replace("-", "_")
        ?.replace(" ", "_")
        .orEmpty()
    return when {
        mode == "issue" || "VC_ISSUE" in actionType || "CREDENTIAL" in actionType -> QrOverlayCopy(
            title = "QR 코드를 스캔하세요",
            subtitle = "증명서 발급 QR을 화면 안에 맞춰주세요.",
            footer = null
        )
        mode == "presentation" || "VP_REQUEST" in actionType || "PRESENTATION" in actionType -> QrOverlayCopy(
            title = "QR 코드를 스캔하세요",
            subtitle = "제출 요청 QR을 화면 안에 맞춰주세요.",
            footer = null
        )
        else -> QrOverlayCopy(
            title = "QR 코드를 스캔하세요",
            subtitle = "QR을 화면 안에 맞춰주세요.",
            footer = null
        )
    }
}

private fun CredentialNativeScreen.fallbackAction(): String {
    return when (this) {
        CredentialNativeScreen.IssueComplete -> "REQUEST_CREDENTIAL_ISSUE_COMPLETE"
        CredentialNativeScreen.IssueConfirm -> "REQUEST_CREDENTIAL_ISSUE_CONFIRM"
        CredentialNativeScreen.CredentialDetail -> "REQUEST_CREDENTIAL_DETAIL"
        CredentialNativeScreen.CredentialSubmit -> "REQUEST_CREDENTIAL_SUBMIT"
    }
}

private fun credentialDateOnly(value: String): String {
    val date = value
        .trim()
        .substringBefore("T")
        .substringBefore(" ")
    return if (Regex("""\d{4}-\d{2}-\d{2}""").matches(date)) {
        date.replace("-", ".")
    } else {
        date
    }
}

private fun parseXrpAmount(value: String): BigDecimal {
    val normalized = value
        .replace("XRP", "", ignoreCase = true)
        .replace(",", "")
        .trim()
        .ifBlank { "0" }
    return normalized.toBigDecimalOrNull() ?: BigDecimal.ZERO
}

private fun parseXrpFeeAmount(value: String): BigDecimal {
    val trimmed = value.trim()
    val normalized = trimmed
        .replace("XRP", "", ignoreCase = true)
        .replace("drops", "", ignoreCase = true)
        .replace("drop", "", ignoreCase = true)
        .replace(",", "")
        .trim()
        .ifBlank { "0" }
    val amount = normalized.toBigDecimalOrNull() ?: return BigDecimal.ZERO
    val looksLikeDrops = trimmed.contains("drop", ignoreCase = true) ||
        (!trimmed.contains("XRP", ignoreCase = true) && !normalized.contains(".") && amount >= BigDecimal.ONE)
    return if (looksLikeDrops) amount.movePointLeft(6) else amount
}

private fun calculateUsableXrp(balanceXrp: String, feeXrp: BigDecimal): String {
    val usable = parseXrpAmount(balanceXrp).subtract(feeXrp)
    return if (usable < BigDecimal.ZERO) "0" else usable.stripTrailingZeros().toPlainString()
}

private fun formatXrpLabel(value: String): String {
    return formatXrpAmountLabel(parseXrpAmount(value))
}

private fun formatXrpAmountLabel(value: BigDecimal): String {
    val amount = value.setScale(6, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
    return "$amount XRP"
}

private fun storedCredentialClaims(
    sdJwt: String?,
    vcJwt: String?,
    vcJson: String?,
    selectiveDisclosureJson: String?
): List<Triple<String, String, String>> {
    val claims = linkedMapOf<String, String>()
    val disclosurePaths = selectiveDisclosureJson?.let(::extractDisclosablePaths).orEmpty()
    vcJson?.let { addJsonCredentialClaims(claims, it) }
    vcJwt?.let { addJwtCredentialClaims(claims, it) }
    sdJwt?.let { addSdJwtCredentialClaims(claims, it, disclosurePaths) }
    selectiveDisclosureJson?.let { addSelectiveDisclosureClaims(claims, it) }
    val labelCounts = claims.keys
        .map(::claimDisplayLabel)
        .groupingBy { it }
        .eachCount()
    return claims.entries
        .filter { (_, value) -> value.isNotBlank() }
        .sortedWith(
            compareBy<Map.Entry<String, String>> { claimOrder(it.key) }
                .thenBy { it.key }
        )
        .mapIndexed { index, (key, value) ->
            val label = claimDisplayLabel(key)
            Triple(
                claimIcon(index, key),
                if ((labelCounts[label] ?: 0) > 1) "$label (${normalizeClaimKey(key)})" else label,
                claimDisplayValue(key, value)
            )
        }
}

private fun addJsonCredentialClaims(claims: MutableMap<String, String>, rawJson: String) {
    val json = runCatching { JSONObject(rawJson) }.getOrNull() ?: return
    val subject = json.optJSONObject("credentialSubject") ?: json.optJSONObject("claims") ?: json
    flattenJsonClaims(subject, "", claims)
}

private fun addJwtCredentialClaims(claims: MutableMap<String, String>, jwt: String) {
    val payload = decodeJwtPayloadJson(jwt) ?: return
    val subject = payload.optJSONObject("credentialSubject") ?: payload.optJSONObject("claims") ?: payload
    flattenJsonClaims(subject, "", claims)
}

private fun addSdJwtCredentialClaims(
    claims: MutableMap<String, String>,
    sdJwt: String,
    disclosurePaths: List<String>
) {
    val parts = sdJwt.split("~").filter { it.isNotBlank() }
    parts.firstOrNull()?.let { issuerJwt -> addJwtCredentialClaims(claims, issuerJwt) }
    val issuerPayload = parts.firstOrNull()?.let(::decodeJwtPayloadJson)
    val disclosuresByDigest = parts.drop(1)
        .mapNotNull { disclosure -> decodeJsonDisclosure(disclosure) }
        .associateBy { it.digest }
    issuerPayload?.let { payload ->
        collectSdJwtPayloadDisclosureClaims(payload, "", disclosuresByDigest, claims)
    }
    val usedDisclosurePaths = mutableSetOf<String>()
    parts.drop(1).forEachIndexed { index, disclosure ->
        val array = decodeBase64JsonArray(disclosure) ?: return@forEachIndexed
        if (array.length() >= 3 && array.opt(1) is String) {
            val leafKey = array.optString(1).takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val indexedPath = disclosurePaths.getOrNull(index)
                ?.takeIf { path -> path.substringAfterLast(".") == leafKey || path.endsWith("[].$leafKey") }
            val matchedPath = indexedPath ?: disclosurePaths.firstOrNull { path ->
                path !in usedDisclosurePaths &&
                    (path.substringAfterLast(".") == leafKey || path.endsWith("[].$leafKey"))
            }
            matchedPath?.let { usedDisclosurePaths += it }
            val key = matchedPath ?: "disclosure[$index].$leafKey"
            putClaimValue(claims, key, array.opt(2))
        }
    }
}

private data class DecodedJsonDisclosure(
    val digest: String,
    val key: String?,
    val value: Any?
)

private fun decodeJsonDisclosure(disclosure: String): DecodedJsonDisclosure? {
    val array = decodeBase64JsonArray(disclosure) ?: return null
    if (array.length() < 2) return null
    val key = if (array.length() >= 3 && array.opt(1) is String) array.optString(1).takeIf { it.isNotBlank() } else null
    val value = if (key == null) array.opt(1) else array.opt(2)
    return DecodedJsonDisclosure(
        digest = sdDisclosureDigest(disclosure),
        key = key,
        value = value
    )
}

private fun collectSdJwtPayloadDisclosureClaims(
    value: Any?,
    prefix: String,
    disclosuresByDigest: Map<String, DecodedJsonDisclosure>,
    claims: MutableMap<String, String>
) {
    when (value) {
        is JSONObject -> {
            value.optJSONArray("_sd")?.let { sdArray ->
                for (index in 0 until sdArray.length()) {
                    val disclosure = disclosuresByDigest[sdArray.optString(index)] ?: continue
                    val key = disclosure.key ?: continue
                    val path = if (prefix.isBlank()) key else "$prefix.$key"
                    collectSdJwtDisclosureValueClaims(disclosure.value, path, disclosuresByDigest, claims)
                }
            }
            val arrayDisclosure = value.optString("...").takeIf { it.isNotBlank() }?.let(disclosuresByDigest::get)
            if (arrayDisclosure != null && prefix.isNotBlank()) {
                collectSdJwtDisclosureValueClaims(arrayDisclosure.value, prefix, disclosuresByDigest, claims)
            }
            value.keys().forEach { key ->
                if (key == "_sd" || key == "_sd_alg" || key == "...") return@forEach
                val path = if (prefix.isBlank()) key else "$prefix.$key"
                collectSdJwtPayloadDisclosureClaims(value.opt(key), path, disclosuresByDigest, claims)
            }
        }
        is JSONArray -> {
            for (index in 0 until value.length()) {
                val item = value.opt(index)
                val path = when (item) {
                    is JSONObject, is JSONArray -> "$prefix[$index]"
                    else -> prefix
                }
                collectSdJwtPayloadDisclosureClaims(item, path, disclosuresByDigest, claims)
            }
        }
    }
}

private fun collectSdJwtDisclosureValueClaims(
    value: Any?,
    path: String,
    disclosuresByDigest: Map<String, DecodedJsonDisclosure>,
    claims: MutableMap<String, String>
) {
    when (value) {
        null, JSONObject.NULL -> Unit
        is JSONObject -> {
            value.optJSONArray("_sd")?.let { sdArray ->
                for (index in 0 until sdArray.length()) {
                    val disclosure = disclosuresByDigest[sdArray.optString(index)] ?: continue
                    val key = disclosure.key ?: continue
                    collectSdJwtDisclosureValueClaims(disclosure.value, "$path.$key", disclosuresByDigest, claims)
                }
            }
            val arrayDisclosure = value.optString("...").takeIf { it.isNotBlank() }?.let(disclosuresByDigest::get)
            if (arrayDisclosure != null) {
                collectSdJwtDisclosureValueClaims(arrayDisclosure.value, path, disclosuresByDigest, claims)
            }
            value.keys().forEach { key ->
                if (key == "_sd" || key == "_sd_alg" || key == "...") return@forEach
                collectSdJwtDisclosureValueClaims(value.opt(key), "$path.$key", disclosuresByDigest, claims)
            }
        }
        is JSONArray -> {
            if (value.length() == 0) {
                claims.putIfAbsent(path, "[]")
            }
            for (index in 0 until value.length()) {
                val item = value.opt(index)
                val itemPath = when (item) {
                    is JSONObject, is JSONArray -> "$path[$index]"
                    else -> path
                }
                collectSdJwtDisclosureValueClaims(item, itemPath, disclosuresByDigest, claims)
            }
        }
        else -> claims.putIfAbsent(path, value.toString())
    }
}

private fun extractDisclosablePaths(rawJson: String): List<String> {
    val json = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
    val paths = json.optJSONArray("disclosablePaths")
        ?: json.optJSONArray("requiredDisclosures")
        ?: json.optJSONArray("paths")
        ?: return emptyList()
    return buildList {
        for (index in 0 until paths.length()) {
            paths.optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

private fun addSelectiveDisclosureClaims(claims: MutableMap<String, String>, rawJson: String) {
    val json = runCatching { JSONObject(rawJson) }.getOrNull() ?: return
    listOf("claims", "disclosures", "values", "selectedClaims").forEach { key ->
        json.optJSONObject(key)?.let { flattenJsonClaims(it, "", claims) }
    }
}

private fun flattenJsonClaims(
    json: JSONObject,
    prefix: String,
    claims: MutableMap<String, String>
) {
    json.keys().forEach { key ->
        if (key == "..." || key.startsWith("_sd") || key in CLAIM_METADATA_KEYS) return@forEach
        val path = if (prefix.isBlank()) key else "$prefix.$key"
        putClaimValue(claims, path, json.opt(key))
    }
}

private fun putClaimValue(claims: MutableMap<String, String>, key: String, value: Any?) {
    when (value) {
        null, JSONObject.NULL -> Unit
        is JSONObject -> flattenJsonClaims(value, key, claims)
        is JSONArray -> {
            for (index in 0 until value.length()) {
                val item = value.opt(index)
                val itemKey = "$key[$index]"
                when (item) {
                    is JSONObject -> flattenJsonClaims(item, itemKey, claims)
                    is JSONArray -> claims.putIfAbsent(itemKey, item.toString())
                    null, JSONObject.NULL -> Unit
                    else -> claims.putIfAbsent(itemKey, item.toString())
                }
            }
        }
        else -> claims.putIfAbsent(key, value.toString())
    }
}

private fun decodeJwtPayloadJson(jwt: String): JSONObject? {
    val payload = jwt.split(".").getOrNull(1) ?: return null
    return runCatching { JSONObject(decodeBase64Url(payload)) }.getOrNull()
}

private fun decodeBase64JsonArray(value: String): JSONArray? {
    return runCatching { JSONArray(decodeBase64Url(value)) }.getOrNull()
}

private fun decodeBase64Url(value: String): String {
    val normalized = value.padEnd(value.length + (4 - value.length % 4) % 4, '=')
    return String(Base64.decode(normalized, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
}

private fun sdDisclosureDigest(disclosure: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(disclosure.toByteArray(Charsets.US_ASCII))
    return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

private fun claimIcon(index: Int, key: String): String {
    return when {
        key in COMMON_META_CLAIM_LABELS -> "M"
        key.startsWith("kyc.", ignoreCase = true) -> "K"
        key.contains("legalEntity", ignoreCase = true) -> "법"
        key.contains("representative", ignoreCase = true) -> "대"
        key.contains("beneficial", ignoreCase = true) -> "실"
        key.contains("delegate", ignoreCase = true) || key.contains("delegation", ignoreCase = true) -> "위"
        key.contains("establishmentPurpose", ignoreCase = true) -> "목"
        key.contains("aiAssessment", ignoreCase = true) -> "AI"
        key.contains("documentEvidence", ignoreCase = true) -> "문"
        key.contains("address", ignoreCase = true) -> "주"
        else -> (index + 1).toString()
    }
}

private fun claimDisplayLabel(key: String): String {
    val normalized = normalizeClaimKey(key)
    return CLAIM_LABELS[normalized] ?: when {
        normalized.startsWith("beneficialOwners[].") -> CLAIM_LABELS[normalized] ?: normalized
        normalized.startsWith("documentEvidence[].") -> CLAIM_LABELS[normalized] ?: normalized
        normalized == "name" -> "법인명"
        normalized == "registrationNumber" || normalized == "businessRegistrationNumber" -> "법인등록번호"
        normalized == "type" -> "법인 종류"
        else -> normalized.substringAfterLast(".").substringBefore("[")
    }
}

private fun claimGroupLabel(key: String): String {
    val normalized = normalizeClaimKey(key)
    return when {
        normalized.startsWith("kyc.") -> "KYC 정보"
        normalized.startsWith("legalEntity.") -> "법인 정보"
        normalized.startsWith("representative.") -> "대표자 정보"
        normalized.startsWith("beneficialOwners") -> "실소유자 정보"
        normalized.startsWith("delegate.") || normalized.startsWith("delegation.") -> "대리인/위임 정보"
        normalized.startsWith("establishmentPurpose.") -> "설립목적 정보"
        normalized.startsWith("documentEvidence") -> "문서 증빙 정보"
        normalized.startsWith("extra.") -> "추가 심사 정보"
        else -> "기타 정보"
    }
}

private fun claimDisplayValue(key: String, value: String): String {
    val normalized = normalizeClaimKey(key)
    return when (normalized) {
        "legalEntity.type" -> LEGAL_ENTITY_TYPE_LABELS[value] ?: value
        "kyc.assuranceLevel" -> ASSURANCE_LEVEL_LABELS[value] ?: value
        "documentEvidence[].documentType" -> DOCUMENT_TYPE_LABELS[value] ?: value
        "documentEvidence[].documentClass" -> DOCUMENT_CLASS_LABELS[value] ?: value
        "legalEntity.nonProfit",
        "legalEntity.purposeCheckRequired",
        "delegation.kycApplication",
        "delegation.documentSubmission",
        "delegation.vcReceipt",
        "establishmentPurpose.checked" -> booleanDisplayValue(value)
        else -> value
    }
}

private fun documentDisplayTitle(rawTitle: String, documentType: String, index: Int): String {
    val typeLabel = DOCUMENT_TYPE_LABELS[documentType]
    val fallback = "제출 문서 ${index + 1}"
    val title = rawTitle.ifBlank { typeLabel ?: documentType.ifBlank { fallback } }
    return if (title == documentType && !typeLabel.isNullOrBlank()) typeLabel else title
}

private fun booleanDisplayValue(value: String): String {
    return when (value.lowercase()) {
        "true", "yes", "y", "1" -> "예"
        "false", "no", "n", "0" -> "아니오"
        else -> value
    }
}

private fun normalizeClaimKey(key: String): String {
    return key
        .removePrefix("credentialSubject.")
        .removePrefix("claims.")
        .replace(Regex("""\[\d+]"""), "[]")
}

private fun claimOrder(key: String): Int {
    val normalized = normalizeClaimKey(key)
    return CLAIM_ORDER.indexOf(normalized).takeIf { it >= 0 } ?: Int.MAX_VALUE
}

private val COMMON_META_CLAIM_LABELS = setOf(
    "iss",
    "sub",
    "vct",
    "jti",
    "iat",
    "exp",
    "cnf.kid",
    "credentialStatus.type",
    "credentialStatus.statusId",
    "credentialStatus.credentialType"
)

private val CLAIM_METADATA_KEYS = setOf(
    "proof",
    "credentialStatus",
    "disclosablePaths",
    "requiredDisclosures",
    "paths",
    "selectiveDisclosure"
)

private val CLAIM_ORDER = listOf(
    "iss",
    "sub",
    "vct",
    "jti",
    "iat",
    "exp",
    "cnf.kid",
    "credentialStatus.type",
    "credentialStatus.statusId",
    "credentialStatus.credentialType",
    "kyc.jurisdiction",
    "kyc.assuranceLevel",
    "kyc.verifiedAt",
    "legalEntity.type",
    "legalEntity.name",
    "legalEntity.registrationNumber",
    "legalEntity.nonProfit",
    "legalEntity.purposeCheckRequired",
    "representative.name",
    "representative.birthDate",
    "representative.nationality",
    "representative.englishName",
    "beneficialOwners[].name",
    "beneficialOwners[].birthDate",
    "beneficialOwners[].nationality",
    "beneficialOwners[].englishName",
    "beneficialOwners[].ownershipPercentage",
    "delegate.name",
    "delegate.address",
    "delegate.contact",
    "delegate.identityDigest",
    "delegate.identityDigestAlgorithm",
    "delegate.identityDigestVersion",
    "delegation.kycApplication",
    "delegation.documentSubmission",
    "delegation.vcReceipt",
    "delegation.validFrom",
    "delegation.validUntil",
    "delegation.targetCorporateName",
    "establishmentPurpose.checked",
    "establishmentPurpose.purposeText",
    "extra.aiAssessmentRef.assessmentId",
    "extra.aiAssessmentRef.applicationId",
    "extra.aiAssessmentRef.status",
    "documentEvidence[].documentId",
    "documentEvidence[].documentType",
    "documentEvidence[].documentClass",
    "documentEvidence[].digestSRI",
    "documentEvidence[].mediaType",
    "documentEvidence[].byteSize",
    "documentEvidence[].hashInput",
    "documentEvidence[].evidenceFor"
)

private val CLAIM_LABELS = mapOf(
    "iss" to "발급자 DID",
    "sub" to "소지자 DID",
    "vct" to "Credential 타입",
    "jti" to "Credential ID",
    "iat" to "발급 시각",
    "exp" to "만료 시각",
    "cnf.kid" to "Holder 키 ID",
    "credentialStatus.type" to "상태 조회 방식",
    "credentialStatus.statusId" to "상태 레코드 ID",
    "credentialStatus.credentialType" to "XRPL Credential Type",
    "kyc.jurisdiction" to "관할 국가",
    "kyc.assuranceLevel" to "심사 보증 수준",
    "kyc.verifiedAt" to "검증 완료 시각",
    "legalEntity.type" to "법인 종류",
    "legalEntity.name" to "법인명",
    "legalEntity.registrationNumber" to "법인등록번호",
    "legalEntity.businessRegistrationNumber" to "법인등록번호",
    "legalEntity.nonProfit" to "비영리 여부",
    "legalEntity.purposeCheckRequired" to "설립 목적 증빙 필요",
    "representative.name" to "대표자 이름",
    "representative.birthDate" to "대표자 생년월일",
    "representative.nationality" to "대표자 국적",
    "representative.englishName" to "대표자 영문명",
    "beneficialOwners[].name" to "실소유자 이름",
    "beneficialOwners[].birthDate" to "실소유자 생년월일",
    "beneficialOwners[].nationality" to "실소유자 국적",
    "beneficialOwners[].englishName" to "실소유자 영문명",
    "beneficialOwners[].ownershipPercentage" to "지분율",
    "delegate.name" to "대리인 이름",
    "delegate.address" to "대리인 주소",
    "delegate.contact" to "대리인 연락처",
    "delegate.identityDigest" to "대리인 신원정보 해시",
    "delegate.identityDigestAlgorithm" to "해시 알고리즘",
    "delegate.identityDigestVersion" to "해시 포맷 버전",
    "delegation.kycApplication" to "KYC 신청 권한",
    "delegation.documentSubmission" to "서류 제출 권한",
    "delegation.vcReceipt" to "VC 수령 권한",
    "delegation.validFrom" to "위임 시작일",
    "delegation.validUntil" to "위임 종료일",
    "delegation.targetCorporateName" to "위임 대상 법인명",
    "establishmentPurpose.checked" to "설립 목적 검증 충족",
    "establishmentPurpose.purposeText" to "설립 목적",
    "extra.aiAssessmentRef.assessmentId" to "AI 심사 ID",
    "extra.aiAssessmentRef.applicationId" to "KYC 신청 ID",
    "extra.aiAssessmentRef.status" to "AI 심사 상태",
    "documentEvidence[].documentId" to "증빙 문서 ID",
    "documentEvidence[].documentType" to "문서 종류 코드",
    "documentEvidence[].documentClass" to "원본 문서 분류",
    "documentEvidence[].digestSRI" to "문서 해시값",
    "documentEvidence[].mediaType" to "MIME 타입",
    "documentEvidence[].byteSize" to "문서 크기",
    "documentEvidence[].hashInput" to "해시 입력 기준",
    "documentEvidence[].evidenceFor" to "증빙 대상 Claim"
)

private val LEGAL_ENTITY_TYPE_LABELS = mapOf(
    "STOCK_COMPANY" to "주식회사",
    "LIMITED_COMPANY" to "유한회사",
    "LIMITED_PARTNERSHIP" to "유한합자회사 계열",
    "GENERAL_PARTNERSHIP" to "합명회사 계열",
    "INCORPORATED_ASSOCIATION" to "사단법인",
    "FOUNDATION" to "재단법인",
    "COOPERATIVE" to "협동조합",
    "UNIQUE_NUMBER_ORGANIZATION" to "고유번호 단체",
    "FOREIGN_COMPANY" to "외국회사"
)

private val ASSURANCE_LEVEL_LABELS = mapOf(
    "STANDARD" to "표준",
    "ENHANCED" to "강화",
    "HIGH" to "고수준"
)

private val DOCUMENT_CLASS_LABELS = mapOf(
    "ORIGINAL" to "원본",
    "SUPPORTING" to "증빙",
    "SYSTEM_GENERATED" to "시스템 생성",
    "EXTRACTED" to "추출 데이터"
)

private val DOCUMENT_TYPE_LABELS = mapOf(
    "SHAREHOLDER_LIST" to "주주명부",
    "KR_SHAREHOLDER_REGISTER" to "주주명부",
    "CORPORATE_SEAL_CERTIFICATE" to "법인인감증명서",
    "KR_CORPORATE_SEAL_CERTIFICATE" to "법인인감증명서",
    "KR_SEAL_CERTIFICATE" to "법인인감증명서",
    "CORPORATE_REGISTRY_CERTIFICATE" to "등기사항전부증명서",
    "KR_CORPORATE_REGISTER_FULL_CERTIFICATE" to "등기사항전부증명서",
    "CORPORATE_REGISTER" to "등기사항전부증명서",
    "BUSINESS_REGISTRATION_CERTIFICATE" to "사업자등록증",
    "KR_BUSINESS_REGISTRATION_CERTIFICATE" to "사업자등록증",
    "LEGAL_ENTITY_KYC_CREDENTIAL" to "법인 KYC 증명서",
    "KYC_CREDENTIAL" to "법인 KYC 증명서"
)

private fun JSONObject.childObject(pathPart: String): JSONObject? {
    val arrayMarkerIndex = pathPart.indexOf('[')
    if (arrayMarkerIndex < 0) {
        return optJSONObject(pathPart)
    }
    val key = pathPart.substring(0, arrayMarkerIndex)
    val array = optJSONArray(key) ?: return null
    val explicitIndex = pathPart
        .substringAfter('[', "")
        .substringBefore(']', "")
        .toIntOrNull()
    return array.optJSONObject(explicitIndex ?: 0)
}

private fun JSONObject.childText(pathPart: String): String? {
    val arrayMarkerIndex = pathPart.indexOf('[')
    if (arrayMarkerIndex < 0) {
        return optString(pathPart).takeIf { it.isNotBlank() }
    }
    val key = pathPart.substring(0, arrayMarkerIndex)
    val array = optJSONArray(key) ?: return null
    val explicitIndex = pathPart
        .substringAfter('[', "")
        .substringBefore(']', "")
        .toIntOrNull()
    return array.optString(explicitIndex ?: 0).takeIf { it.isNotBlank() }
}

private data class SubmitIssuerOption(
    val issuerId: String,
    val issuerName: String,
    val credentialId: String,
    val selected: Boolean,
    val submitDocuments: List<SubmitDocumentItem> = emptyList(),
    val submitDisclosures: List<SubmitDisclosureItem> = emptyList()
)

private data class SubmitDocumentItem(
    val documentId: String,
    val title: String,
    val documentType: String,
    val digest: String,
    val fileName: String = "",
    val attachmentRef: String = "",
    val mediaType: String = "",
    val byteSize: Long? = null,
    val required: Boolean,
    val selected: Boolean
)

private data class SubmitDisclosureItem(
    val path: String,
    val title: String,
    val group: String,
    val value: String,
    val required: Boolean,
    val selected: Boolean
)

private data class CredentialUiData(
    val issuerName: String = "-",
    val credentialId: String = "",
    val requesterName: String = "신한은행",
    val credentialTitle: String = "법인등록증명서",
    val submitCredentialTitle: String = "법인 KYC 증명서",
    val holderName: String = "주식회사 케이와이브이씨",
    val registrationNumber: String = "110111-1234567",
    val companyType: String = "주식회사",
    val establishedAt: String = "2020년 3월 15일",
    val representativeName: String = "홍길동",
    val beneficialOwnerName: String = "이현수",
    val agentName: String = "김계와",
    val address: String = "서울특별시 강남구 테헤란로 123",
    val did: String = "DID:kyvc:corp:240315",
    val issuerDid: String = "did:xrpl:1:rpseLKeHEoLDWBnTJvRJgh1mSNz7vJVENc",
    val holderDid: String = "did:xrpl:1:rf7J73nExampleHolderAddress",
    val issuedAt: String = "2026.05.07",
    val issuedAtFull: String = "2026.05.07 14:32",
    val expiresAt: String = "2027.05.06",
    val transactionHash: String = "0x7f3a92e1c4d28b1a...",
    val currentBalanceXrp: String = "0 XRP",
    val networkFeeXrp: String = "0.000012 XRP",
    val usableBalanceXrp: String = "0 XRP",
    val balanceWarning: String = "* 잔액이 부족합니다",
    val statusText: String = "정상 · 검증 가능",
    val didRegistrationLabel: String = "did 등록하기",
    val submitIssuerOptions: List<SubmitIssuerOption> = emptyList(),
    val submitDocuments: List<SubmitDocumentItem> = emptyList(),
    val submitDisclosures: List<SubmitDisclosureItem> = emptyList(),
    val storedClaimRows: List<Triple<String, String, String>> = emptyList()
) {
    companion object {
        fun from(requestJson: String): CredentialUiData {
            val json = runCatching { JSONObject(requestJson) }.getOrNull()
            fun text(name: String, fallback: String): String {
                return json?.optString(name)?.takeIf { it.isNotBlank() } ?: fallback
            }
            fun pathText(path: String): String? {
                val root = json ?: return null
                val parts = path.split(".")
                var current: JSONObject = root
                parts.dropLast(1).forEach { part ->
                    current = current.childObject(part) ?: return null
                }
                return current.childText(parts.last())
            }
            fun deepPathText(path: String): String? {
                val root = json ?: return null
                val candidatePaths = buildList {
                    add(path)
                    add("data.$path")
                    add("offer.$path")
                    add("credentialOffer.$path")
                    add("credentialPayload.$path")
                    add("credentialPayload.metadata.$path")
                    add("credentialPayload.credential.$path")
                    add("credentialPayload.credential.claims.$path")
                    add("credentialPayload.credential.credentialSubject.$path")
                    add("credential.$path")
                    add("credential.claims.$path")
                    add("credential.credentialSubject.$path")
                    add("claims.$path")
                    add("credentialSubject.$path")
                    add("metadata.$path")
                }
                return candidatePaths.firstNotNullOfOrNull { candidate ->
                    val parts = candidate.split(".")
                    var current: JSONObject = root
                    parts.dropLast(1).forEach { part ->
                        current = current.childObject(part) ?: return@firstNotNullOfOrNull null
                    }
                    current.childText(parts.last())
                }
            }
            fun deepPathValue(path: String): Any? {
                val root = json ?: return null
                val candidatePaths = buildList {
                    add(path)
                    add("data.$path")
                    add("offer.$path")
                    add("credentialOffer.$path")
                    add("credentialPayload.$path")
                    add("credentialPayload.metadata.$path")
                    add("credentialPayload.credential.$path")
                    add("credentialPayload.credential.claims.$path")
                    add("credentialPayload.credential.credentialSubject.$path")
                    add("credential.$path")
                    add("credential.claims.$path")
                    add("credential.credentialSubject.$path")
                    add("claims.$path")
                    add("credentialSubject.$path")
                    add("metadata.$path")
                }
                return candidatePaths.firstNotNullOfOrNull { candidate ->
                    val parts = candidate.split(".")
                    var current: JSONObject = root
                    parts.dropLast(1).forEach { part ->
                        current = current.childObject(part) ?: return@firstNotNullOfOrNull null
                    }
                    current.opt(parts.last())?.takeUnless { it == JSONObject.NULL }
                }
            }
            fun deepObjectText(path: String): String? {
                return when (val value = deepPathValue(path)) {
                    is JSONObject -> value.toString()
                    is JSONArray -> value.toString()
                    is String -> value.takeIf { it.isNotBlank() }
                    else -> value?.toString()?.takeIf { it.isNotBlank() }
                }
            }
            val sdJwtClaimMap = linkedMapOf<String, String>().apply {
                val sdJwt = listOf(
                    "sdJwt",
                    "sd_jwt",
                    "credentialPayload.sdJwt",
                    "credentialPayload.credentialJwt",
                    "data.credentialPayload.sdJwt",
                    "data.credentialPayload.credentialJwt",
                    "credentialPayload.credential",
                    "credential"
                ).firstNotNullOfOrNull { path ->
                    deepObjectText(path)?.takeIf { it.contains("~") }
                }
                val selectiveDisclosure = listOf(
                    "selectiveDisclosure",
                    "credentialPayload.selectiveDisclosure",
                    "data.credentialPayload.selectiveDisclosure"
                ).firstNotNullOfOrNull { path ->
                    deepObjectText(path)
                }
                sdJwt?.let { addSdJwtCredentialClaims(this, it, selectiveDisclosure?.let(::extractDisclosablePaths).orEmpty()) }
            }
            fun claimText(vararg keys: String): String? {
                return keys.firstNotNullOfOrNull { key ->
                    sdJwtClaimMap[key]
                        ?: sdJwtClaimMap["claims.$key"]
                        ?: sdJwtClaimMap["credentialSubject.$key"]
                }?.takeIf { it.isNotBlank() }
            }
            fun firstText(fallback: String, vararg names: String): String {
                return names.firstNotNullOfOrNull { name ->
                    if (name.contains(".")) {
                        deepPathText(name) ?: claimText(name)
                    } else {
                        json?.optString(name)?.takeIf { it.isNotBlank() } ?: deepPathText(name) ?: claimText(name)
                    }
                } ?: fallback
            }
            fun firstArray(vararg names: String): JSONArray? {
                return names.firstNotNullOfOrNull { name ->
                    json?.optJSONArray(name)
                }
            }
            fun parseSubmitDocumentsArray(documents: JSONArray?): List<SubmitDocumentItem> {
                if (documents == null) return emptyList()
                return buildList {
                    for (index in 0 until documents.length()) {
                        val item = documents.optJSONObject(index) ?: continue
                        val rawTitle = item.optString("title")
                            .ifBlank { item.optString("name") }
                            .ifBlank { item.optString("documentName") }
                        val documentType = item.optString("documentType")
                            .ifBlank { item.optString("type") }
                            .ifBlank { rawTitle }
                        val title = documentDisplayTitle(rawTitle, documentType, index)
                        val digest = item.optString("digestSRI")
                            .ifBlank { item.optString("hash") }
                            .ifBlank { item.optString("digest") }
                            .ifBlank { item.optString("documentHash") }
                        val fileName = item.optString("fileName")
                            .ifBlank { item.optString("filename") }
                            .ifBlank { item.optString("originalFilename") }
                        val byteSize = if (item.has("byteSize") && !item.isNull("byteSize")) {
                            runCatching { item.getLong("byteSize") }.getOrNull()
                        } else {
                            null
                        }
                        add(
                            SubmitDocumentItem(
                                documentId = item.optString("documentId")
                                    .ifBlank { item.optString("id") }
                                    .ifBlank { "document-${index + 1}" },
                                title = title,
                                documentType = documentType.ifBlank { title },
                                digest = digest.ifBlank { "-" },
                                fileName = fileName,
                                attachmentRef = item.optString("attachmentRef"),
                                mediaType = item.optString("mediaType"),
                                byteSize = byteSize,
                                required = item.optBoolean("required", true),
                                selected = item.optBoolean("selected", true)
                            )
                        )
                    }
                }
            }
            fun parseSubmitDisclosuresArray(disclosures: JSONArray?): List<SubmitDisclosureItem> {
                if (disclosures == null) return emptyList()
                return buildList {
                    for (index in 0 until disclosures.length()) {
                        val item = disclosures.optJSONObject(index) ?: continue
                        val path = item.optString("path")
                            .ifBlank { item.optString("claimPath") }
                            .ifBlank { item.optString("id") }
                        if (path.isBlank()) continue
                        val normalizedPath = normalizeClaimKey(path)
                        val title = item.optString("title")
                            .ifBlank { item.optString("label") }
                            .ifBlank { claimDisplayLabel(path) }
                        val rawValue = item.optString("value")
                            .ifBlank { item.optString("displayValue") }
                            .ifBlank { "-" }
                        add(
                            SubmitDisclosureItem(
                                path = path,
                                title = title,
                                group = item.optString("group")
                                    .ifBlank { claimGroupLabel(normalizedPath) },
                                value = claimDisplayValue(path, rawValue),
                                required = item.optBoolean("required", false),
                                selected = item.optBoolean("selected", item.optBoolean("required", false))
                            )
                        )
                    }
                }
            }
            fun parseIssuerOptions(): List<SubmitIssuerOption> {
                val defaultDocuments = parseSubmitDocumentsArray(firstArray("submitDocuments", "requiredDocuments", "documents", "attachmentDocuments"))
                val defaultDisclosures = parseSubmitDisclosuresArray(firstArray("submitDisclosures", "disclosureClaims", "claimsToDisclose"))
                val issuers = firstArray("issuerOptions", "issuers", "credentialIssuers", "availableIssuers")
                    ?: return listOf(
                        SubmitIssuerOption(
                            issuerId = firstText("default", "issuerId", "issuerAccount", "issuerDid"),
                            issuerName = firstText("-", "issuerName", "issuerDisplayName", "issuerCorporateName", "issuer.name"),
                            credentialId = firstText("", "credentialId", "id", "jti", "credentialPayload.metadata.credentialId", "metadata.credentialId"),
                            selected = true,
                            submitDocuments = defaultDocuments,
                            submitDisclosures = defaultDisclosures
                        )
                    )
                return buildList {
                    for (index in 0 until issuers.length()) {
                        val item = issuers.optJSONObject(index) ?: continue
                        val issuerName = item.optString("issuerName")
                            .ifBlank { item.optString("name") }
                            .ifBlank { item.optString("displayName") }
                            .ifBlank { item.optString("issuerDid") }
                            .ifBlank { item.optString("issuerAccount") }
                            .ifBlank { "-" }
                        val issuerId = item.optString("issuerId")
                            .ifBlank { item.optString("id") }
                            .ifBlank { item.optString("issuerAccount") }
                            .ifBlank { item.optString("issuerDid") }
                            .ifBlank { issuerName }
                        add(
                            SubmitIssuerOption(
                                issuerId = issuerId,
                                issuerName = issuerName,
                                credentialId = item.optString("credentialId").ifBlank { item.optString("id") },
                                selected = item.optBoolean("selected", index == 0),
                                submitDocuments = parseSubmitDocumentsArray(
                                    item.optJSONArray("submitDocuments")
                                        ?: item.optJSONArray("requiredDocuments")
                                        ?: item.optJSONArray("documents")
                                        ?: item.optJSONArray("attachmentDocuments")
                                ),
                                submitDisclosures = parseSubmitDisclosuresArray(
                                    item.optJSONArray("submitDisclosures")
                                        ?: item.optJSONArray("disclosureClaims")
                                        ?: item.optJSONArray("claimsToDisclose")
                                )
                            )
                        )
                    }
                }.ifEmpty {
                    listOf(SubmitIssuerOption("default", "-", "", true))
                }
            }
            fun parseSubmitDocuments(): List<SubmitDocumentItem> {
                val documents = firstArray("submitDocuments", "requiredDocuments", "documents", "attachmentDocuments")
                    ?: return emptyList()
                return buildList {
                    for (index in 0 until documents.length()) {
                        val item = documents.optJSONObject(index) ?: continue
                        val rawTitle = item.optString("title")
                            .ifBlank { item.optString("name") }
                            .ifBlank { item.optString("documentName") }
                        val documentType = item.optString("documentType")
                            .ifBlank { item.optString("type") }
                            .ifBlank { rawTitle }
                        val title = documentDisplayTitle(rawTitle, documentType, index)
                        val digest = item.optString("digestSRI")
                            .ifBlank { item.optString("hash") }
                            .ifBlank { item.optString("digest") }
                            .ifBlank { item.optString("documentHash") }
                        val fileName = item.optString("fileName")
                            .ifBlank { item.optString("filename") }
                            .ifBlank { item.optString("originalFilename") }
                        val byteSize = if (item.has("byteSize") && !item.isNull("byteSize")) {
                            runCatching { item.getLong("byteSize") }.getOrNull()
                        } else {
                            null
                        }
                        add(
                            SubmitDocumentItem(
                                documentId = item.optString("documentId")
                                    .ifBlank { item.optString("id") }
                                    .ifBlank { "document-${index + 1}" },
                                title = title,
                                documentType = documentType.ifBlank { title },
                                digest = digest.ifBlank { "-" },
                                fileName = fileName,
                                attachmentRef = item.optString("attachmentRef"),
                                mediaType = item.optString("mediaType"),
                                byteSize = byteSize,
                                required = item.optBoolean("required", true),
                                selected = item.optBoolean("selected", true)
                            )
                        )
                    }
                }
            }
            fun parseSubmitDisclosures(): List<SubmitDisclosureItem> {
                return parseSubmitDisclosuresArray(firstArray("submitDisclosures", "disclosureClaims", "claimsToDisclose"))
            }
            val fallbackHolderDid = text("holderDid", text("did", "did:xrpl:1:rHolder..."))
            val rawIssuedAt = firstText(
                "-",
                "displayIssuedAt",
                "issuedDate",
                "credentialIssuedAt",
                "validFrom",
                "issuanceDate",
                "issuedAtFull",
                "issuedAt"
            )
            val rawIssuedAtFull = firstText(
                rawIssuedAt,
                "issuedAtFull",
                "credentialIssuedAtFull",
                "credentialIssuedAt",
                "validFrom",
                "issuanceDate",
                "issuedAt"
            )
            val rawExpiresAt = firstText(
                "-",
                "displayExpiresAt",
                "expiresDate",
                "expirationDate",
                "validUntil",
                "expiresAt"
            )
            return CredentialUiData(
                issuerName = firstText("-", "issuerName", "issuerDisplayName", "issuerCorporateName", "issuer.name"),
                credentialId = firstText("", "credentialId", "id", "jti", "credentialPayload.metadata.credentialId", "metadata.credentialId"),
                requesterName = text("requesterName", "-"),
                credentialTitle = text("credentialTitle", "법인 kyc 증명서"),
                submitCredentialTitle = text("submitCredentialTitle", "법인 KYC 증명서"),
                holderName = firstText("-", "legalEntity.name", "holderName", "corporateName", "companyName", "businessName", "legalEntity.corporateName"),
                registrationNumber = firstText("-", "legalEntity.registrationNumber", "legalEntity.businessRegistrationNumber", "registrationNumber", "businessNumber", "businessRegistrationNumber", "corporateRegistrationNumber"),
                companyType = claimDisplayValue("legalEntity.type", firstText("-", "companyType", "corporateType", "legalEntityType", "legalEntity.type", "claims.legalEntity.type", "credentialSubject.legalEntity.type", "legalEntity.companyType", "legalEntity.corporateType")),
                establishedAt = firstText("-", "establishedAt", "establishmentDate", "foundedAt", "legalEntity.establishedAt", "claims.legalEntity.establishedAt", "credentialSubject.legalEntity.establishedAt", "legalEntity.establishmentDate", "legalEntity.foundedAt"),
                representativeName = firstText("-", "representativeName", "ceoName", "legalRepresentativeName", "representative.name", "claims.representative.name", "credentialSubject.representative.name", "representative.fullName", "legalRepresentative.name"),
                beneficialOwnerName = firstText("-", "beneficialOwnerName", "beneficialOwner", "ownerName", "beneficialOwner.name", "beneficialOwner.fullName", "beneficialOwners[].name", "beneficialOwners[0].name", "claims.beneficialOwners[].name", "claims.beneficialOwners[0].name", "credentialSubject.beneficialOwners[].name", "credentialSubject.beneficialOwners[0].name", "beneficialOwners[].fullName", "beneficialOwners[0].fullName"),
                agentName = firstText("-", "agentName", "delegateName", "representativeAgentName", "agent.name", "agent.fullName", "delegate.name", "claims.delegate.name", "credentialSubject.delegate.name", "delegate.fullName", "authorizedAgent.name", "authorizedAgent.fullName", "proxy.name", "proxy.fullName"),
                address = firstText("-", "address", "corporateAddress", "businessAddress", "legalEntity.address", "claims.legalEntity.address", "credentialSubject.legalEntity.address", "legalEntity.businessAddress", "legalEntity.headOfficeAddress"),
                did = fallbackHolderDid,
                issuerDid = text("issuerDid", "did:xrpl:1:rpseLKeHEoLDWBnTJvRJgh1mSNz7vJVENc"),
                holderDid = fallbackHolderDid,
                issuedAt = credentialDateOnly(rawIssuedAt),
                issuedAtFull = rawIssuedAtFull,
                expiresAt = rawExpiresAt,
                transactionHash = text("transactionHash", "0x7f3a92e1c4d28b1a..."),
                currentBalanceXrp = text("currentBalanceXrp", text("balanceXrp", "0 XRP")),
                networkFeeXrp = text("networkFeeXrp", text("feeXrp", "0.000012 XRP")),
                usableBalanceXrp = text("usableBalanceXrp", text("availableBalanceXrp", "0 XRP")),
                balanceWarning = text("balanceWarning", "* 잔액이 부족합니다"),
                statusText = text("statusText", "정상 · 검증 가능"),
                didRegistrationLabel = text("didRegistrationLabel", "did 등록하기"),
                submitIssuerOptions = parseIssuerOptions(),
                submitDocuments = parseSubmitDocuments(),
                submitDisclosures = parseSubmitDisclosures()
            )
        }

        fun defaultSubmitDocuments(): List<SubmitDocumentItem> {
            return emptyList()
        }
    }
}

@Composable
private fun NativeCredentialFlowScreen(
    screen: CredentialNativeScreen,
    data: CredentialUiData,
    onBack: () -> Unit,
    onResult: (String) -> Unit
) {
    when (screen) {
        CredentialNativeScreen.IssueComplete -> IssueCompleteScreen(data, onResult)
        CredentialNativeScreen.IssueConfirm -> IssueConfirmScreen(data, onBack = onBack, onResult = onResult)
        CredentialNativeScreen.CredentialDetail -> CredentialDetailScreen(data, onBack = onBack, onResult = onResult)
        CredentialNativeScreen.CredentialSubmit -> CredentialSubmitScreen(data, onBack = onBack, onResult = onResult)
    }
}

@Composable
private fun CredentialNativeScaffold(
    title: String,
    onBack: (() -> Unit)?,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFBFAFF))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (bottomBar == null) 0.dp else 118.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            CredentialTopBar(title = title, onBack = onBack)
            Spacer(modifier = Modifier.height(26.dp))
            content()
            Spacer(modifier = Modifier.height(28.dp))
        }
        if (bottomBar != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.White)
                    .border(1.dp, Color(0xFFECECEA))
                    .padding(horizontal = 22.dp, vertical = 10.dp)
                    .navigationBarsPadding()
            ) {
                bottomBar()
            }
        }
    }
}

@Composable
private fun CredentialTopBar(
    title: String,
    onBack: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF5F5F3))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Text("<", color = Color(0xFF0B1D40), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        } else {
            Spacer(modifier = Modifier.size(34.dp))
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(title, color = Color(0xFF0B1D40), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(modifier = Modifier.size(34.dp))
    }
}

@Composable
private fun IssueCompleteScreen(
    data: CredentialUiData,
    onResult: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .navigationBarsPadding()
            .padding(horizontal = 29.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(bottom = 90.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF6885FE), Color(0xFF9A74F8)))),
                contentAlignment = Alignment.Center
            ) {
                Text("✓", color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text("발급 완료", color = Color(0xFF0B1D40), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                "${data.credentialTitle}가 지갑에 안전하게 저장되었어요.",
                color = Color(0xFF8A93A3),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(28.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFFECECEA), RoundedCornerShape(16.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                SummaryLine("발급 기관", data.issuerName, strong = true)
                SummaryLine("증명서", data.credentialTitle, strong = true)
                SummaryLine("발급 시각", data.issuedAtFull, strong = true)
                SummaryLine("트랜잭션", data.transactionHash, color = Color(0xFF2F7DFF), strong = true)
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilledActionButton("증명서 보기") { onResult("viewCredential") }
            OutlinedActionButton("홈으로") { onResult("home") }
        }
    }
}

@Composable
private fun IssueConfirmScreen(
    data: CredentialUiData,
    onBack: () -> Unit,
    onResult: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 138.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 14.dp)
        ) {
            CredentialTopBar(title = "발급 확인", onBack = onBack)
            Spacer(modifier = Modifier.height(34.dp))
            Text("발급 확인", color = Color(0xFF0B1D40), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Spacer(modifier = Modifier.height(12.dp))
            Text("증명서 내용을 확인해주세요.", color = Color(0xFF8A93A3), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(20.dp))
            Text("발급 상세 정보", color = Color(0xFF111827), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            Spacer(modifier = Modifier.height(10.dp))
            IssueConfirmDetailCard(data)
            Spacer(modifier = Modifier.height(12.dp))
            IssueConfirmBalanceCard(data)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 22.dp, vertical = 10.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FilledActionButton("발급 확인") { onResult("confirm") }
            OutlinedActionButton("거부") { onResult("reject") }
        }
    }
}

@Composable
private fun IssueConfirmDetailCard(data: CredentialUiData) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFFE8E8E8), RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        IssueConfirmInfoRow("발급 기관", data.issuerName.ifBlank { "-" })
        IssueConfirmInfoRow("법인유형", data.companyType)
        IssueConfirmInfoRow("법인명", data.holderName)
        IssueConfirmInfoRow("법인등록번호", data.registrationNumber)
        IssueConfirmInfoRow("대표자", data.representativeName)
        IssueConfirmInfoRow("실소유자", data.beneficialOwnerName)
        IssueConfirmInfoRow("대리인", data.agentName)
    }
}

@Composable
private fun IssueConfirmInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Color(0xFFB0B5BE), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 82.dp))
        Spacer(modifier = Modifier.size(10.dp))
        Text(text = value, color = Color(0xFF111827), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun IssueConfirmBalanceCard(data: CredentialUiData) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFFE8E8E8), RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(horizontal = 27.dp, vertical = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("현재 잔액", color = Color(0xFF8A93A3), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
            Spacer(modifier = Modifier.size(4.dp))
            Text(data.balanceWarning, color = Color(0xFFFF3B30), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(modifier = Modifier.height(7.dp))
        Text(data.currentBalanceXrp, color = Color(0xFF0B2A55), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Spacer(modifier = Modifier.height(17.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFECECEA)))
        Spacer(modifier = Modifier.height(16.dp))
        Text("네트워크 수수료", color = Color(0xFF8A93A3), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
        Spacer(modifier = Modifier.height(7.dp))
        Text(data.networkFeeXrp, color = Color(0xFF0B2A55), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        Spacer(modifier = Modifier.height(17.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFECECEA)))
        Spacer(modifier = Modifier.height(16.dp))
        Text("등록 후 사용 가능 잔액", color = Color(0xFF8A93A3), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
        Spacer(modifier = Modifier.height(7.dp))
        Text(data.usableBalanceXrp, color = Color(0xFF2F7DFF), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun CredentialDetailScreen(
    data: CredentialUiData,
    onBack: () -> Unit,
    onResult: (String) -> Unit
) {
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    CredentialNativeScaffold(
        title = "증명서 상세",
        onBack = onBack,
        bottomBar = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledActionButton("내 QR 보기") { onResult("showQr") }
                DestructiveOutlinedActionButton("증명서 삭제") { showDeleteConfirm = true }
            }
        }
    ) {
        CredentialCard(data, showVerified = true)
        Spacer(modifier = Modifier.height(20.dp))
        InfoRowCard("발", "발급기관", data.issuerName)
        Spacer(modifier = Modifier.height(12.dp))
        InfoRowCard("유", "유효기간", "${data.issuedAt} - ${credentialDateOnly(data.expiresAt)}")
        Spacer(modifier = Modifier.height(12.dp))
        InfoRowCard("✓", "상태", data.statusText)
        Spacer(modifier = Modifier.height(12.dp))
        val claimRows = credentialDetailClaimRows(data)
        claimRows.forEachIndexed { index, item ->
            InfoRowCard(item.first, item.second, item.third)
            if (index != claimRows.lastIndex) {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    if (showDeleteConfirm) {
        DeleteCredentialConfirmDialog(
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                onResult("delete")
            }
        )
    }
}

private fun credentialDetailClaimRows(data: CredentialUiData): List<Triple<String, String, String>> {
    if (data.storedClaimRows.isNotEmpty()) {
        return data.storedClaimRows
    }
    return listOf(
        Triple("증", "증명서명", "법인 kyc 증명서"),
        Triple("법", "법인명", data.holderName),
        Triple("번", "법인등록번호", data.registrationNumber),
        Triple("유", "법인유형", data.companyType),
        Triple("설", "설립일", data.establishedAt),
        Triple("대", "대표자", data.representativeName),
        Triple("실", "실소유자", data.beneficialOwnerName),
        Triple("위", "대리인", data.agentName),
        Triple("주", "주소", data.address),
        Triple("I", "Issuer DID", data.issuerDid),
        Triple("H", "Holder DID", data.holderDid),
        Triple("Tx", "트랜잭션", data.transactionHash)
    )
}

@Composable
private fun CredentialSubmitScreen(
    data: CredentialUiData,
    onBack: () -> Unit,
    onResult: (String) -> Unit
) {
    val issuerOptions = data.submitIssuerOptions.ifEmpty {
        listOf(SubmitIssuerOption("default", data.issuerName, data.credentialId, true))
    }
    val baseSubmitDocuments = data.submitDocuments.ifEmpty {
        emptyList()
    }
    val baseSubmitDisclosures = data.submitDisclosures
    var selectedIssuerId by remember(data) {
        mutableStateOf(issuerOptions.firstOrNull { it.selected }?.issuerId ?: issuerOptions.first().issuerId)
    }
    var issuerDropdownExpanded by remember(data) { mutableStateOf(false) }
    val selectedIssuer = issuerOptions.firstOrNull { it.issuerId == selectedIssuerId } ?: issuerOptions.first()
    val submitDocuments = selectedIssuer.submitDocuments.ifEmpty { baseSubmitDocuments }
    val submitDisclosures = selectedIssuer.submitDisclosures.ifEmpty { baseSubmitDisclosures }
    val selectedDocumentIds = remember(data, selectedIssuerId) {
        mutableStateListOf<String>().apply {
            addAll(submitDocuments.filter { it.selected || it.required }.map { it.documentId })
        }
    }
    val selectedDisclosurePaths = remember(data, selectedIssuerId) {
        mutableStateListOf<String>().apply {
            addAll(submitDisclosures.filter { it.selected || it.required }.map { it.path })
        }
    }
    var expandedDocumentId by remember(data) { mutableStateOf<String?>(null) }
    val selectedCount = selectedDocumentIds.size
    val requiredDisclosureCount = submitDisclosures.count { it.required }
    val optionalDisclosureCount = submitDisclosures.count { !it.required }
    val selectedOptionalDisclosureCount = submitDisclosures.count { !it.required && it.path in selectedDisclosurePaths }

    CredentialNativeScaffold(
        title = "증명서 제출",
        onBack = onBack,
        bottomBar = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledActionButton("증명서 제출하기") {
                    onResult(
                        JSONObject().apply {
                            put("result", "submit")
                            put("selectedIssuerId", selectedIssuer.issuerId)
                            put("selectedIssuerName", selectedIssuer.issuerName)
                            put("selectedCredentialId", selectedIssuer.credentialId)
                            put(
                                "selectedDocuments",
                                JSONArray(
                                    submitDocuments
                                        .filter { it.documentId in selectedDocumentIds }
                                        .map { document ->
                                            JSONObject().apply {
                                                put("documentId", document.documentId)
                                                put("documentType", document.documentType)
                                                put("title", document.title)
                                                put("digest", document.digest)
                                                put("fileName", document.fileName)
                                                put("attachmentRef", document.attachmentRef)
                                                put("mediaType", document.mediaType)
                                                document.byteSize?.let { put("byteSize", it) }
                                            }
                                        }
                                )
                            )
                            put(
                                "selectedDisclosures",
                                JSONArray(
                                    submitDisclosures
                                        .filter { it.path in selectedDisclosurePaths || it.required }
                                        .map { it.path }
                                )
                            )
                            put(
                                "selectedDisclosureClaims",
                                JSONArray(
                                    submitDisclosures
                                        .filter { it.path in selectedDisclosurePaths || it.required }
                                        .map { disclosure ->
                                            JSONObject().apply {
                                                put("path", disclosure.path)
                                                put("title", disclosure.title)
                                                put("required", disclosure.required)
                                            }
                                        }
                                )
                            )
                        }.toString()
                    )
                }
                OutlinedActionButton("거부") { onResult("reject") }
            }
        }
    ) {
        RequesterCard(data)
        Spacer(modifier = Modifier.height(22.dp))
        Text("${data.requesterName}이 요청한 필수 정보만 추렸어요.", color = Color(0xFF8A93A3), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(22.dp))
        Text("발급기관 선택", color = Color(0xFF0B1D40), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        Spacer(modifier = Modifier.height(10.dp))
        IssuerDropdownSelect(
            selectedIssuer = selectedIssuer,
            issuerOptions = issuerOptions,
            expanded = issuerDropdownExpanded,
            onExpandedChange = { issuerDropdownExpanded = it },
            onIssuerSelected = { issuer ->
                selectedIssuerId = issuer.issuerId
                issuerDropdownExpanded = false
            }
        )
        Spacer(modifier = Modifier.height(22.dp))
        Text("제출할 증명서 (${selectedCount}건)", color = Color(0xFF0B1D40), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        Spacer(modifier = Modifier.height(10.dp))
        submitDocuments.forEachIndexed { index, document ->
            SubmitDocumentRow(
                document = document,
                issuerName = selectedIssuer.issuerName,
                selected = document.documentId in selectedDocumentIds,
                expanded = expandedDocumentId == document.documentId,
                onClick = {
                    expandedDocumentId = if (expandedDocumentId == document.documentId) null else document.documentId
                },
                onToggle = {
                    if (!document.required) {
                        if (document.documentId in selectedDocumentIds) {
                            selectedDocumentIds.remove(document.documentId)
                        } else {
                            selectedDocumentIds.add(document.documentId)
                        }
                    }
                }
            )
            if (index != submitDocuments.lastIndex) {
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
        Spacer(modifier = Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("공개할 세부 정보", color = Color(0xFF0B1D40), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                "필수 ${requiredDisclosureCount}건 · 선택 ${selectedOptionalDisclosureCount}/${optionalDisclosureCount}건",
                color = Color(0xFF8A93A3),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (submitDisclosures.isNotEmpty()) {
            val disclosureGroups = submitDisclosures.groupBy { it.group }
            disclosureGroups.entries.forEachIndexed { index, group ->
                DisclosureGroup(
                    title = group.key,
                    items = group.value,
                    selectedPaths = selectedDisclosurePaths,
                    onToggle = { disclosure ->
                        if (!disclosure.required) {
                            if (disclosure.path in selectedDisclosurePaths) {
                                selectedDisclosurePaths.remove(disclosure.path)
                            } else {
                                selectedDisclosurePaths.add(disclosure.path)
                            }
                        }
                    }
                )
                if (index != disclosureGroups.size - 1) {
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }
        }
        if (submitDisclosures.isEmpty()) {
            InfoNotice("공개할 세부 정보 없음", "제출 요청에서 선택 가능한 claim 정보를 찾지 못했습니다.")
        }
        Spacer(modifier = Modifier.height(20.dp))
        WarningSubmitNotice()
    }
}

@Composable
private fun CredentialCard(
    data: CredentialUiData,
    showVerified: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(192.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF111827), Color(0xFF183B8F), Color(0xFF7C3AED))))
            .padding(18.dp)
    ) {
        Column(modifier = Modifier.align(Alignment.TopStart).padding(end = 86.dp)) {
            Text(
                "Issuer DID",
                color = Color.White.copy(alpha = 0.58f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                data.issuerDid,
                color = Color.White.copy(alpha = 0.84f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (showVerified) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.18f))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text("검증됨", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
            }
        }
        Text(
            "법인 kyc 증명서",
            modifier = Modifier.align(Alignment.CenterStart),
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold
        )
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 14.dp)) {
                Text(
                    "Holder DID",
                    color = Color.White.copy(alpha = 0.58f),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    data.holderDid,
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                CredentialCardDateRow("발급일", credentialDateOnly(data.issuedAt))
                CredentialCardDateRow("만료일", credentialDateOnly(data.expiresAt))
            }
        }
    }
}

@Composable
private fun CredentialCardDateRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.widthIn(min = 30.dp),
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start
        )
        Spacer(modifier = Modifier.size(3.dp))
        Text(
            value,
            modifier = Modifier.widthIn(min = 58.dp),
            color = Color.White.copy(alpha = 0.86f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun IssuerRequestCard(data: CredentialUiData) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .padding(24.dp)
    ) {
        Text("발급기관", color = Color(0xFF8A93A3), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LetterIcon("법")
            Spacer(modifier = Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(data.issuerName, color = Color(0xFF0B1D40), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text("${data.credentialTitle} 발급 요청", color = Color(0xFF8A93A3), style = MaterialTheme.typography.bodyMedium)
            }
            VerifiedPill()
        }
    }
}

@Composable
private fun RequesterCard(data: CredentialUiData) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .padding(20.dp)
    ) {
        Text("요청 기관", color = Color(0xFF8A93A3), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
        Spacer(modifier = Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LetterIcon("S", small = true)
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(data.requesterName, color = Color(0xFF0B1D40), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                Text("법인계좌 개설용 인증", color = Color(0xFF8A93A3), style = MaterialTheme.typography.bodySmall)
            }
            VerifiedPill()
        }
    }
}

@Composable
private fun CredentialDetailList(items: List<Pair<String, String>>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, Color(0xFFECECEA), RoundedCornerShape(18.dp))
            .background(Color.White)
            .padding(horizontal = 22.dp)
    ) {
        items.forEachIndexed { index, item ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
                Text(item.first, color = Color(0xFF8A93A3), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    item.second,
                    color = if (item.first.contains("DID") || item.first == "트랜잭션") Color(0xFF2F7DFF) else Color(0xFF0B1D40),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            if (index != items.lastIndex) {
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFECECEA)))
            }
        }
    }
}

@Composable
private fun InfoRowCard(letter: String, title: String, body: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFFECECEA), RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LetterIcon(letter, small = true)
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color(0xFF0B1D40), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                body,
                color = Color(0xFF6B7280),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun InfoNotice(title: String, body: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, Color(0xFFE5EDFF), RoundedCornerShape(18.dp))
            .background(Color.White)
            .padding(22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("♡", color = Color(0xFF2F7DFF), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Spacer(modifier = Modifier.size(18.dp))
        Column {
            Text(title, color = Color(0xFF0B1D40), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(body, color = Color(0xFF8A93A3), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SelectBox(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(2.dp, Color(0xFFE5E7EB), RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = Color(0xFF8A93A3), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        DropdownChevron(color = Color(0xFF8A93A3))
    }
}

@Composable
private fun IssuerDropdownSelect(
    selectedIssuer: SubmitIssuerOption,
    issuerOptions: List<SubmitIssuerOption>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onIssuerSelected: (SubmitIssuerOption) -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(14.dp))
                .background(Color.White)
                .clickable { onExpandedChange(!expanded) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LetterIcon(selectedIssuer.issuerName.take(1).ifBlank { "발" }, small = true)
            Spacer(modifier = Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(selectedIssuer.issuerName, color = Color(0xFF0B1D40), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold)
                Text(
                    selectedIssuer.credentialId.ifBlank { "발급기관별 1개 증명서" },
                    color = Color(0xFF8A93A3),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            DropdownChevron(color = Color(0xFF6B7280))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.fillMaxWidth(0.9f).background(Color.White)
        ) {
            issuerOptions.forEach { issuer ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(issuer.issuerName, color = Color(0xFF0B1D40), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold)
                            Text(
                                issuer.credentialId.ifBlank { "발급기관별 1개 증명서" },
                                color = Color(0xFF8A93A3),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    onClick = { onIssuerSelected(issuer) }
                )
            }
        }
    }
}

@Composable
private fun IssuerSelectRow(
    issuer: SubmitIssuerOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Color(0xFF2F7DFF) else Color(0xFFE5E7EB),
                shape = RoundedCornerShape(16.dp)
            )
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LetterIcon(issuer.issuerName.take(1).ifBlank { "발" }, small = true)
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(issuer.issuerName, color = Color(0xFF0B1D40), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            Text(
                issuer.credentialId.ifBlank { "발급기관별 1개 증명서" },
                color = Color(0xFF8A93A3),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Text("✓", color = Color(0xFF2F7DFF), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun SubmitDocumentRow(
    document: SubmitDocumentItem,
    issuerName: String,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit
) {
    val requiredLabel = if (document.required) "필수" else "선택"
    val fileName = document.fileName.ifBlank { "-" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, Color(0xFFECECEA), RoundedCornerShape(18.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) Color(0xFFA8B7FF) else Color(0xFFE5E7EB)),
                contentAlignment = Alignment.Center
            ) {
                Text("문", color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    document.title,
                    color = Color(0xFF0B1D40),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "제출 파일: $fileName",
                    color = Color(0xFF5F6877),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOf(issuerName, requiredLabel).joinToString(" · "),
                    color = Color(0xFF8A93A3),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color(0xFF2F7DFF) else Color(0xFFF3F4F6))
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center
            ) {
                Text(if (selected) "✓" else "", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            }
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(10.dp))
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFECECEA)))
            Spacer(modifier = Modifier.height(10.dp))
            DocumentMetaLine("제출 문서", document.title)
            DocumentMetaLine("실제 파일명", fileName)
            if (document.attachmentRef.isNotBlank()) {
                DocumentMetaLine("파일 part name", document.attachmentRef)
            }
            DocumentMetaLine("문서 유형", document.documentType)
            DocumentMetaLine("문서 ID", document.documentId)
            if (document.mediaType.isNotBlank()) {
                DocumentMetaLine("MIME 타입", document.mediaType)
            }
            document.byteSize?.let {
                DocumentMetaLine("파일 크기", "$it bytes")
            }
            DocumentMetaLine("문서 해시", document.digest)
        }
    }
}

@Composable
private fun DocumentMetaLine(label: String, value: String) {
    if (value.isBlank()) return
    Spacer(modifier = Modifier.height(6.dp))
    Text(label, color = Color(0xFF8A93A3), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold)
    Text(
        value,
        color = Color(0xFF2F7DFF),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun SubmitCredentialRow(title: String, issuer: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(86.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, Color(0xFFECECEA), RoundedCornerShape(18.dp))
            .background(Color.White)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(color))
        Spacer(modifier = Modifier.size(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color(0xFF0B1D40), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            Text(issuer, color = Color(0xFF8A93A3), style = MaterialTheme.typography.bodyMedium)
        }
        Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF2F7DFF)), contentAlignment = Alignment.Center) {
            Text("✓", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun DisclosureGroup(
    title: String,
    items: List<SubmitDisclosureItem>,
    selectedPaths: List<String>,
    onToggle: (SubmitDisclosureItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(Color(0xFFF6F6F5))
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = Color(0xFF8A93A3), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Text("${items.count { it.required }} 필수", color = Color(0xFF2F7DFF), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
        }
        items.forEachIndexed { index, item ->
            val selected = item.required || item.path in selectedPaths
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 62.dp)
                    .clickable(enabled = !item.required) { onToggle(item) }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(8.dp))
                        .background(if (selected) Color(0xFF2F7DFF) else Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) Text("✓", color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(modifier = Modifier.size(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.title, color = Color(0xFF263445), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold)
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        item.value,
                        color = Color(0xFF6B7280),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (item.required) Color(0xFFF1F6FF) else Color(0xFFF3F4F6))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(if (item.required) "필수" else "선택", color = if (item.required) Color(0xFF2F7DFF) else Color(0xFF8A93A3), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
                }
            }
            if (index != items.lastIndex) Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFECECEA)))
        }
    }
}

@Composable
private fun DisclosureGroup(title: String, items: List<Pair<String, Boolean>>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(Color(0xFFF6F6F5))
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = Color(0xFF8A93A3), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            DropdownChevron(color = Color(0xFF8A93A3))
        }
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(8.dp))
                        .background(if (item.second) Color(0xFF2F7DFF) else Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.second) Text("✓", color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(modifier = Modifier.size(14.dp))
                Text(item.first, color = Color(0xFF263445), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFFF1F6FF))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(if (item.second) "필수" else "선택", color = if (item.second) Color(0xFF2F7DFF) else Color(0xFF8A93A3), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
                }
            }
            if (index != items.lastIndex) Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFECECEA)))
        }
    }
}

@Composable
private fun DropdownChevron(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(20.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(end = 7.dp)
                .size(width = 10.dp, height = 2.dp)
                .rotate(43f)
                .clip(RoundedCornerShape(99.dp))
                .background(color)
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(start = 7.dp)
                .size(width = 10.dp, height = 2.dp)
                .rotate(-43f)
                .clip(RoundedCornerShape(99.dp))
                .background(color)
        )
    }
}

@Composable
private fun WarningSubmitNotice() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFFFF4CC))
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SubmitLockIcon(color = Color(0xFFA34D00))
        Spacer(modifier = Modifier.size(16.dp))
        Column {
            Text("제출 시 동의 사항", color = Color(0xFFA34D00), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold)
            Spacer(modifier = Modifier.height(6.dp))
            Text("선택한 증명서가 제출되며, 블록체인에 제출 기록이 남습니다.", color = Color(0xFFD97706), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SubmitLockIcon(color: Color) {
    Box(
        modifier = Modifier.size(25.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 2.dp)
                .size(width = 13.dp, height = 13.dp)
                .border(2.dp, color, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .size(width = 18.dp, height = 14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFFFF4CC))
                .border(2.dp, color, RoundedCornerShape(4.dp))
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 5.dp)
                .size(width = 2.dp, height = 5.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(color)
        )
    }
}

@Composable
private fun LetterIcon(letter: String, small: Boolean = false) {
    Box(
        modifier = Modifier
            .size(if (small) 40.dp else 56.dp)
            .clip(RoundedCornerShape(if (small) 14.dp else 16.dp))
            .background(Color(0xFF635BEE)),
        contentAlignment = Alignment.Center
    ) {
        Text(letter, color = Color.White, style = if (small) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun VerifiedPill() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFFEFFFF8))
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("✓", color = Color(0xFF009B72), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold)
        Spacer(modifier = Modifier.size(6.dp))
        Text("인증됨", color = Color(0xFF009B72), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun SummaryLine(label: String, value: String, color: Color = Color(0xFF0B1D40), strong: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFF8A93A3), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = if (strong) FontWeight.ExtraBold else FontWeight.Normal)
    }
}

@Composable
private fun FilledActionButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0B2A55))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun OutlinedActionButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFFECECEA), RoundedCornerShape(14.dp))
            .background(Color.White)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color(0xFF8A93A3), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun DestructiveOutlinedActionButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFFFFD7D7), RoundedCornerShape(14.dp))
            .background(Color.White)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color(0xFFD92D20), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun DeleteCredentialConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White)
                .border(1.dp, Color(0xFFECECEA), RoundedCornerShape(18.dp))
                .padding(horizontal = 22.dp, vertical = 20.dp)
        ) {
            Text(
                "증명서 삭제",
                color = Color(0xFF0B1D40),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "이 증명서를 지갑에서 삭제하시겠습니까? 삭제된 증명서는 복구할 수 없으며, 다시 사용하려면 새로 발급받아야 합니다.",
                color = Color(0xFF6B7280),
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
            )
            Spacer(modifier = Modifier.height(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DestructiveFilledActionButton("삭제하기", onConfirm)
                OutlinedActionButton("취소", onDismiss)
            }
        }
    }
}

@Composable
private fun VpLoginCredentialPickerDialog(
    request: VpLoginCredentialPickerRequest,
    onClose: () -> Unit,
    onSelected: (String) -> Unit
) {
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White)
                .border(1.dp, Color(0xFFECECEA), RoundedCornerShape(18.dp))
                .padding(horizontal = 22.dp, vertical = 20.dp)
        ) {
            Text(
                request.title,
                color = Color(0xFF0B1D40),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "PC 로그인에 제출할 법인 KYC 증명서를 선택해주세요.",
                color = Color(0xFF6B7280),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            request.options.forEachIndexed { index, option ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, Color(0xFFECECEA), RoundedCornerShape(14.dp))
                        .background(Color.White)
                        .clickable { onSelected(option.first) }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Column {
                        Text(
                            option.second,
                            color = Color(0xFF0B1D40),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Credential ID ${option.first}",
                            color = Color(0xFF8A93A3),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                if (index != request.options.lastIndex) {
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedActionButton("취소", onClose)
        }
    }
}

@Composable
private fun VpLoginCompletionDialog(
    message: String,
    onClose: () -> Unit
) {
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White)
                .border(1.dp, Color(0xFFECECEA), RoundedCornerShape(18.dp))
                .padding(horizontal = 22.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("VP 제출 완료", color = Color(0xFF0B1D40), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            Spacer(modifier = Modifier.height(10.dp))
            Text(message, color = Color(0xFF6B7280), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(18.dp))
            FilledActionButton("확인", onClose)
        }
    }
}

@Composable
private fun DestructiveFilledActionButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFD92D20))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun MnemonicBackupScreen(
    mnemonic: String,
    onBack: () -> Unit,
    onConfirm: () -> Unit
) {
    val words = remember(mnemonic) { mnemonic.trim().split(Regex("\\s+")).filter { it.isNotBlank() } }
    WalletNativeScaffold(title = "복구 문구 백업", onBack = onBack) {
        Text(
            text = "12개의 복구 문구를 안전하게 기록하세요",
            color = Color(0xFF0B2A55),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = MaterialTheme.typography.headlineLarge.lineHeight,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "이 문구는 지갑을 복구할 수 있는 유일한 수단입니다. 온라인이나 클라우드에 저장하지 마세요.",
            color = Color(0xFF7B8493),
            style = MaterialTheme.typography.titleMedium,
            lineHeight = MaterialTheme.typography.titleMedium.lineHeight,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(34.dp))
        MnemonicWordGrid(words = words)
        Spacer(modifier = Modifier.height(36.dp))
        WarningCard(
            title = "절대 공유 금지",
            body = "복구 문구를 묻는 사람은 100% 사기꾼입니다."
        )
        Spacer(modifier = Modifier.weight(1f))
        PrimaryBottomButton(text = "다 기록했습니다", onClick = onConfirm)
    }
}

@Composable
private fun WalletRestoreScreen(
    onBack: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var wordCount by remember { mutableStateOf(12) }
    var phrase by remember { mutableStateOf("") }
    val normalizedWords = phrase.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    val canSubmit = normalizedWords.size == wordCount

    WalletNativeScaffold(title = "지갑 복구", onBack = onBack) {
        Text(
            text = "지갑 복구",
            color = Color(0xFF0B2A55),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(22.dp))
        Text(
            text = "지갑을 복구하려면 복구 문구를 입력하세요.",
            color = Color(0xFF7B8493),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(32.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFFF7F7F5))
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(12, 24).forEach { count ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (wordCount == count) Color(0xFF0B2A55) else Color.Transparent)
                        .clickable { wordCount = count },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$count 단어",
                        color = if (wordCount == count) Color.White else Color(0xFF7B8493),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(34.dp))
        OutlinedTextField(
            value = phrase,
            onValueChange = { phrase = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp),
            placeholder = { Text("복구 문구를 공백으로 구분해 입력하세요") },
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            minLines = 5
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "${normalizedWords.size} / $wordCount 단어",
            color = if (canSubmit) Color(0xFF2F7DFF) else Color(0xFF7B8493),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End
        )
        Spacer(modifier = Modifier.height(24.dp))
        WarningCard(
            title = "주의사항",
            body = "KYvC는 한 개의 지갑만 허용하므로 지갑 복구 시 발급되어있는 증명서와 지갑은 삭제됩니다."
        )
        Spacer(modifier = Modifier.weight(1f))
        PrimaryBottomButton(
            text = "확인",
            enabled = canSubmit,
            onClick = { onConfirm(normalizedWords.joinToString(" ")) }
        )
    }
}

@Composable
private fun WalletNativeScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF7F7F5))
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("<", color = Color(0xFF0B2A55), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(title, color = Color(0xFF0B2A55), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(modifier = Modifier.size(38.dp))
            }
            Spacer(modifier = Modifier.height(34.dp))
            content()
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun MnemonicWordGrid(words: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        words.chunked(3).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { columnIndex, word ->
                    val index = rowIndex * 3 + columnIndex + 1
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(58.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(14.dp))
                            .background(Color.White)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("$index", color = Color(0xFFA3A3A3), fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(word, color = Color(0xFF0B2A55), fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun WarningCard(title: String, body: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, Color(0xFFE5EDFF), RoundedCornerShape(18.dp))
            .background(Color.White)
            .padding(22.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("?", color = Color(0xFF2F7DFF), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        Spacer(modifier = Modifier.size(18.dp))
        Column {
            Text(title, color = Color(0xFF0B2A55), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(body, color = Color(0xFF7B8493), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun PrimaryBottomButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun PinSetupScreen(
    pin: String,
    statusMessage: String?,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onCancel: () -> Unit
) {
    val keyRows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "⌫")
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF5F5F3))
                    .clickable { onCancel() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "<",
                    color = Color(0xFF0B1D40),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "PIN 번호 등록",
                    color = Color(0xFF0B1D40),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Spacer(modifier = Modifier.size(34.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "PIN 번호를 입력하세요",
                style = MaterialTheme.typography.headlineSmall,
                color = Color(0xFF0B1D40),
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(86.dp))
            Text(
                text = statusMessage ?: "PIN 번호를 등록합니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (statusMessage == null) Color(0xFF6B7280) else MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(78.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(SETUP_PIN_LENGTH) { index ->
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(if (index < pin.length) Color(0xFF0B1D40) else Color(0xFFD1D5DB))
                    )
                }
            }
            Spacer(modifier = Modifier.height(66.dp))
            keyRows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 34.dp),
                    horizontalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    row.forEach { key ->
                        if (key.isBlank()) {
                            Spacer(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(58.dp)
                            )
                            return@forEach
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(58.dp)
                                .clip(RoundedCornerShape(15.dp))
                                .border(1.dp, Color(0xFFECECEA), RoundedCornerShape(15.dp))
                                .background(Color.White)
                                .clickable {
                                    if (key == "⌫") onBackspace() else onDigit(key)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = key,
                                    color = Color(0xFF0B1D40),
                                    fontWeight = FontWeight.ExtraBold,
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun PinKeypadSection(
    pin: String,
    errorMessage: String?,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit
) {
    Spacer(modifier = Modifier.height(8.dp))

    val keyRows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "⌫")
    )

    Text(
        text = "PIN 번호를 입력하세요",
        style = MaterialTheme.typography.titleLarge,
        color = Color(0xFF0B1D40),
        fontWeight = FontWeight.ExtraBold
    )
    Text(
        text = "지갑 잠금을 해제합니다.",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF6B7280)
    )

    Spacer(modifier = Modifier.height(14.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(4) { index ->
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (index < pin.length) Color(0xFF0B1D40) else Color(0xFFD1D5DB))
            )
        }
    }

    keyRows.forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            row.forEach { key ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .clickable(enabled = key.isNotBlank()) {
                            when (key) {
                                "⌫" -> onBackspace()
                                else -> onDigit(key)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (key.isNotBlank()) {
                        Text(
                            text = key,
                            color = Color(0xFF0B1D40),
                            fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }

    errorMessage?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
    }

    Button(
        onClick = onSubmit,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("PIN 로그인")
    }
}

@Composable
private fun AuthModeSelector(
    currentMode: AuthMode,
    pinEnabled: Boolean,
    patternEnabled: Boolean,
    onModeSelected: (AuthMode) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { onModeSelected(AuthMode.Pin) },
            enabled = pinEnabled,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("PIN")
        }
        Button(
            onClick = { onModeSelected(AuthMode.Pattern) },
            enabled = patternEnabled,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("패턴")
        }
    }
    Text(
        text = if (currentMode == AuthMode.Pin) "현재 방식: PIN" else "현재 방식: 패턴",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun PinSection(
    setupMode: Boolean,
    pin: String,
    pinConfirm: String,
    onPinChange: (String) -> Unit,
    onPinConfirmChange: (String) -> Unit
) {
    OutlinedTextField(
        value = pin,
        onValueChange = onPinChange,
        label = { Text(if (setupMode) "새 PIN" else "PIN") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth()
    )

    if (setupMode) {
        OutlinedTextField(
            value = pinConfirm,
            onValueChange = onPinConfirmChange,
            label = { Text("PIN 확인") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PatternSection(
    setupMode: Boolean,
    pattern: List<Int>,
    patternConfirm: List<Int>,
    patternConfirmMode: Boolean,
    onPointTapped: (Int) -> Unit,
    onClear: () -> Unit,
    onMoveToConfirm: () -> Unit
) {
    Text(
        text = when {
            !setupMode -> "점을 순서대로 눌러 패턴을 입력합니다."
            !patternConfirmMode -> "점을 순서대로 눌러 새 패턴을 만듭니다."
            else -> "같은 순서로 패턴을 다시 눌러 확인합니다."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    PatternGrid(
        selectedPoints = if (patternConfirmMode) patternConfirm else pattern,
        onPointTapped = onPointTapped
    )
    Text(
        text = "입력: " + (if (patternConfirmMode) patternConfirm else pattern).joinToString(" -> "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onClear) {
            Text("초기화")
        }
        if (setupMode && !patternConfirmMode) {
            TextButton(onClick = onMoveToConfirm) {
                Text("확인 단계")
            }
        }
    }
}

@Composable
private fun PatternGrid(
    selectedPoints: List<Int>,
    onPointTapped: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 280.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(3) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                repeat(3) { column ->
                    val point = row * 3 + column
                    val selectedIndex = selectedPoints.indexOf(point)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onPointTapped(point) },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selectedIndex >= 0) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color.Transparent
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (selectedIndex >= 0) (selectedIndex + 1).toString() else "",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    url: String,
    fallbackUrl: String,
    bridge: WalletBridge,
    onWebViewCreated: (WebView) -> Unit = {},
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                var fallbackLoaded = false
                fun loadFallbackIfNeeded(view: WebView?) {
                    if (!fallbackLoaded) {
                        fallbackLoaded = true
                        view?.loadUrl(fallbackUrl)
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val requestedUrl = request?.url ?: return false
                        if (requestedUrl.scheme == "http" && requestedUrl.host == WEB_HOST) {
                            view?.loadUrl(
                                requestedUrl.buildUpon()
                                    .scheme("https")
                                    .build()
                                    .toString()
                            )
                            return true
                        }
                        return false
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        Log.d(WEBVIEW_TAG, "page started: $url")
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d(WEBVIEW_TAG, "page finished: $url")
                        view?.evaluateJavascript(
                            """
                            (function() {
                              return JSON.stringify({
                                href: location.href,
                                title: document.title,
                                bodyTextLength: (document.body && document.body.innerText || '').length,
                                bodyHtmlLength: (document.body && document.body.innerHTML || '').length
                              });
                            })();
                            """.trimIndent()
                        ) { result ->
                            Log.d(WEBVIEW_TAG, "page snapshot: $result")
                        }
                        view?.evaluateJavascript(WEBVIEW_DIAGNOSTICS_SCRIPT) { result ->
                            Log.d(WEBVIEW_TAG, "layout diagnostics: $result")
                        }
                        // Auto-login session event is disabled for now.
                        // bridge.emitAutoLoginSessionIfAvailable()
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true) {
                            Log.w(
                                WEBVIEW_TAG,
                                "main frame error url=${request.url} code=${error?.errorCode} desc=${error?.description}"
                            )
                        }
                        if (request?.isForMainFrame == true && !fallbackLoaded && shouldFallback(error)) {
                            loadFallbackIfNeeded(view)
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?
                    ) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        if (request?.isForMainFrame == true) {
                            val message = "WebView HTTP ${errorResponse?.statusCode}: ${request.url}"
                            Log.w(WEBVIEW_TAG, message)
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        } else if (errorResponse?.statusCode == 404) {
                            Log.w(WEBVIEW_TAG, "resource 404: ${request?.url}")
                        }
                        // 404/4xx/5xx는 서버 연결 자체는 성공이므로 fallback으로 넘기지 않는다.
                    }

                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: SslErrorHandler?,
                        error: SslError?
                    ) {
                        handler?.cancel()
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        Log.d(
                            WEBVIEW_TAG,
                            "console ${consoleMessage?.messageLevel()} ${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()} ${consoleMessage?.message()}"
                        )
                        return true
                    }
                }
                WebView.setWebContentsDebuggingEnabled(true)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                addJavascriptInterface(bridge, "Android")
                bridge.attachWebView(this)
                onWebViewCreated(this)
                loadUrl(url)
            }
        },
        update = {}
    )
}

private fun shouldFallback(error: WebResourceError?): Boolean {
    if (error == null) return false
    return when (error.errorCode) {
        WebViewClient.ERROR_HOST_LOOKUP,
        WebViewClient.ERROR_CONNECT,
        WebViewClient.ERROR_TIMEOUT,
        WebViewClient.ERROR_PROXY_AUTHENTICATION,
        WebViewClient.ERROR_UNSUPPORTED_SCHEME -> true
        else -> false
    }
}
