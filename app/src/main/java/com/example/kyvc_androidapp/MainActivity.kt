package com.example.kyvc_androidapp

import android.app.Activity
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.example.kyvc_androidapp.auth.UnlockActivity
import com.example.kyvc_androidapp.bridge.WalletBridge
import com.example.kyvc_androidapp.scanner.QrScannerActivity
import com.example.kyvc_androidapp.ui.main.MainViewModel
import com.example.kyvc_androidapp.ui.theme.Kyvc_AndroidAppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

private enum class AuthMode {
    Pin,
    Pattern
}

class MainActivity : FragmentActivity() {
    private val mainViewModel: MainViewModel by viewModels()
    private lateinit var bridge: WalletBridge
    private lateinit var biometricExecutor: Executor
    private var isUnlocked = mutableStateOf(false)
    private val qrScanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val requestJson = result.data?.getStringExtra(QrScannerActivity.EXTRA_REQUEST_JSON)
            val qrData = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_DATA)
            val error = result.data?.getStringExtra(QrScannerActivity.EXTRA_ERROR)
            if (result.resultCode == Activity.RESULT_OK && !qrData.isNullOrBlank()) {
                bridge.onQrScanResult(requestJson = requestJson, qrData = qrData, errorMessage = null)
            } else {
                bridge.onQrScanResult(
                    requestJson = requestJson,
                    qrData = null,
                    errorMessage = error ?: "QR scan cancelled"
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
                val intent = Intent(this, QrScannerActivity::class.java).apply {
                    putExtra(QrScannerActivity.EXTRA_REQUEST_JSON, requestJson)
                }
                qrScanLauncher.launch(intent)
            },
            launchNativeAuth = { requestJson, method ->
                val intent = Intent(this, UnlockActivity::class.java).apply {
                    putExtra(UnlockActivity.EXTRA_REQUEST_JSON, requestJson)
                    putExtra(UnlockActivity.EXTRA_METHOD, method)
                }
                unlockLauncher.launch(intent)
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

        if (unlocked) {
            content(true)
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

                            if (authMode == AuthMode.Pin) {
                                PinSection(
                                    setupMode = setupMode,
                                    pin = pin,
                                    pinConfirm = pinConfirm,
                                    onPinChange = {
                                        pin = it.filter(Char::isDigit).take(8)
                                        errorMessage = null
                                    },
                                    onPinConfirmChange = {
                                        pinConfirm = it.filter(Char::isDigit).take(8)
                                        errorMessage = null
                                    }
                                )
                            } else {
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

                            Button(
                                onClick = {
                                    when (authMode) {
                                        AuthMode.Pin -> {
                                            when {
                                                pin.length !in 4..8 -> errorMessage = "PIN은 4~8자리 숫자여야 합니다."
                                                pin != pinConfirm -> errorMessage = "PIN 확인 값이 일치하지 않습니다."
                                                else -> {
                                                    appLockStore.setPin(pin)
                                                    unlocked = true
                                                    setupMode = false
                                                    pin = ""
                                                    pinConfirm = ""
                                                    errorMessage = null
                                                }
                                            }
                                        }
                                        AuthMode.Pattern -> {
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
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (authMode == AuthMode.Pin) "PIN 저장" else "패턴 저장")
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
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true && !fallbackLoaded) {
                            fallbackLoaded = true
                            view?.loadUrl(fallbackUrl)
                        }
                    }
                }
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
