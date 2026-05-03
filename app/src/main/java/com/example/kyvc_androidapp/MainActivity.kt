package com.example.kyvc_androidapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.example.kyvc_androidapp.ui.theme.Kyvc_AndroidAppTheme
import com.example.kyvc_androidapp.bridge.WalletBridge
import com.example.kyvc_androidapp.scanner.QrScannerActivity
import com.example.kyvc_androidapp.ui.main.MainViewModel

class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()
    private lateinit var bridge: WalletBridge
    private val qrScanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        val container = mainViewModel.appContainer
        bridge = WalletBridge(
            context = this,
            scope = lifecycleScope,
            walletStateStore = container.walletStateStore,
            xrplHelper = container.xrplHelper,
            credentialRepository = container.credentialRepository,
            launchQrScanner = { requestJson ->
                val intent = Intent(this, QrScannerActivity::class.java).apply {
                    putExtra(QrScannerActivity.EXTRA_REQUEST_JSON, requestJson)
                }
                qrScanLauncher.launch(intent)
            }
        )
        setContent {
            Kyvc_AndroidAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WebViewScreen(
                        url = "file:///android_asset/index.html",
                        bridge = bridge,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun WebViewScreen(url: String, bridge: WalletBridge, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true // For complex web apps
                addJavascriptInterface(bridge, "Android")
                bridge.attachWebView(this)
                loadUrl(url)
            }
        },
        update = { webView ->
            // Update logic if needed
        }
    )
}
