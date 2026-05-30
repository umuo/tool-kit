package com.lacknb.toolkit.features.image

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.lacknb.toolkit.core.Tool
import com.lacknb.toolkit.core.ToolCategory
import com.lacknb.toolkit.ui.screens.ToolWorkspaceContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ImageToTextTool : Tool {
    override val id: String = "image_to_text"
    override val name: String = "图片转文字"
    override val description: String = "离线提取图片中的印刷及手写中英文文字，支持批量导入与导出PDF"
    override val icon: ImageVector = Icons.Default.TextFormat
    override val category: ToolCategory = ToolCategory.IMAGE_SCANNING

    @Composable
    override fun Content(onBack: () -> Unit) {
        val context = LocalContext.current
        val clipboardManager = LocalClipboardManager.current
        val coroutineScope = rememberCoroutineScope()

        val selectedBitmaps = remember { mutableStateListOf<Bitmap>() }
        var isScanning by remember { mutableStateOf(false) }
        var scanningProgressPage by remember { mutableStateOf(0) }
        var recognizedText by remember { mutableStateOf("") }
        var activeTab by remember { mutableStateOf(0) } // 0: 扫描源图, 1: 识别结果
        var showSuccessDialog by remember { mutableStateOf(false) }

        var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
        var selectedPreviewIndex by remember { mutableStateOf(0) }

        val animatedProgress by animateFloatAsState(
            targetValue = if (isScanning) 1f else 0f,
            animationSpec = tween(durationMillis = 2000, easing = LinearEasing),
            label = "ScanProgress"
        )

        // Multiple Image Picker Launcher
        val batchPhotoPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickMultipleVisualMedia()
        ) { uris ->
            if (uris.isNotEmpty()) {
                try {
                    uris.forEach { uri ->
                        val bitmap = getScaledBitmap(context, uri)
                        selectedBitmaps.add(bitmap)
                    }
                    recognizedText = ""
                    activeTab = 0
                    selectedPreviewIndex = selectedBitmaps.size - uris.size
                } catch (e: Throwable) {
                    Toast.makeText(context, "批量导入失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // High-Res Camera Capture Launcher
        val highResCameraLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.TakePicture()
        ) { success ->
            if (success) {
                tempCameraUri?.let { uri ->
                    try {
                        val bitmap = getScaledBitmap(context, uri)
                        selectedBitmaps.add(bitmap)
                        recognizedText = ""
                        activeTab = 0
                        selectedPreviewIndex = selectedBitmaps.size - 1
                    } catch (e: Throwable) {
                        Toast.makeText(context, "拍照解析失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Sequential coroutine execution for multi-page batch OCR
        val performBatchOcr = {
            if (selectedBitmaps.isNotEmpty()) {
                isScanning = true
                activeTab = 0
                recognizedText = ""
                
                coroutineScope.launch {
                    val combinedTextBuilder = java.lang.StringBuilder()
                    val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

                    withContext(Dispatchers.Default) {
                        for (i in selectedBitmaps.indices) {
                            scanningProgressPage = i + 1
                            val originalBitmap = selectedBitmaps[i]
                            val enhancedBitmap = enhanceBitmapForOCR(originalBitmap)
                            val image = InputImage.fromBitmap(enhancedBitmap, 0)
                            
                            try {
                                val visionText = Tasks.await(recognizer.process(image))
                                if (visionText.text.isNotBlank()) {
                                    combinedTextBuilder.append("--- 第 ${i + 1} 页识别文字 ---\n")
                                    combinedTextBuilder.append(visionText.text)
                                    combinedTextBuilder.append("\n\n")
                                }
                            } catch (e: Throwable) {
                                combinedTextBuilder.append("--- 第 ${i + 1} 页识别失败: ${e.localizedMessage} ---\n\n")
                            }
                        }
                    }

                    recognizedText = combinedTextBuilder.toString().trim()
                    isScanning = false
                    activeTab = 1
                    Toast.makeText(context, "批量文本提取完毕！", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Native PDF Exporter for Text Content
        val saveOcrTextToPdf = {
            if (recognizedText.isNotEmpty()) {
                try {
                    val pdfDocument = android.graphics.pdf.PdfDocument()
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.BLACK
                        textSize = 15f
                        isAntiAlias = true
                    }

                    val lines = recognizedText.split("\n")
                    var pageNumber = 1
                    var pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                    var page = pdfDocument.startPage(pageInfo)
                    var canvas = page.canvas
                    var yOffset = 50f

                    for (line in lines) {
                        // Chunk long lines to prevent canvas overflow wrapping manually
                        val lineSegments = if (line.length > 35) line.chunked(35) else listOf(line)
                        for (segment in lineSegments) {
                            if (yOffset > 790f) {
                                pdfDocument.finishPage(page)
                                pageNumber++
                                pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                                page = pdfDocument.startPage(pageInfo)
                                canvas = page.canvas
                                yOffset = 50f
                            }
                            canvas.drawText(segment, 50f, yOffset, paint)
                            yOffset += 26f
                        }
                    }
                    pdfDocument.finishPage(page)

                    // Store inside system Documents folder using MediaStore Resolver
                    val resolver = context.contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "OcrText_${System.currentTimeMillis()}.pdf")
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOCUMENTS + "/ToolkitScanner")
                    }
                    val pdfUri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
                    if (pdfUri != null) {
                        resolver.openOutputStream(pdfUri)?.use { stream ->
                            pdfDocument.writeTo(stream)
                        }
                        showSuccessDialog = true
                    } else {
                        Toast.makeText(context, "导出PDF文件失败", Toast.LENGTH_SHORT).show()
                    }
                    pdfDocument.close()
                } catch (e: Exception) {
                    Toast.makeText(context, "PDF写入失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        BackHandler(enabled = true) {
            if (activeTab == 1) {
                activeTab = 0
            } else if (selectedBitmaps.isNotEmpty()) {
                selectedBitmaps.clear()
            } else {
                onBack()
            }
        }

        ToolWorkspaceContainer(
            toolName = name,
            onBack = onBack
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(Color(0xFF121212))
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Feature Header Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Brush.linearGradient(listOf(Color(0xFF3F51B5), Color(0xFF00BCD4)))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DocumentScanner,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "真·批量 OCR 识别与 PDF 导出",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "支持多图批量导入，顺次自动识别，一键导出为 PDF 电子书",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // File loaders if empty
                if (selectedBitmaps.isEmpty()) {
                    Text(
                        text = "选择待识别的图片源（支持多选）：",
                        color = Color.LightGray,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // High-Res Camera Card
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    try {
                                        val tempFile = File(context.cacheDir, "highres_photo_${System.currentTimeMillis()}.jpg")
                                        if (tempFile.exists()) tempFile.delete()
                                        tempFile.createNewFile()
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "com.lacknb.toolkit.fileprovider",
                                            tempFile
                                        )
                                        tempCameraUri = uri
                                        highResCameraLauncher.launch(uri)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "拉起相机失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                            border = BorderStroke(1.dp, Color(0xFF2E2E2E)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF2E2E2E)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PhotoCamera,
                                        contentDescription = "拍照",
                                        tint = Color(0xFF00BCD4),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "直接拍照",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Batch picker
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    batchPhotoPickerLauncher.launch(
                                        androidx.activity.result.PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                            border = BorderStroke(1.dp, Color(0xFF2E2E2E)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF2E2E2E)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Collections,
                                        contentDescription = "相册",
                                        tint = Color(0xFF00BCD4),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "图库批量导入",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Try with sample document option
                    OutlinedButton(
                        onClick = {
                            val sampleText1 = """
                                极致工具箱 - 第1页 (批量OCR中文识别测试)
                                离线神经网络引擎成功激活！
                                正在对第一页底图文本进行离线智能提取测试中。
                                
                                机器计算状态：正常
                                硬件加速芯片：开启
                            """.trimIndent()
                            val sampleText2 = """
                                极致工具箱 - 第2页 (批量OCR中文识别测试)
                                
                                支持生成多页标准 A4 排版 PDF 文档。
                                多页文本顺次合成完毕，可在第二页右侧标签栏查阅效果。
                                
                                导出结果位置：Documents/ToolkitScanner/
                            """.trimIndent()
                            selectedBitmaps.add(createSampleBitmap(sampleText1))
                            selectedBitmaps.add(createSampleBitmap(sampleText2))
                            recognizedText = ""
                            activeTab = 0
                            selectedPreviewIndex = 0
                            Toast.makeText(context, "已动态绘制载入 2 张高拟真合同测试图！", Toast.LENGTH_LONG).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        border = BorderStroke(1.dp, Color(0xFF3F51B5)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00BCD4)),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Science, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "载入预设批量中文字符测试图组", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    // Toolbar for loaded list
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "已载入 ${selectedBitmaps.size} 张照片底片",
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "继续添加",
                                color = Color(0xFF00BCD4),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    if (!isScanning) {
                                        batchPhotoPickerLauncher.launch(
                                            androidx.activity.result.PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly
                                            )
                                        )
                                    }
                                }
                            )
                            Text(
                                text = "全部清除",
                                color = Color(0xFFE57373),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    if (!isScanning) selectedBitmaps.clear()
                                }
                            )
                        }
                    }

                    // Navigation tabs
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E1E1E))
                    ) {
                        listOf("源图片预览", "合并识别结果").forEachIndexed { index, title ->
                            val isTabSelected = activeTab == index
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { activeTab = index }
                                    .background(if (isTabSelected) Color(0xFF2E2E2E) else Color.Transparent)
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = title,
                                    color = if (isTabSelected) Color(0xFF00BCD4) else Color.Gray,
                                    fontWeight = if (isTabSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    // Main display Workspace
                    if (activeTab == 0) {
                        Column {
                            // High-Res preview area for current select item
                            val activePreviewBitmap = selectedBitmaps.getOrNull(selectedPreviewIndex)
                            if (activePreviewBitmap != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(280.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.dp, Color(0xFF2E2E2E), RoundedCornerShape(12.dp))
                                        .background(Color(0xFF0B0B0B)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = activePreviewBitmap.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxHeight(0.9f)
                                            .fillMaxWidth(0.9f)
                                            .shadow(elevation = 6.dp)
                                    )

                                    // Scanning laser overlay
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        if (isScanning) {
                                            val currentY = size.height * animatedProgress
                                            drawRect(
                                                brush = Brush.verticalGradient(
                                                    colors = listOf(
                                                        Color.Transparent,
                                                        Color(0x0500BCD4),
                                                        Color(0x4000BCD4)
                                                    ),
                                                    startY = currentY - 80f,
                                                    endY = currentY
                                                ),
                                                topLeft = Offset(0f, currentY - 80f),
                                                size = Size(size.width, 80f)
                                            )
                                            drawLine(
                                                color = Color(0xFF00BCD4),
                                                start = Offset(0f, currentY),
                                                end = Offset(size.width, currentY),
                                                strokeWidth = 3.dp.toPx()
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Horizontal thumbnail list roll
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(72.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(selectedBitmaps) { index, item ->
                                    val isSelected = selectedPreviewIndex == index
                                    Box(
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .border(
                                                width = if (isSelected) 2.dp else 1.dp,
                                                color = if (isSelected) Color(0xFF00BCD4) else Color(0xFF2E2E2E),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable { selectedPreviewIndex = index },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Image(
                                            bitmap = item.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        // Small badge index indicator
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .padding(4.dp)
                                                .size(16.dp)
                                                .background(Color(0xFF00BCD4), RoundedCornerShape(8.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${index + 1}",
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Combined Results Editor
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color(0xFF2E2E2E), RoundedCornerShape(12.dp))
                                .background(Color(0xFF1E1E1E))
                                .padding(16.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "合并识别文本结果",
                                        color = Color.LightGray,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        IconButton(
                                            onClick = {
                                                if (recognizedText.isNotEmpty()) {
                                                    clipboardManager.setText(AnnotatedString(recognizedText))
                                                    Toast.makeText(context, "已合并文本复制至剪贴板", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "复制",
                                                tint = Color(0xFF00BCD4),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                if (recognizedText.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(180.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "点击下方按钮开始合并分析所有页面文字",
                                            color = Color.Gray,
                                            fontSize = 12.sp
                                        )
                                    }
                                } else {
                                    OutlinedTextField(
                                        value = recognizedText,
                                        onValueChange = { recognizedText = it },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 180.dp, max = 350.dp),
                                        textStyle = TextStyle(
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = 20.sp
                                        ),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFF2E2E2E),
                                            unfocusedBorderColor = Color(0xFF2E2E2E),
                                            focusedContainerColor = Color(0xFF121212),
                                            unfocusedContainerColor = Color(0xFF121212)
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Dynamic actions trigger
                    if (activeTab == 0) {
                        Button(
                            onClick = performBatchOcr,
                            enabled = !isScanning,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F51B5)),
                            shape = RoundedCornerShape(26.dp)
                        ) {
                            if (isScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color(0xFF00BCD4),
                                    strokeWidth = 2.5.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(text = "顺次智能识别第 ${scanningProgressPage}/${selectedBitmaps.size} 页...", fontSize = 13.sp)
                            } else {
                                Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = "开始批量 OCR 文字识别", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        // Export options
                        Button(
                            onClick = saveOcrTextToPdf,
                            enabled = !isScanning && recognizedText.isNotEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BCD4)),
                            shape = RoundedCornerShape(26.dp)
                        ) {
                            Icon(imageVector = Icons.Default.SaveAlt, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "导出已识别文本为 PDF 电子书", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // PDF Export Success Alert Modal
        if (showSuccessDialog) {
            AlertDialog(
                onDismissRequest = { showSuccessDialog = false },
                icon = {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(Color(0xFF4CAF50).copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                },
                title = { Text(text = "PDF 电子书生成完成", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 17.sp) },
                text = {
                    Text(
                        text = "识别合并大文本已完美写出至您的 Documents/ToolkitScanner 文件夹中。",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { showSuccessDialog = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF00BCD4))
                    ) {
                        Text(text = "我知道了", fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = Color(0xFF1E1E1E)
            )
        }
    }

    // Canvas mock printed sheet rendering
    private fun createSampleBitmap(text: String): Bitmap {
        val bitmap = Bitmap.createBitmap(720, 500, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 28f
            isAntiAlias = true
            strokeWidth = 2.0f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val lines = text.split("\n")
        var y = 60f
        for (line in lines) {
            canvas.drawText(line, 40f, y, paint)
            y += 45f
        }
        return bitmap
    }

    private fun enhanceBitmapForOCR(src: Bitmap): Bitmap {
        // Create a mutable copy in ARGB_8888 if it's not already
        val dest = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(dest)
        
        val colorMatrix = android.graphics.ColorMatrix()
        // 1. Grayscale
        colorMatrix.setSaturation(0f)
        
        // 2. High Contrast and Brightness adjustment to eliminate faint backgrounds
        val contrast = 2.0f // Double the contrast
        val brightness = -20f // Slightly reduce brightness to make blacks darker
        
        val contrastMatrix = android.graphics.ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, brightness,
            0f, contrast, 0f, 0f, brightness,
            0f, 0f, contrast, 0f, brightness,
            0f, 0f, 0f, 1f, 0f
        ))
        
        colorMatrix.postConcat(contrastMatrix)
        
        val paint = android.graphics.Paint()
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
        
        // Draw the original bitmap into the destination using the contrast/grayscale filter
        canvas.drawBitmap(src, 0f, 0f, paint)
        return dest
    }

    private fun getScaledBitmap(context: Context, uri: Uri, maxDimension: Int = 1536): Bitmap {
        val originalBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val max = Math.max(info.size.width, info.size.height)
                if (max > maxDimension) {
                    val scale = maxDimension.toFloat() / max
                    decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            val max = Math.max(bitmap.width, bitmap.height)
            if (max > maxDimension) {
                val scale = maxDimension.toFloat() / max
                val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                if (scaled != bitmap) bitmap.recycle()
                scaled
            } else {
                bitmap
            }
        }
        return originalBitmap.copy(Bitmap.Config.ARGB_8888, true) ?: originalBitmap
    }
}
