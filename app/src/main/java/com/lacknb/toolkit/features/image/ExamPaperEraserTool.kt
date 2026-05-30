package com.lacknb.toolkit.features.image

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.lacknb.toolkit.ui.components.PdfPreviewDialog
import android.content.Intent
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lacknb.toolkit.core.Tool
import com.lacknb.toolkit.core.ToolCategory
import com.lacknb.toolkit.ui.screens.ToolWorkspaceContainer
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt
import kotlin.math.pow

class ExamPaperEraserTool : Tool {
    override val id: String = "exam_paper_eraser"
    override val name: String = "试卷擦除工具"
    override val description: String = "智能切边矫正，过滤笔迹还原空白试卷，支持批量合并 PDF"
    override val icon: ImageVector = Icons.Default.AutoFixNormal
    override val category: ToolCategory = ToolCategory.IMAGE_SCANNING

    private enum class EraserMode {
        SPLIT_SLIDER,   // 左右拉伸滑块对比
        MAGIC_BRUSH    // 魔法手指手画擦除
    }

    private data class PageCropPoints(
        var p0: Offset,
        var p1: Offset,
        var p2: Offset,
        var p3: Offset
    )

    @Composable
    override fun Content(onBack: () -> Unit) {
        val context = LocalContext.current
        val haptic = LocalHapticFeedback.current
        val coroutineScope = rememberCoroutineScope()

        val rawBitmaps = remember { mutableStateListOf<Bitmap>() }
        val croppedBitmaps = remember { mutableStateListOf<Bitmap>() }
        val processedBitmaps = remember { mutableStateListOf<Bitmap>() }
        val pageCropPointsList = remember { mutableStateListOf<PageCropPoints>() }

        var currentMode by remember { mutableStateOf(EraserMode.SPLIT_SLIDER) }
        var isProcessing by remember { mutableStateOf(false) }
        var isSaving by remember { mutableStateOf(false) }
        var showPdfPreview by remember { mutableStateOf(false) }
        var scanningProgressPage by remember { mutableStateOf(0) }
        var activeTab by remember { mutableStateOf(0) } // 0: 切边矫正, 1: 字迹微调

        // Slider ratio
        var sliderRatio by remember { mutableStateOf(0.5f) }

        // Navigation state
        var selectedPreviewIndex by remember { mutableStateOf(0) }
        
        // Crop editor states
        val dragIndexState = remember { mutableStateOf(-1) }
        val canvasSizeState = remember { mutableStateOf(Offset.Zero) }
        val dragIndex = dragIndexState.value
        val canvasSize = canvasSizeState.value
        var activeBrushPoint by remember { mutableStateOf<Offset?>(null) }
        var tempCameraUri by remember { mutableStateOf<Uri?>(null) }

        LaunchedEffect(rawBitmaps.size) {
            if (rawBitmaps.isNotEmpty()) {
                while (pageCropPointsList.size < rawBitmaps.size) {
                    val index = pageCropPointsList.size
                    pageCropPointsList.add(
                        PageCropPoints(
                            p0 = Offset(0.0f, 0.0f),
                            p1 = Offset(1.0f, 0.0f),
                            p2 = Offset(1.0f, 1.0f),
                            p3 = Offset(0.0f, 1.0f)
                        )
                    )
                }
            }
        }

        // Processing Pipeline: 1. Perspective Warp -> 2. Whitening -> 3. Blue Ink Erase
        val performBatchProcessing = {
            if (rawBitmaps.isNotEmpty() && pageCropPointsList.size >= rawBitmaps.size) {
                isProcessing = true
                croppedBitmaps.clear()
                processedBitmaps.clear()
                
                coroutineScope.launch {
                    val tempCroppedList = mutableListOf<Bitmap>()
                    val tempProcessedList = mutableListOf<Bitmap>()
                    
                    withContext(Dispatchers.Default) {
                        for (i in rawBitmaps.indices) {
                            scanningProgressPage = i + 1
                            val bitmap = rawBitmaps[i]
                            val crop = pageCropPointsList[i]

                            // 1. Perspective Warp
                            val srcPoints = floatArrayOf(
                                crop.p0.x * bitmap.width, crop.p0.y * bitmap.height,
                                crop.p1.x * bitmap.width, crop.p1.y * bitmap.height,
                                crop.p2.x * bitmap.width, crop.p2.y * bitmap.height,
                                crop.p3.x * bitmap.width, crop.p3.y * bitmap.height
                            )
                            val dstWidth = 1240f
                            val dstHeight = 1754f
                            val dstPoints = floatArrayOf(
                                0f, 0f,
                                dstWidth, 0f,
                                dstWidth, dstHeight,
                                0f, dstHeight
                            )
                            val matrix = android.graphics.Matrix()
                            matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)
                            val cropped = Bitmap.createBitmap(dstWidth.toInt(), dstHeight.toInt(), Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(cropped)
                            val paint = Paint().apply {
                                isAntiAlias = true
                                isFilterBitmap = true
                            }
                            canvas.drawBitmap(bitmap, matrix, paint)
                            
                            // 2. Whitening (Paper flatten)
                            val whitened = enhanceDocument(cropped)
                            tempCroppedList.add(whitened)

                            // 3. Blue Ink Erase
                            val w = whitened.width
                            val h = whitened.height
                            val dest = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            val pixels = IntArray(w * h)
                            whitened.getPixels(pixels, 0, w, 0, 0, w, h)

                            val hsv = FloatArray(3)
                            for (j in pixels.indices) {
                                val color = pixels[j]
                                val r = (color shr 16) and 0xFF
                                val g = (color shr 8) and 0xFF
                                val b = color and 0xFF

                                android.graphics.Color.RGBToHSV(r, g, b, hsv)
                                val hue = hsv[0]
                                val saturation = hsv[1]
                                val value = hsv[2]

                                // Blue ink thresholds: hue between 165 and 255
                                if (hue in 165f..255f && saturation > 0.15f && value > 0.15f) {
                                    pixels[j] = android.graphics.Color.WHITE
                                }
                            }
                            dest.setPixels(pixels, 0, w, 0, 0, w, h)
                            tempProcessedList.add(dest)
                        }
                    }

                    croppedBitmaps.addAll(tempCroppedList)
                    processedBitmaps.addAll(tempProcessedList)
                    isProcessing = false
                    activeTab = 1
                    selectedPreviewIndex = 0
                    Toast.makeText(context, "批量矫正与滤除完毕！", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val batchPhotoPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickMultipleVisualMedia()
        ) { uris ->
            if (uris.isNotEmpty()) {
                try {
                    uris.forEach { uri ->
                        val bitmap = getScaledBitmap(context, uri)
                        rawBitmaps.add(bitmap)
                    }
                    croppedBitmaps.clear()
                    processedBitmaps.clear()
                    activeTab = 0
                    selectedPreviewIndex = rawBitmaps.size - uris.size
                } catch (e: Throwable) {
                    Toast.makeText(context, "批量导入失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Camera Capture Launcher
        val cameraLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.TakePicture()
        ) { success ->
            if (success) {
                tempCameraUri?.let { uri ->
                    try {
                        val bitmap = getScaledBitmap(context, uri)
                        rawBitmaps.add(bitmap)
                        croppedBitmaps.clear()
                        processedBitmaps.clear()
                        activeTab = 0
                        selectedPreviewIndex = rawBitmaps.size - 1
                    } catch (e: Throwable) {
                        Toast.makeText(context, "拍照解析失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val launchCamera = {
            try {
                val tempFile = File(context.cacheDir, "eraser_photo_${System.currentTimeMillis()}.jpg")
                if (tempFile.exists()) tempFile.delete()
                tempFile.createNewFile()
                val uri = FileProvider.getUriForFile(
                    context,
                    "com.lacknb.toolkit.fileprovider",
                    tempFile
                )
                tempCameraUri = uri
                cameraLauncher.launch(uri)
            } catch (e: Exception) {
                Toast.makeText(context, "拉起相机失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }

        val applyBrushEraserToBitmapPage = { start: Offset, end: Offset ->
            val activeBitmap = processedBitmaps.getOrNull(selectedPreviewIndex)
            if (activeBitmap != null && canvasSize != Offset.Zero) {
                val scaleX = activeBitmap.width.toFloat() / canvasSize.x
                val scaleY = activeBitmap.height.toFloat() / canvasSize.y

                val canvas = Canvas(activeBitmap)
                val paint = Paint().apply {
                    color = android.graphics.Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = 24f * scaleX
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                canvas.drawLine(start.x * scaleX, start.y * scaleY, end.x * scaleX, end.y * scaleY, paint)

                if (selectedPreviewIndex < processedBitmaps.size) {
                    processedBitmaps[selectedPreviewIndex] = activeBitmap
                }
            }
        }

        BackHandler(enabled = true) {
            if (activeTab == 1) {
                activeTab = 0
            } else if (rawBitmaps.isNotEmpty()) {
                rawBitmaps.clear()
                pageCropPointsList.clear()
                croppedBitmaps.clear()
                processedBitmaps.clear()
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
                // Header
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
                                .background(Brush.linearGradient(listOf(Color(0xFF00E676), Color(0xFF00B0FF)))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoFixNormal,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "真·试卷字迹滤除与PDF合并",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "先智能切边矫正，后秒级滤除笔迹，生成空白卷",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                if (rawBitmaps.isEmpty()) {
                    Text(
                        text = "载入待擦除笔迹的试卷（支持多选）：",
                        color = Color.LightGray,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Camera Card
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { launchCamera() }
                                .shadow(elevation = 4.dp, shape = RoundedCornerShape(12.dp)),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                            border = BorderStroke(1.dp, Color(0xFF2E2E2E)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = null,
                                    tint = Color(0xFF00E676),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "直接拍照",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "拍一张试卷照片",
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }

                        // Album Card
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    batchPhotoPickerLauncher.launch(
                                        androidx.activity.result.PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                }
                                .shadow(elevation = 4.dp, shape = RoundedCornerShape(12.dp)),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                            border = BorderStroke(1.dp, Color(0xFF2E2E2E)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudUpload,
                                    contentDescription = null,
                                    tint = Color(0xFF00E676),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "从相册选择",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "批量选择多页试卷",
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isProcessing) "运算处理中..." else "当前页面 (${selectedPreviewIndex + 1}/${rawBitmaps.size})",
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "拍照",
                                color = Color(0xFF00E676),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    if (!isProcessing) launchCamera()
                                }
                            )
                            Text(
                                text = "添加试卷",
                                color = Color(0xFF00E676),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    if (!isProcessing) {
                                        batchPhotoPickerLauncher.launch(
                                            androidx.activity.result.PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly
                                            )
                                        )
                                    }
                                }
                            )
                            Text(
                                text = "清除全部",
                                color = Color(0xFFE57373),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    if (!isProcessing) {
                                        rawBitmaps.clear()
                                        pageCropPointsList.clear()
                                        croppedBitmaps.clear()
                                        processedBitmaps.clear()
                                    }
                                }
                            )
                        }
                    }

                    // Tabs
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E1E1E))
                    ) {
                        listOf("1. 切边调整", "2. 字迹微调").forEachIndexed { index, title ->
                            val isTabSelected = activeTab == index
                            val isEnabled = index == 0 || processedBitmaps.isNotEmpty()
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(enabled = isEnabled) { activeTab = index }
                                    .background(if (isTabSelected) Color(0xFF2E2E2E) else Color.Transparent)
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = title,
                                    color = if (isTabSelected) Color(0xFF00B0FF) else if (isEnabled) Color.Gray else Color.DarkGray,
                                    fontWeight = if (isTabSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    if (activeTab == 0) {
                        val activeCropPoint = pageCropPointsList.getOrNull(selectedPreviewIndex)
                        val activeBitmap = rawBitmaps.getOrNull(selectedPreviewIndex)

                        if (activeCropPoint != null && activeBitmap != null) {
                            val imageBounds = calculateImageBounds(
                                canvasWidth = canvasSize.x,
                                canvasHeight = canvasSize.y,
                                bitmapWidth = activeBitmap.width,
                                bitmapHeight = activeBitmap.height
                            )
                            val screenP0 = Offset(imageBounds.left + activeCropPoint.p0.x * imageBounds.width, imageBounds.top + activeCropPoint.p0.y * imageBounds.height)
                            val screenP1 = Offset(imageBounds.left + activeCropPoint.p1.x * imageBounds.width, imageBounds.top + activeCropPoint.p1.y * imageBounds.height)
                            val screenP2 = Offset(imageBounds.left + activeCropPoint.p2.x * imageBounds.width, imageBounds.top + activeCropPoint.p2.y * imageBounds.height)
                            val screenP3 = Offset(imageBounds.left + activeCropPoint.p3.x * imageBounds.width, imageBounds.top + activeCropPoint.p3.y * imageBounds.height)

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(280.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF0F0F0F))
                                    .border(1.dp, Color(0xFF2E2E2E), RoundedCornerShape(12.dp))
                                    .onGloballyPositioned { layoutCoordinates ->
                                        canvasSizeState.value = Offset(
                                            layoutCoordinates.size.width.toFloat(),
                                            layoutCoordinates.size.height.toFloat()
                                        )
                                    }
                                    .pointerInput(selectedPreviewIndex, canvasSize) {
                                        var touchOffset = Offset.Zero
                                        detectDragGestures(
                                            onDragStart = { offset ->
                                                val activeCrop = pageCropPointsList.getOrNull(selectedPreviewIndex)
                                                val cSize = canvasSizeState.value
                                                if (activeCrop != null && cSize.x > 0f && cSize.y > 0f) {
                                                    val bounds = calculateImageBounds(
                                                        canvasWidth = cSize.x,
                                                        canvasHeight = cSize.y,
                                                        bitmapWidth = activeBitmap.width,
                                                        bitmapHeight = activeBitmap.height
                                                    )
                                                    val p0 = Offset(bounds.left + activeCrop.p0.x * bounds.width, bounds.top + activeCrop.p0.y * bounds.height)
                                                    val p1 = Offset(bounds.left + activeCrop.p1.x * bounds.width, bounds.top + activeCrop.p1.y * bounds.height)
                                                    val p2 = Offset(bounds.left + activeCrop.p2.x * bounds.width, bounds.top + activeCrop.p2.y * bounds.height)
                                                    val p3 = Offset(bounds.left + activeCrop.p3.x * bounds.width, bounds.top + activeCrop.p3.y * bounds.height)

                                                    val dist0 = getDistance(offset, p0)
                                                    val dist1 = getDistance(offset, p1)
                                                    val dist2 = getDistance(offset, p2)
                                                    val dist3 = getDistance(offset, p3)

                                                    val threshold = 120f
                                                    val selectedIdx = when {
                                                        dist0 < threshold && dist0 <= dist1 && dist0 <= dist2 && dist0 <= dist3 -> 0
                                                        dist1 < threshold && dist1 <= dist0 && dist1 <= dist2 && dist1 <= dist3 -> 1
                                                        dist2 < threshold && dist2 <= dist0 && dist2 <= dist1 && dist2 <= dist3 -> 2
                                                        dist3 < threshold && dist3 <= dist0 && dist3 <= dist1 && dist3 <= dist2 -> 3
                                                        else -> -1
                                                    }
                                                    dragIndexState.value = selectedIdx
                                                    if (selectedIdx >= 0) {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        val targetCenter = when (selectedIdx) {
                                                            0 -> p0
                                                            1 -> p1
                                                            2 -> p2
                                                            3 -> p3
                                                            else -> Offset.Zero
                                                        }
                                                        touchOffset = offset - targetCenter
                                                    }
                                                }
                                            },
                                            onDrag = { change, _ ->
                                                val activeCrop = pageCropPointsList.getOrNull(selectedPreviewIndex)
                                                val currentDragIndex = dragIndexState.value
                                                val cSize = canvasSizeState.value
                                                if (activeCrop != null && currentDragIndex >= 0 && cSize.x > 0f && cSize.y > 0f) {
                                                    val bounds = calculateImageBounds(
                                                        canvasWidth = cSize.x,
                                                        canvasHeight = cSize.y,
                                                        bitmapWidth = activeBitmap.width,
                                                        bitmapHeight = activeBitmap.height
                                                    )
                                                    if (bounds.width > 0f && bounds.height > 0f) {
                                                        val touchPoint = change.position - touchOffset
                                                        val clampedScreenPoint = Offset(
                                                            touchPoint.x.coerceIn(bounds.left, bounds.left + bounds.width),
                                                            touchPoint.y.coerceIn(bounds.top, bounds.top + bounds.height)
                                                        )
                                                        val updatedNormalizedPoint = Offset(
                                                            (clampedScreenPoint.x - bounds.left) / bounds.width,
                                                            (clampedScreenPoint.y - bounds.top) / bounds.height
                                                        )

                                                        when (currentDragIndex) {
                                                            0 -> activeCrop.p0 = updatedNormalizedPoint
                                                            1 -> activeCrop.p1 = updatedNormalizedPoint
                                                            2 -> activeCrop.p2 = updatedNormalizedPoint
                                                            3 -> activeCrop.p3 = updatedNormalizedPoint
                                                        }
                                                        pageCropPointsList[selectedPreviewIndex] = activeCrop.copy()
                                                    }
                                                }
                                            },
                                            onDragEnd = {
                                                dragIndexState.value = -1
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            }
                                        )
                                    }
                            ) {
                                Crossfade(targetState = activeBitmap, label = "editorImageCrossfade") { bmp ->
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }

                                ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                                    val strokePath = Path().apply {
                                        moveTo(screenP0.x, screenP0.y)
                                        lineTo(screenP1.x, screenP1.y)
                                        lineTo(screenP2.x, screenP2.y)
                                        lineTo(screenP3.x, screenP3.y)
                                        close()
                                    }
                                    drawPath(
                                        path = strokePath,
                                        color = Color(0xFF00E676),
                                        style = Stroke(
                                            width = 2.dp.toPx(),
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                                        )
                                    )

                                    val points = listOf(screenP0, screenP1, screenP2, screenP3)
                                    points.forEachIndexed { index, point ->
                                        val isSelected = index == dragIndex
                                        drawCircle(
                                            color = if (isSelected) Color(0x6600E676) else Color(0x4D00E676),
                                            radius = if (isSelected) 24.dp.toPx() else 14.dp.toPx(),
                                            center = point
                                        )
                                        drawCircle(
                                            color = if (isSelected) Color.White else Color(0xFF00E676),
                                            radius = if (isSelected) 8.dp.toPx() else 7.dp.toPx(),
                                            center = point
                                        )
                                        drawCircle(
                                            color = if (isSelected) Color(0xFF00E676) else Color.White,
                                            radius = if (isSelected) 4.dp.toPx() else 2.5.dp.toPx(),
                                            center = point
                                        )
                                    }
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = performBatchProcessing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B0FF)),
                            shape = RoundedCornerShape(26.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Transform, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "矫正切边并自动擦除笔迹 (${rawBitmaps.size}页)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                    } else { // activeTab == 1 (字迹微调)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            listOf(
                                EraserMode.SPLIT_SLIDER to "左右滑块对比",
                                EraserMode.MAGIC_BRUSH to "魔法画笔"
                            ).forEach { (mode, title) ->
                                val isSelected = currentMode == mode
                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 4.dp)
                                        .clickable { currentMode = mode },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) Color(0xFF00B0FF).copy(alpha = 0.2f) else Color(0xFF1E1E1E)
                                    ),
                                    border = if (isSelected) BorderStroke(1.dp, Color(0xFF00B0FF)) else BorderStroke(1.dp, Color(0xFF2E2E2E)),
                                ) {
                                    Text(
                                        text = title,
                                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                        color = if (isSelected) Color(0xFF00B0FF) else Color.Gray,
                                        fontSize = 12.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0F0F0F))
                                .border(1.dp, Color(0xFF2E2E2E), RoundedCornerShape(12.dp))
                                .onGloballyPositioned { layoutCoordinates ->
                                    if (currentMode == EraserMode.SPLIT_SLIDER) {
                                        canvasSizeState.value = Offset(
                                            layoutCoordinates.size.width.toFloat(),
                                            layoutCoordinates.size.height.toFloat()
                                        )
                                    }
                                }
                                .pointerInput(currentMode, selectedPreviewIndex) {
                                    if (currentMode == EraserMode.SPLIT_SLIDER) {
                                        detectDragGestures { change, _ ->
                                            sliderRatio = (change.position.x / canvasSizeState.value.x).coerceIn(0f, 1f)
                                        }
                                    }
                                }
                        ) {
                            ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                                val w = size.width
                                val h = size.height
                                val rawImg = croppedBitmaps.getOrNull(selectedPreviewIndex)
                                val cleanImg = processedBitmaps.getOrNull(selectedPreviewIndex)

                                if (rawImg != null && cleanImg != null) {
                                    if (currentMode == EraserMode.SPLIT_SLIDER) {
                                        val splitX = w * sliderRatio

                                        clipRect(right = splitX) {
                                            drawImage(
                                                image = rawImg.asImageBitmap(),
                                                dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt())
                                            )
                                        }

                                        clipRect(left = splitX) {
                                            drawImage(
                                                image = cleanImg.asImageBitmap(),
                                                dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt())
                                            )
                                        }

                                        drawLine(
                                            color = Color(0xFF00B0FF),
                                            start = Offset(splitX, 0f),
                                            end = Offset(splitX, h),
                                            strokeWidth = 3.dp.toPx()
                                        )
                                        drawCircle(
                                            color = Color(0xFF00B0FF),
                                            radius = 18.dp.toPx(),
                                            center = Offset(splitX, h / 2f)
                                        )
                                        drawCircle(
                                            color = Color.White,
                                            radius = 14.dp.toPx(),
                                            center = Offset(splitX, h / 2f)
                                        )
                                    } else {
                                        drawImage(
                                            image = cleanImg.asImageBitmap(),
                                            dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt())
                                        )
                                    }
                                }
                            }
                        }

                        if (currentMode == EraserMode.MAGIC_BRUSH) {
                            androidx.compose.ui.window.Dialog(
                                onDismissRequest = { currentMode = EraserMode.SPLIT_SLIDER },
                                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F))
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(onClick = { currentMode = EraserMode.SPLIT_SLIDER }) {
                                            Icon(Icons.Default.ArrowBack, contentDescription = "Close", tint = Color.White)
                                        }
                                        Text("魔法画笔 (全屏擦除)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                        Spacer(modifier = Modifier.width(48.dp))
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .onGloballyPositioned { layoutCoordinates ->
                                                canvasSizeState.value = Offset(
                                                    layoutCoordinates.size.width.toFloat(),
                                                    layoutCoordinates.size.height.toFloat()
                                                )
                                            }
                                            .pointerInput(selectedPreviewIndex) {
                                                detectDragGestures(
                                                    onDragStart = { offset ->
                                                        activeBrushPoint = offset
                                                    },
                                                    onDrag = { change, _ ->
                                                        val start = activeBrushPoint
                                                        val end = change.position
                                                        if (start != null) {
                                                            applyBrushEraserToBitmapPage(start, end)
                                                        }
                                                        activeBrushPoint = end
                                                    },
                                                    onDragEnd = {
                                                        activeBrushPoint = null
                                                    }
                                                )
                                            }
                                    ) {
                                        ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                                            val cleanImg = processedBitmaps.getOrNull(selectedPreviewIndex)
                                            if (cleanImg != null) {
                                                drawImage(
                                                    image = cleanImg.asImageBitmap(),
                                                    dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())
                                                )
                                                activeBrushPoint?.let { touchPoint ->
                                                    drawCircle(
                                                        color = Color(0xFF00B0FF).copy(alpha = 0.3f),
                                                        radius = 24.dp.toPx(),
                                                        center = touchPoint
                                                    )
                                                    drawCircle(
                                                        color = Color(0xFF00B0FF),
                                                        radius = 3.dp.toPx(),
                                                        center = touchPoint
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { showPdfPreview = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .shadow(elevation = 6.dp, shape = RoundedCornerShape(28.dp)),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F51B5)),
                            shape = RoundedCornerShape(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "预览与导出 PDF",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(rawBitmaps) { index, item ->
                            val isSelected = selectedPreviewIndex == index
                            val activeDisplayBitmap = if (activeTab == 0) item else processedBitmaps.getOrNull(index) ?: item
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) Color(0xFF00E676) else Color(0xFF2E2E2E),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { selectedPreviewIndex = index },
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = activeDisplayBitmap.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(4.dp)
                                        .size(16.dp)
                                        .background(Color(0xFF00E676), RoundedCornerShape(8.dp)),
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
            }
        }
        
        if (isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .clickable(enabled = false) {}, // Block all touch interactions
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFF2E2E2E)),
                    modifier = Modifier.width(280.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF00B0FF),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "正在矫正并擦除第 ${scanningProgressPage}/${rawBitmaps.size} 页...",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
        
        if (showPdfPreview) {
            PdfPreviewDialog(
                bitmaps = processedBitmaps,
                defaultFileName = "CleanExam_${System.currentTimeMillis()}",
                onDismiss = { showPdfPreview = false }
            )
        }
    }

    private data class ImageDisplayBounds(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float
    )

    private fun calculateImageBounds(
        canvasWidth: Float,
        canvasHeight: Float,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): ImageDisplayBounds {
        if (canvasWidth <= 0f || canvasHeight <= 0f || bitmapWidth <= 0 || bitmapHeight <= 0) {
            return ImageDisplayBounds(0f, 0f, canvasWidth, canvasHeight)
        }
        val canvasRatio = canvasWidth / canvasHeight
        val bitmapRatio = bitmapWidth.toFloat() / bitmapHeight.toFloat()

        val displayWidth: Float
        val displayHeight: Float
        val left: Float
        val top: Float

        if (bitmapRatio > canvasRatio) {
            displayWidth = canvasWidth
            displayHeight = canvasWidth / bitmapRatio
            left = 0f
            top = (canvasHeight - displayHeight) / 2f
        } else {
            displayHeight = canvasHeight
            displayWidth = canvasHeight * bitmapRatio
            left = (canvasWidth - displayWidth) / 2f
            top = 0f
        }
        return ImageDisplayBounds(left, top, displayWidth, displayHeight)
    }

    private fun getDistance(a: Offset, b: Offset): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun detectDocumentCorners(bitmap: Bitmap): PageCropPoints? {
        try {
            val scale = 0.08f // Downsample to 8% for ultra-fast calculation
            val w = (bitmap.width * scale).toInt().coerceAtLeast(40)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(40)
            val small = Bitmap.createScaledBitmap(bitmap, w, h, false)

            val pixels = IntArray(w * h)
            small.getPixels(pixels, 0, w, 0, 0, w, h)

            val lums = FloatArray(w * h)
            var sum = 0f
            for (i in pixels.indices) {
                val color = pixels[i]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                lums[i] = 0.299f * r + 0.587f * g + 0.114f * b
                sum += lums[i]
            }
            val avgLum = sum / (w * h)

            val points = mutableListOf<Offset>()
            // Consider pixels brighter than 103% average brightness as paper sheets on desks
            val threshold = avgLum * 1.03f
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    if (lums[idx] > threshold) {
                        points.add(Offset(x.toFloat(), y.toFloat()))
                    }
                }
            }

            if (points.size < 20) return null

            var tl = points[0]
            var tr = points[0]
            var br = points[0]
            var bl = points[0]

            var minSum = tl.x + tl.y
            var maxDiff = tr.x - tr.y
            var maxSum = br.x + br.y
            var minDiff = bl.x - bl.y

            for (p in points) {
                val s = p.x + p.y
                val d = p.x - p.y

                if (s < minSum) {
                    minSum = s
                    tl = p
                }
                if (d > maxDiff) {
                    maxDiff = d
                    tr = p
                }
                if (s > maxSum) {
                    maxSum = s
                    br = p
                }
                if (d < minDiff) {
                    minDiff = d
                    bl = p
                }
            }

            return PageCropPoints(
                p0 = Offset(tl.x / w, tl.y / h),
                p1 = Offset(tr.x / w, tr.y / h),
                p2 = Offset(br.x / w, br.y / h),
                p3 = Offset(bl.x / w, bl.y / h)
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun enhanceDocument(src: Bitmap): Bitmap {
        try {
            val width = src.width
            val height = src.height
            val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            
            val scale = 0.2f
            val sw = (width * scale).toInt().coerceAtLeast(10)
            val sh = (height * scale).toInt().coerceAtLeast(10)
            val small = Bitmap.createScaledBitmap(src, sw, sh, true)
            
            val smallPixels = IntArray(sw * sh)
            small.getPixels(smallPixels, 0, sw, 0, 0, sw, sh)
            
            val blurRadius = (sw / 6).coerceAtLeast(3)
            val blurredSmallPixels = boxBlurRGB(smallPixels, sw, sh, blurRadius)
            
            val blurredSmall = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            blurredSmall.setPixels(blurredSmallPixels, 0, sw, 0, 0, sw, sh)
            
            val background = Bitmap.createScaledBitmap(blurredSmall, width, height, true)
            
            val srcPixels = IntArray(width * height)
            val bgPixels = IntArray(width * height)
            src.getPixels(srcPixels, 0, width, 0, 0, width, height)
            background.getPixels(bgPixels, 0, width, 0, 0, width, height)
            
            val outPixels = IntArray(width * height)
            for (i in srcPixels.indices) {
                val sc = srcPixels[i]
                val bg = bgPixels[i]
                
                val sr = (sc shr 16) and 0xFF
                val sg = (sc shr 8) and 0xFF
                val sb = sc and 0xFF
                
                val bgr = ((bg shr 16) and 0xFF).coerceAtLeast(1)
                val bgg = ((bg shr 8) and 0xFF).coerceAtLeast(1)
                val bgb = (bg and 0xFF).coerceAtLeast(1)
                
                var nr = (sr.toFloat() / bgr.toFloat() * 255f).toInt().coerceIn(0, 255)
                var ng = (sg.toFloat() / bgg.toFloat() * 255f).toInt().coerceIn(0, 255)
                var nb = (sb.toFloat() / bgb.toFloat() * 255f).toInt().coerceIn(0, 255)
                
                val gray = 0.299f * nr + 0.587f * ng + 0.114f * nb
                
                if (gray < 220f) {
                    val factor = (gray / 220f).toDouble().pow(1.2).toFloat()
                    nr = (nr * factor).toInt().coerceIn(0, 255)
                    ng = (ng * factor).toInt().coerceIn(0, 255)
                    nb = (nb * factor).toInt().coerceIn(0, 255)
                } else {
                    nr = 255
                    ng = 255
                    nb = 255
                }
                
                outPixels[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
            
            dest.setPixels(outPixels, 0, width, 0, 0, width, height)
            return dest
        } catch (e: Exception) {
            return src.copy(Bitmap.Config.ARGB_8888, true)
        }
    }

    private fun boxBlurRGB(pixels: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val size = w * h
        val out = IntArray(size)
        
        val r = IntArray(size)
        val g = IntArray(size)
        val b = IntArray(size)
        
        for (i in pixels.indices) {
            val c = pixels[i]
            r[i] = (c shr 16) and 0xFF
            g[i] = (c shr 8) and 0xFF
            b[i] = c and 0xFF
        }
        
        val tempR = IntArray(size)
        val tempG = IntArray(size)
        val tempB = IntArray(size)
        
        // Horizontal
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sumR = 0
                var sumG = 0
                var sumB = 0
                var count = 0
                for (dx in -radius..radius) {
                    val nx = x + dx
                    if (nx in 0 until w) {
                        val idx = y * w + nx
                        sumR += r[idx]
                        sumG += g[idx]
                        sumB += b[idx]
                        count++
                    }
                }
                val idx = y * w + x
                tempR[idx] = sumR / count
                tempG[idx] = sumG / count
                tempB[idx] = sumB / count
            }
        }
        
        // Vertical
        for (x in 0 until w) {
            for (y in 0 until h) {
                var sumR = 0
                var sumG = 0
                var sumB = 0
                var count = 0
                for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny in 0 until h) {
                        val idx = ny * w + x
                        sumR += tempR[idx]
                        sumG += tempG[idx]
                        sumB += tempB[idx]
                        count++
                    }
                }
                val idx = y * w + x
                out[idx] = (0xFF shl 24) or ((sumR / count) shl 16) or ((sumG / count) shl 8) or (sumB / count)
            }
        }
        
        return out
    }
    
    // Dynamic tilted handwritten exam sheet builder with page-specific contents
    private fun createSampleExamWithBlueHandwriting(pageIndex: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(android.graphics.Color.DKGRAY)

        val paperPaint = Paint().apply {
            color = android.graphics.Color.parseColor("#FFFDF0")
            isAntiAlias = true
        }

        // Tilted quad coordinates with small offsets
        val offsetFactor = pageIndex * 15f
        val paperPath = android.graphics.Path().apply {
            moveTo(200f + offsetFactor, 120f + offsetFactor) // TL
            lineTo(680f - offsetFactor, 180f + offsetFactor) // TR
            lineTo(600f - offsetFactor, 540f - offsetFactor) // BR
            lineTo(120f + offsetFactor, 450f - offsetFactor) // BL
            close()
        }
        canvas.drawPath(paperPath, paperPaint)

        // Save canvas, rotate and translate to draw text aligned with the tilted paper
        canvas.save()
        // Approximation of the tilt transformation for drawing text inside
        canvas.translate(180f + offsetFactor, 160f + offsetFactor)
        canvas.rotate(8f)

        val printPaint = Paint().apply {
            color = android.graphics.Color.parseColor("#1C1C1C")
            textSize = 24f
            isAntiAlias = true
            strokeWidth = 2.0f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val linePaint = Paint().apply {
            color = android.graphics.Color.parseColor("#D0CBB0")
            strokeWidth = 1.5f
        }

        canvas.drawText("期末数学检测卷 - 第 ${pageIndex + 1} 页", 40f, 0f, printPaint)
        canvas.drawLine(-140f, 25f, 580f, 25f, linePaint)

        printPaint.textSize = 18f
        
        val handwritePaint = Paint().apply {
            color = android.graphics.Color.parseColor("#002BBD") // Distinct blue ink
            textSize = 22f
            strokeWidth = 3.5f
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.ITALIC)
        }

        if (pageIndex == 0) {
            canvas.drawText("一、填空题 (每题5分，共20分)", -140f, 70f, printPaint)
            canvas.drawText("1. 已知函数 f(x) = 2x + 5，若 f(a) = 15，则 a = ___。", -120f, 120f, printPaint)
            canvas.drawText("5", 300f, 118f, handwritePaint)

            canvas.drawText("2. 若一元二次方程 x² - 4x + k = 0 有两个相等实根，则 k = ___。", -120f, 190f, printPaint)
            canvas.drawText("4", 370f, 188f, handwritePaint)
        } else {
            canvas.drawText("二、解答题 (需要写出完整的计算证明步骤)", -140f, 70f, printPaint)
            canvas.drawText("3. 计算行列式： 2x - y = 5 且 3x + y = 10，求 x 与 y 的值。", -120f, 120f, printPaint)

            canvas.drawText("解：两式相加得 5x = 15", -90f, 180f, handwritePaint)
            canvas.drawText("  解得 x = 3", -90f, 225f, handwritePaint)
            canvas.drawText("  代入第一式得 6 - y = 5  => y = 1", -90f, 270f, handwritePaint)
        }

        val stampPaint = Paint().apply {
            color = android.graphics.Color.parseColor("#E65100")
            textSize = 20f
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        canvas.drawText("批改得分：优秀", 320f, 320f, stampPaint)

        canvas.restore()
        return bitmap
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
