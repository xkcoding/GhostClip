package com.xkcoding.ghostclip.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.xkcoding.ghostclip.R
import com.xkcoding.ghostclip.net.PairingInfo
import com.xkcoding.ghostclip.util.DebugLog
import java.util.concurrent.Executors

/**
 * 扫码配对页面
 *
 * CameraX 预览 + ML Kit Barcode Scanning 实时识别 QR 码
 * 识别到 ghostclip://pair URI 后解析配对信息并返回结果
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var scanHint: TextView

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    private var scanHandled = false

    // 相机权限请求
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        previewView = findViewById(R.id.preview_view)
        scanHint = findViewById(R.id.scan_hint)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            finish()
        }

        // 检查相机权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, QrAnalyzer())
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                )
            } catch (e: Exception) {
                DebugLog.e(TAG, "相机启动失败: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * ML Kit QR 码分析器
     */
    private inner class QrAnalyzer : ImageAnalysis.Analyzer {
        private val scanner = BarcodeScanning.getClient()

        @OptIn(ExperimentalGetImage::class)
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage == null || scanHandled) {
                imageProxy.close()
                return
            }

            val inputImage = InputImage.fromMediaImage(
                mediaImage, imageProxy.imageInfo.rotationDegrees
            )

            scanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        if (barcode.format == Barcode.FORMAT_QR_CODE) {
                            handleQrCode(barcode.rawValue ?: continue)
                            break
                        }
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    private fun handleQrCode(raw: String) {
        if (scanHandled) return

        val info = PairingInfo.parse(raw)
        if (info == null) {
            // 非 GhostClip QR 码 -- 在 UI 线程提示
            runOnUiThread {
                scanHint.text = getString(R.string.scan_invalid_qr)
                scanHint.setTextColor(ContextCompat.getColor(this, R.color.warning))
            }
            DebugLog.d(TAG, "非 GhostClip QR 码: ${raw.take(50)}")
            return
        }

        // 成功识别
        scanHandled = true
        DebugLog.d(TAG, "扫码成功: mac_hash=${info.macHash}, device=${info.deviceName}")

        runOnUiThread {
            Toast.makeText(this, R.string.scan_success, Toast.LENGTH_SHORT).show()
        }

        // 返回配对信息给调用方
        val resultIntent = Intent().apply {
            putExtra(EXTRA_MAC_HASH, info.macHash)
            putExtra(EXTRA_TOKEN, info.token)
            putExtra(EXTRA_DEVICE_NAME, info.deviceName)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "ScanActivity"
        const val EXTRA_MAC_HASH = "mac_hash"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_DEVICE_NAME = "device_name"
    }
}
