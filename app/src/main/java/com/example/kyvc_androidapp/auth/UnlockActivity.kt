package com.example.kyvc_androidapp.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.kyvc_androidapp.security.AppLockStore
import com.example.kyvc_androidapp.ui.theme.Kyvc_AndroidAppTheme
import java.util.concurrent.Executor

private const val MIN_PATTERN_POINTS = 4

class UnlockActivity : FragmentActivity() {
    private lateinit var appLockStore: AppLockStore
    private lateinit var biometricExecutor: Executor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appLockStore = AppLockStore(applicationContext)
        biometricExecutor = ContextCompat.getMainExecutor(this)

        val requestJson = intent.getStringExtra(EXTRA_REQUEST_JSON).orEmpty()
        val method = intent.getStringExtra(EXTRA_METHOD).orEmpty().lowercase()

        setContent {
            Kyvc_AndroidAppTheme {
                UnlockScreen(
                    method = method,
                    appLockStore = appLockStore,
                    onBiometricAuth = ::showBiometricPrompt,
                    onSuccess = { finishWithResult(true, method, requestJson, null) },
                    onFailure = { error -> finishWithResult(false, method, requestJson, error) },
                    onCancel = { finishWithResult(false, method, requestJson, "사용자가 인증을 취소했습니다.") }
                )
            }
        }
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
                    appLockStore.resetAuthFailures()
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    val failedAttempts = appLockStore.recordAuthFailure()
                    if (appLockStore.isEmailVerificationRequired()) {
                        onError("인증 5회 실패로 이메일 인증이 필요합니다.")
                    } else {
                        val remaining = appLockStore.getRemainingAuthAttempts()
                        onError("지문을 다시 확인해주세요. 남은 시도 ${remaining}회")
                    }
                }
            }
        )
        prompt.authenticate(promptInfo)
    }

    private fun finishWithResult(success: Boolean, method: String, requestJson: String, error: String?) {
        setResult(
            if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Intent().apply {
                putExtra(EXTRA_METHOD, method)
                putExtra(EXTRA_REQUEST_JSON, requestJson)
                putExtra(EXTRA_ERROR, error)
            }
        )
        finish()
    }

    companion object {
        const val EXTRA_METHOD = "auth_method"
        const val EXTRA_REQUEST_JSON = "request_json"
        const val EXTRA_ERROR = "auth_error"
    }
}

@Composable
private fun UnlockScreen(
    method: String,
    appLockStore: AppLockStore,
    onBiometricAuth: (
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) -> Unit,
    onSuccess: () -> Unit,
    onFailure: (String) -> Unit,
    onCancel: () -> Unit
) {
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var pin by rememberSaveable { mutableStateOf("") }
    val pattern = remember { mutableStateListOf<Int>() }

    LaunchedEffect(method) {
        if (method == "biometric") {
            if (appLockStore.isEmailVerificationRequired()) {
                onFailure("인증 5회 실패로 이메일 인증이 필요합니다.")
                return@LaunchedEffect
            }
            if (!appLockStore.isBiometricEnabled()) {
                onFailure("지문 로그인이 활성화되어 있지 않습니다.")
            } else {
                onBiometricAuth(
                    "지문 로그인",
                    "지문으로 인증을 완료합니다.",
                    onSuccess,
                    { message -> onFailure(message) }
                )
            }
        }
    }

    if (method == "biometric") {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Text("지문 인증을 요청하는 중입니다.")
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
                        text = if (method == "pattern") "패턴 인증" else "PIN 인증",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (method == "pattern") {
                            "등록된 패턴으로 인증합니다."
                        } else {
                            "등록된 PIN으로 인증합니다."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (method == "pattern") {
                        PatternGrid(
                            selectedPoints = pattern,
                            onPointTapped = { point ->
                                if (!pattern.contains(point)) {
                                    pattern.add(point)
                                    errorMessage = null
                                }
                            }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                pattern.clear()
                                errorMessage = null
                            }) {
                                Text("초기화")
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = pin,
                            onValueChange = {
                                pin = it.filter(Char::isDigit).take(8)
                                errorMessage = null
                            },
                            label = { Text("PIN") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.fillMaxWidth()
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
                            val verified = if (method == "pattern") {
                                when {
                                    pattern.size < MIN_PATTERN_POINTS -> {
                                        errorMessage = "패턴은 최소 4개의 점을 연결해야 합니다."
                                        false
                                    }
                                    else -> appLockStore.verifyPattern(pattern.toList())
                                }
                            } else {
                                when {
                                    pin.length !in 4..8 -> {
                                        errorMessage = "PIN은 4~8자리 숫자여야 합니다."
                                        false
                                    }
                                    else -> appLockStore.verifyPin(pin)
                                }
                            }
                            if (verified) {
                                appLockStore.resetAuthFailures()
                                onSuccess()
                            } else if (errorMessage == null) {
                                val failedAttempts = appLockStore.recordAuthFailure()
                                if (appLockStore.isEmailVerificationRequired()) {
                                    onFailure("인증 5회 실패로 이메일 인증이 필요합니다.")
                                } else {
                                    val remaining = appLockStore.getRemainingAuthAttempts()
                                    errorMessage = if (method == "pattern") {
                                        "패턴이 올바르지 않습니다. 남은 시도 ${remaining}회"
                                    } else {
                                        "PIN이 올바르지 않습니다. 남은 시도 ${remaining}회"
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (method == "pattern") "패턴 인증" else "PIN 인증")
                    }

                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("취소")
                    }
                }
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
                                    if (selectedIndex >= 0) MaterialTheme.colorScheme.primary else Color.Transparent
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
