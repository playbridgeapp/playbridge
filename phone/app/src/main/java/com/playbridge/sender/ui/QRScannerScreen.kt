package com.playbridge.sender.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.playbridge.sender.model.QRCodeData
import com.playbridge.sender.model.parseQRCode
import java.util.concurrent.Executors

private const val TAG = "QRScannerScreen"

@Composable
fun QRScannerScreen(
    onQRCodeScanned: (QRCodeData) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == 
                PackageManager.PERMISSION_GRANTED
        )
    }
    
    var scannedData by remember { mutableStateOf<QRCodeData?>(null) }
    var isScanning by remember { mutableStateOf(true) }
    var showManualDialog by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoomLevel by remember { mutableFloatStateOf(0f) }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            // Camera Preview
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        
                        val imageAnalyzer = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { analysis ->
                                analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                                    if (!isScanning) {
                                        imageProxy.close()
                                        return@setAnalyzer
                                    }
                                    
                                    @androidx.camera.core.ExperimentalGetImage
                                    val mediaImage = imageProxy.image
                                    if (mediaImage != null) {
                                        val image = InputImage.fromMediaImage(
                                            mediaImage,
                                            imageProxy.imageInfo.rotationDegrees
                                        )
                                        
                                        val scanner = BarcodeScanning.getClient()
                                        scanner.process(image)
                                            .addOnSuccessListener { barcodes ->
                                                for (barcode in barcodes) {
                                                    if (barcode.valueType == Barcode.TYPE_TEXT) {
                                                        barcode.rawValue?.let { rawValue ->
                                                            val qrData = parseQRCode(rawValue)
                                                            if (qrData != null && isScanning) {
                                                                isScanning = false
                                                                scannedData = qrData
                                                                Log.i(TAG, "Scanned: ${qrData.name} at ${qrData.ip}:${qrData.port}")
                                                                onQRCodeScanned(qrData)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            .addOnCompleteListener {
                                                imageProxy.close()
                                            }
                                    } else {
                                        imageProxy.close()
                                    }
                                }
                            }
                        
                        try {
                            cameraProvider.unbindAll()
                            camera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalyzer
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Camera binding failed", e)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
            
            // Overlay
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Scan TV QR Code",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White
                )
                
                Spacer(modifier = Modifier.weight(1f))
                
                // Zoom slider
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "-",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                    
                    Slider(
                        value = zoomLevel,
                        onValueChange = { newZoom ->
                            zoomLevel = newZoom
                            camera?.cameraControl?.setLinearZoom(newZoom)
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.Gray
                        )
                    )
                    
                    Text(
                        text = "+",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                }
                
                Text(
                    text = "Zoom: ${(zoomLevel * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Scanning frame indicator
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .background(Color.Transparent)
                )
                
                Spacer(modifier = Modifier.weight(1f))
                
                Text(
                    text = if (scannedData != null) {
                        "Connecting to ${scannedData?.name}..."
                    } else {
                        "Point camera at the QR code\non your TV screen"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Manual connect button
                OutlinedButton(
                    onClick = { showManualDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    )
                ) {
                    Text("Enter Address Manually")
                }
            }
        } else {
            // No camera permission
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Camera Permission Required",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Please grant camera permission to scan the TV's QR code",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Manual connect option when no camera permission
                Button(
                    onClick = { showManualDialog = true }
                ) {
                    Text("Enter Address Manually")
                }
            }
        }
    }
    
    // Manual connection dialog
    if (showManualDialog) {
        ManualConnectionDialog(
            onDismiss = { showManualDialog = false },
            onConnect = { ip, port ->
                showManualDialog = false
                isScanning = false
                val manualData = QRCodeData(
                    ip = ip,
                    port = port,
                    token = "", // Manual connection doesn't need token for now
                    name = "TV ($ip)"
                )
                onQRCodeScanned(manualData)
            }
        )
    }
}

@Composable
fun ManualConnectionDialog(
    onDismiss: () -> Unit,
    onConnect: (ip: String, port: Int) -> Unit
) {
    var ipAddress by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8765") }
    var isError by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual Connection") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Enter the TV's IP address and port",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = { 
                        ipAddress = it
                        isError = false
                    },
                    label = { Text("IP Address") },
                    placeholder = { Text("192.168.1.100") },
                    singleLine = true,
                    isError = isError && ipAddress.isBlank(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                
                OutlinedTextField(
                    value = port,
                    onValueChange = { 
                        port = it.filter { c -> c.isDigit() }
                        isError = false
                    },
                    label = { Text("Port") },
                    placeholder = { Text("8765") },
                    singleLine = true,
                    isError = isError && port.isBlank(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (ipAddress.isBlank() || port.isBlank()) {
                        isError = true
                        return@Button
                    }
                    val portNum = port.toIntOrNull() ?: 8765
                    onConnect(ipAddress.trim(), portNum)
                }
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

