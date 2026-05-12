package com.example.kyvc_androidapp

import android.app.Activity
import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.net.http.SslError
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private enum class AuthMode {
    Pin,
    Pattern
}

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
                        }
                    }
                }
            }
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
        Text(
            text = "QR Scan",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 24.dp, top = 12.dp)
                .size(38.dp)
                .clip(CircleShape)
                .background(Color(0xFF374151))
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
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(188.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(30.dp))
                    .border(2.dp, Color(0xFF2F7DFF), RoundedCornerShape(30.dp))
                    .background(Color(0xFFF7F7F7))
                    .padding(18.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(modifier = Modifier.height(68.dp))
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = subtitle,
                color = Color(0xFFC6CBD6),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            if (!footer.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(86.dp))
                Text(
                    text = footer,
                    color = Color(0xFFD5DAE3),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
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
            footer = "혹은 지갑에서 바로 발급받기"
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
            .padding(horizontal = 24.dp, vertical = 18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF5F5F3))
                    .clickable { onCancel() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "<",
                    color = Color(0xFF0B1D40),
                    style = MaterialTheme.typography.titleLarge,
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
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Spacer(modifier = Modifier.size(46.dp))
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
