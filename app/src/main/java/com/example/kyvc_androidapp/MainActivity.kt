package com.example.kyvc_androidapp

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.kyvc_androidapp.ui.theme.Kyvc_AndroidAppTheme

import androidx.compose.runtime.rememberCoroutineScope
import androidx.room.Room
import com.example.kyvc_androidapp.bridge.WalletBridge
import com.example.kyvc_androidapp.data.local.AppDatabase
import com.example.kyvc_androidapp.wallet.core.WalletManager
import com.example.kyvc_androidapp.wallet.core.XrplClientHelper
import kotlinx.coroutines.CoroutineScope

class MainActivity : ComponentActivity() {
    private lateinit var db: AppDatabase
    private val walletManager = WalletManager()
    private val xrplHelper = XrplClientHelper()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "kyvc-wallet-db"
        ).build()

        enableEdgeToEdge()
        setContent {
            val scope = rememberCoroutineScope()
            Kyvc_AndroidAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WebViewScreen(
                        url = "file:///android_asset/index.html",
                        bridge = WalletBridge(this, scope, walletManager, xrplHelper),
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
                loadUrl(url)
            }
        },
        update = { webView ->
            // Update logic if needed
        }
    )
}
