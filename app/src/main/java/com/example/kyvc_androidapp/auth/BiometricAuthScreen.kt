package com.example.kyvc_androidapp.auth

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun BiometricAuthScreen(
    modifier: Modifier = Modifier,
    title: String = "생체인증",
    headline: String = "지문을 인식해주세요",
    description: String = "등록한 생체정보로 로그인합니다.",
    statusText: String = "Touch ID 대기 중",
    onStartAuth: (onStatus: (String, Boolean) -> Unit) -> Unit,
    onCancel: () -> Unit,
    onPinLogin: (() -> Unit)? = null
) {
    var currentStatus by remember { mutableStateOf(statusText) }
    var isError by remember { mutableStateOf(false) }

    val startAuth = {
        currentStatus = statusText
        isError = false
        onStartAuth { message, error ->
            currentStatus = message
            isError = error
        }
    }

    LaunchedEffect(Unit) {
        startAuth()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 14.dp)
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
                    text = title,
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
                .align(Alignment.TopCenter)
                .padding(top = 104.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = headline,
                color = Color(0xFF0B1D40),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = description,
                color = Color(0xFF6B7280),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        }

        Box(
            modifier = Modifier.align(Alignment.Center),
            contentAlignment = Alignment.Center
        ) {
            FingerprintMark(
                statusText = currentStatus,
                isError = isError,
                onRetry = startAuth
            )
        }

        if (onPinLogin != null) {
            TextButton(
                onClick = onPinLogin,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 74.dp)
            ) {
                Text(
                    text = "PIN으로 로그인",
                    color = Color(0xFF6B7280),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

@Composable
private fun FingerprintMark(
    statusText: String,
    isError: Boolean,
    onRetry: () -> Unit
) {
    val context = LocalContext.current
    val fingerprintBitmap = remember {
        context.assets.open("img.png").use { input ->
            BitmapFactory.decodeStream(input).asImageBitmap()
        }
    }

    Box(
        modifier = Modifier
            .size(width = 230.dp, height = 228.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(190.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF5F7CFF), Color(0xFF8B5CF6))
                    )
                )
                .clickable { onRetry() },
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = fingerprintBitmap,
                contentDescription = "지문 인식 재시도",
                modifier = Modifier.size(116.dp),
                contentScale = ContentScale.Fit
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .shadow(
                    elevation = 16.dp,
                    shape = CircleShape,
                    clip = false
                )
                .clip(CircleShape)
                .background(if (isError) Color(0xFFFFFBFB) else Color(0xFFF8FFFB))
                .padding(horizontal = 20.dp, vertical = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = statusText,
                color = if (isError) Color(0xFFDC2626) else Color(0xFF16A34A),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
        }
    }
}
