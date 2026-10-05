package com.inonvation.campbox.ui.screen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** 独立扫码页，离开页面即释放相机；不依赖微信或 Google Play 服务。 */
class WaterScanActivity : CaptureActivity()

@Composable
internal fun WaterScanButton(
    enabled: Boolean,
    loading: Boolean,
    onCode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.takeIf { it.isNotBlank() }?.let(onCode)
    }
    fun launchScanner() {
        try {
            scanner.launch(ScanOptions()
                .setCaptureActivity(WaterScanActivity::class.java)
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setOrientationLocked(false)
                .setBeepEnabled(false)
                .setBarcodeImageEnabled(false)
                .setPrompt("将饮水机上的胖乖设备二维码放入框内"))
        } catch (_: Exception) {
            error = "无法打开相机，请检查相机权限后重试"
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchScanner() else {
            permissionDenied = true
            error = "扫码喝水需要相机权限。可在系统设置中为 CampBox 开启相机权限，也可以继续选择历史设备开水。"
        }
    }
    OutlinedButton(
        enabled = enabled && !loading,
        modifier = modifier,
        onClick = {
            permissionDenied = false
            when {
                !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) ->
                    error = "这台设备没有可用摄像头，请选择历史设备开水"
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ->
                    launchScanner()
                else -> cameraPermission.launch(Manifest.permission.CAMERA)
            }
        },
    ) { Text(if (loading) "识别设备中…" else "扫码喝水") }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("扫码喝水") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } },
            dismissButton = {
                if (permissionDenied) TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")))
                    error = null
                }) { Text("去设置") }
            },
        )
    }
}
