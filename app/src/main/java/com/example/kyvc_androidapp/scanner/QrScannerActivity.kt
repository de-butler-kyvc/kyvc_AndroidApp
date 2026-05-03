package com.example.kyvc_androidapp.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QrScannerActivity : ComponentActivity() {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var barcodeScanner: BarcodeScanner? = null
    private var delivered = AtomicBoolean(false)
    private lateinit var previewView: PreviewView

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startCamera()
        } else {
            finishWithError("Camera permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val instruction = TextView(this).apply {
            text = "QR 코드를 프레임 중앙에 맞추세요"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0x66000000)
            setPadding(32, 24, 32, 24)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 32
                topMargin = 48
            }
        }

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(previewView)
            addView(instruction)
        }
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val scannerOptions = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = BarcodeScanning.getClient(scannerOptions)
        barcodeScanner = scanner

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage == null || delivered.get()) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    scanner.process(inputImage)
                        .addOnSuccessListener { barcodes ->
                            val rawValue = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                            if (!rawValue.isNullOrBlank() && delivered.compareAndSet(false, true)) {
                                runOnUiThread {
                                    finishWithSuccess(rawValue)
                                }
                            }
                        }
                        .addOnFailureListener { error ->
                            Log.e(TAG, "QR scan frame failed", error)
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "startCamera failed", e)
                finishWithError(e.message ?: "Failed to start camera")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun finishWithSuccess(qrData: String) {
        val result = Intent().apply {
            putExtra(EXTRA_REQUEST_JSON, intent.getStringExtra(EXTRA_REQUEST_JSON))
            putExtra(EXTRA_QR_DATA, qrData)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun finishWithError(message: String) {
        val result = Intent().apply {
            putExtra(EXTRA_REQUEST_JSON, intent.getStringExtra(EXTRA_REQUEST_JSON))
            putExtra(EXTRA_ERROR, message)
        }
        setResult(RESULT_CANCELED, result)
        finish()
    }

    override fun onDestroy() {
        try {
            barcodeScanner?.close()
        } catch (_: Exception) {
        }
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REQUEST_JSON = "extra_request_json"
        const val EXTRA_QR_DATA = "extra_qr_data"
        const val EXTRA_ERROR = "extra_error"
        private const val TAG = "QrScannerActivity"
    }
}
