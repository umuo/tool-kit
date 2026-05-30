package com.lacknb.toolkit.features.image

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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
import com.lacknb.toolkit.ui.components.PdfPreviewDialog
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt
import kotlin.math.pow

class PhotoToElectronicDocTool : Tool {
    override val id: String = "photo_to_electronic_doc"
    override val name: String = "照片转电子版"
    override val description: String = "智能切边与透视校正，支持多图批量导入与合并生成 PDF"
    override val icon: ImageVector = Icons.Default.Scanner
    override val category: ToolCategory = ToolCategory.IMAGE_SCANNING

    private enum class DocFilter {
        ORIGINAL,       // 原图
        MAGIC_COLOR,    // 魔幻色彩
        BLACK_WHITE,    // 黑白文档
        GRAYSCALE       // 灰度
    }

    // Class to maintain custom crop handles for each page in batch imports
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

        val selectedBitmaps = remember { mutableStateListOf<Bitmap>() }
        val croppedBitmaps = remember { mutableStateListOf<Bitmap>() }
        val pageCropPointsList = remember { mutableStateListOf<PageCropPoints>() }

        val coroutineScope = rememberCoroutineScope()
        var processingProgress by remember { mutableStateOf<String?>(null) }
        var filteredActiveBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var isFilterLoading by remember { mutableStateOf(false) }

        var currentFilter by remember { mutableStateOf(DocFilter.MAGIC_COLOR) }
        var isProcessing by remember { mutableStateOf(false) }
        var showPdfPreview by remember { mutableStateOf(false) }
        var activeTab by remember { mutableStateOf(0) } // 0: 批量切边, 1: 效果与合并导出

        var selectedPreviewIndex by remember { mutableStateOf(0) }
        var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
        val dragIndexState = remember { mutableStateOf(-1) }
        val canvasSizeState = remember { mutableStateOf(Offset.Zero) }
        val dragIndex = dragIndexState.value
        val canvasSize = canvasSizeState.value

        // Initialize default crop points in normalized coordinates (0.0 to 1.0)
        LaunchedEffect(selectedBitmaps.size) {
            if (selectedBitmaps.isNotEmpty()) {
                // Initialize crop points for any pages that don't have them yet
                while (pageCropPointsList.size < selectedBitmaps.size) {
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

        // Multi Image Picker Launcher
        val batchPhotoPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickMultipleVisualMedia()
        ) { uris ->
            if (uris.isNotEmpty()) {
                try {
                    uris.forEach { uri ->
                        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val source = ImageDecoder.createSource(context.contentResolver, uri)
                            ImageDecoder.decodeBitmap(source)
                        } else {
                            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                        }
                        selectedBitmaps.add(bitmap.copy(Bitmap.Config.ARGB_8888, true))
                    }
                    croppedBitmaps.clear()
                    activeTab = 0
                    selectedPreviewIndex = selectedBitmaps.size - uris.size
                } catch (e: Exception) {
                    Toast.makeText(context, "加载图片失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
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
                        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val source = ImageDecoder.createSource(context.contentResolver, uri)
                            ImageDecoder.decodeBitmap(source)
                        } else {
                            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                        }
                        selectedBitmaps.add(bitmap.copy(Bitmap.Config.ARGB_8888, true))
                        croppedBitmaps.clear()
                        activeTab = 0
                        selectedPreviewIndex = selectedBitmaps.size - 1
                    } catch (e: Exception) {
                        Toast.makeText(context, "拍照解析失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val launchCamera = {
            try {
                val tempFile = File(context.cacheDir, "doc_photo_${System.currentTimeMillis()}.jpg")
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

        // Helper to retrieve filter-applied bitmap for export
        val getFilterAppliedBitmap = { src: Bitmap ->
            when (currentFilter) {
                DocFilter.ORIGINAL -> src.copy(Bitmap.Config.ARGB_8888, true)
                DocFilter.MAGIC_COLOR -> enhanceDocument(src, magicColor = true, grayscale = false)
                DocFilter.BLACK_WHITE -> enhanceDocument(src, magicColor = false, grayscale = false)
                DocFilter.GRAYSCALE -> enhanceDocument(src, magicColor = false, grayscale = true)
            }
        }

        // Batch homography warp scan using setPolyToPoly matrix on all pages inside background coroutines
        val performBatchPerspectiveWarp = {
            if (selectedBitmaps.isNotEmpty() && pageCropPointsList.size >= selectedBitmaps.size) {
                isProcessing = true
                coroutineScope.launch {
                    croppedBitmaps.clear()
                    processingProgress = "正在启动裁切引擎..."

                    val warpedList = withContext(Dispatchers.Default) {
                        val temp = mutableListOf<Bitmap>()
                        for (i in selectedBitmaps.indices) {
                            withContext(Dispatchers.Main) {
                                processingProgress = "正在裁切并矫正第 ${i + 1}/${selectedBitmaps.size} 张照片..."
                            }
                            val bitmap = selectedBitmaps[i]
                            val crop = pageCropPointsList[i]

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
                            temp.add(cropped)
                        }
                        temp
                    }

                    // Pre-warm the electronic filter for the first active preview page to make landing instant
                    if (warpedList.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            processingProgress = "正在进行首面智能墨水强化与阴影消除..."
                        }
                        withContext(Dispatchers.Default) {
                            filteredActiveBitmap = getFilterAppliedBitmap(warpedList[0])
                        }
                    }

                    croppedBitmaps.addAll(warpedList)
                    isProcessing = false
                    processingProgress = null
                    activeTab = 1
                    selectedPreviewIndex = 0
                }
            }
        }

        BackHandler(enabled = true) {
            if (activeTab == 1) {
                activeTab = 0
            } else if (selectedBitmaps.isNotEmpty()) {
                selectedBitmaps.clear()
                pageCropPointsList.clear()
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
                // Header card
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
                                .background(Brush.linearGradient(listOf(Color(0xFFFF5722), Color(0xFFFF9800)))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CropFree,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "真·批量透视切边与合并PDF",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "支持批量导入多图，独立调整切边，一键生成合并 PDF",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // If empty select layouts
                if (selectedBitmaps.isEmpty()) {
                    Text(
                        text = "选择待合并切边照片（支持多选）：",
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
                                    tint = Color(0xFFFF9800),
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
                                    text = "拍一张文档照片",
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
                                    imageVector = Icons.Default.AddPhotoAlternate,
                                    contentDescription = null,
                                    tint = Color(0xFFFF9800),
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
                                    text = "批量选择多张照片",
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }

                } else {
                    // Toolbar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (activeTab == 0) "独立调整各页切边 (${selectedPreviewIndex + 1}/${selectedBitmaps.size})" else "多页合并预览与导出",
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "拍照",
                                color = Color(0xFFFF9800),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    if (!isProcessing) launchCamera()
                                }
                            )
                            Text(
                                text = "添加图片",
                                color = Color(0xFFFF9800),
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
                                        selectedBitmaps.clear()
                                        croppedBitmaps.clear()
                                        pageCropPointsList.clear()
                                    }
                                }
                            )
                        }
                    }

                    // Navigation slider tab
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E1E1E))
                    ) {
                        listOf("独立切边调整", "电子效果输出").forEachIndexed { index, title ->
                            val isTabSelected = activeTab == index
                            val isEnabled = index == 0 || croppedBitmaps.isNotEmpty()
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
                                    color = if (isTabSelected) Color(0xFFFF9800) else if (isEnabled) Color.Gray else Color.DarkGray,
                                    fontWeight = if (isTabSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    // Render select workspaces
                    if (activeTab == 0) {
                        Column {
                            val activeCropPoint = pageCropPointsList.getOrNull(selectedPreviewIndex)
                            val activeBitmap = selectedBitmaps.getOrNull(selectedPreviewIndex)

                            if (activeCropPoint != null && activeBitmap != null) {
                                val imageBounds = calculateImageBounds(
                                    canvasWidth = canvasSize.x,
                                    canvasHeight = canvasSize.y,
                                    bitmapWidth = activeBitmap.width,
                                    bitmapHeight = activeBitmap.height
                                )

                                val screenP0 = Offset(
                                    imageBounds.left + activeCropPoint.p0.x * imageBounds.width,
                                    imageBounds.top + activeCropPoint.p0.y * imageBounds.height
                                )
                                val screenP1 = Offset(
                                    imageBounds.left + activeCropPoint.p1.x * imageBounds.width,
                                    imageBounds.top + activeCropPoint.p1.y * imageBounds.height
                                )
                                val screenP2 = Offset(
                                    imageBounds.left + activeCropPoint.p2.x * imageBounds.width,
                                    imageBounds.top + activeCropPoint.p2.y * imageBounds.height
                                )
                                val screenP3 = Offset(
                                    imageBounds.left + activeCropPoint.p3.x * imageBounds.width,
                                    imageBounds.top + activeCropPoint.p3.y * imageBounds.height
                                )

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

                                                        val threshold = 120f // Generous touch target area (in pixels)
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
                                                            // Absolute tracking using touch position with offset correction to prevent snapping jitter
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
                                                            // Force UI redraw of points inside state arrays
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
                                    // Bounding backdrop
                                    Crossfade(targetState = activeBitmap, label = "editorImageCrossfade") { bmp ->
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }

                                    // Quad canvas overlay drawing
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
                                            color = Color(0xFF03A9F4),
                                            style = Stroke(
                                                width = 2.dp.toPx(),
                                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                                            )
                                        )

                                        val points = listOf(screenP0, screenP1, screenP2, screenP3)
                                        points.forEachIndexed { index, point ->
                                            val isSelected = index == dragIndex
                                            drawCircle(
                                                color = if (isSelected) Color(0x6603A9F4) else Color(0x4D03A9F4),
                                                radius = if (isSelected) 24.dp.toPx() else 14.dp.toPx(),
                                                center = point
                                            )
                                            drawCircle(
                                                color = if (isSelected) Color.White else Color(0xFF03A9F4),
                                                radius = if (isSelected) 8.dp.toPx() else 7.dp.toPx(),
                                                center = point
                                            )
                                            drawCircle(
                                                color = if (isSelected) Color(0xFF03A9F4) else Color.White,
                                                radius = if (isSelected) 4.dp.toPx() else 2.5.dp.toPx(),
                                                center = point
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Horizontal preview list roll
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
                                                color = if (isSelected) Color(0xFFFF9800) else Color(0xFF2E2E2E),
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
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .padding(4.dp)
                                                .size(16.dp)
                                                .background(Color(0xFFFF9800), RoundedCornerShape(8.dp)),
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

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = performBatchPerspectiveWarp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5722)),
                                shape = RoundedCornerShape(26.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Crop, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = "下一步 (${selectedBitmaps.size}页)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        // Dynamic Output and saving multi-page PDF options
                        val activeCroppedBitmap = croppedBitmaps.getOrNull(selectedPreviewIndex)

                        // Asynchronously calculate active filtered preview image to keep UI thread 100% responsive
                        LaunchedEffect(activeCroppedBitmap, currentFilter) {
                            if (activeCroppedBitmap != null) {
                                isFilterLoading = true
                                val result = withContext(Dispatchers.Default) {
                                    getFilterAppliedBitmap(activeCroppedBitmap)
                                }
                                filteredActiveBitmap = result
                                isFilterLoading = false
                            } else {
                                filteredActiveBitmap = null
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF0C0C0C))
                                .border(1.dp, Color(0xFF2E2E2E), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isFilterLoading) {
                                CircularProgressIndicator(
                                    color = Color(0xFFFF9800),
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(36.dp)
                                )
                            } else if (filteredActiveBitmap != null) {
                                Crossfade(targetState = filteredActiveBitmap!!, label = "filterCrossfade") { bmp ->
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxHeight(0.9f)
                                            .shadow(elevation = 6.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Horizontal thumbnail selector for output pages
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(croppedBitmaps) { index, item ->
                                val isSelected = selectedPreviewIndex == index
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) Color(0xFFFF9800) else Color(0xFF2E2E2E),
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
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(4.dp)
                                            .size(16.dp)
                                            .background(Color(0xFFFF9800), RoundedCornerShape(8.dp)),
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

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "选择全局电子档排版滤镜：",
                            color = Color.LightGray,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                Triple(DocFilter.ORIGINAL, "原图", Icons.Default.Photo),
                                Triple(DocFilter.MAGIC_COLOR, "魔幻色彩", Icons.Default.AutoAwesome),
                                Triple(DocFilter.BLACK_WHITE, "黑白文档", Icons.Default.FileCopy),
                                Triple(DocFilter.GRAYSCALE, "极简灰度", Icons.Default.FilterBAndW)
                            ).forEach { (filter, title, filterIcon) ->
                                val isFilterSelected = currentFilter == filter
                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { currentFilter = filter },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isFilterSelected) Color(0xFFFF5722).copy(alpha = 0.2f) else Color(0xFF1E1E1E)
                                    ),
                                    border = if (isFilterSelected) {
                                        BorderStroke(1.5.dp, Color(0xFFFF9800))
                                    } else {
                                        BorderStroke(1.dp, Color(0xFF2E2E2E))
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = filterIcon,
                                            contentDescription = null,
                                            tint = if (isFilterSelected) Color(0xFFFF9800) else Color.Gray,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = title,
                                            color = if (isFilterSelected) Color.White else Color.LightGray,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

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
                }
            }
        }

        // Full-screen Glassmorphism Loading Spinner overlay
        if (isProcessing && processingProgress != null) {
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
                            color = Color(0xFFFF9800),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = processingProgress!!,
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
            val filteredBitmaps = remember(croppedBitmaps, currentFilter) {
                croppedBitmaps.map { getFilterAppliedBitmap(it) }
            }
            PdfPreviewDialog(
                bitmaps = filteredBitmaps,
                defaultFileName = "Scanner_${System.currentTimeMillis()}",
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

            // Extrema Search
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

    // Dynamic tilted document generator with subtle page offsets to represent multi-page setups
    private fun createSampleTiltedBitmap(pageIndex: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.DKGRAY)

        val paperPaint = Paint().apply {
            color = android.graphics.Color.WHITE
            isAntiAlias = true
        }

        // Tilted quad coordinates with small offsets depending on pageIndex
        val offsetFactor = pageIndex * 15f
        val paperPath = android.graphics.Path().apply {
            moveTo(200f + offsetFactor, 120f + offsetFactor) // TL
            lineTo(680f - offsetFactor, 180f + offsetFactor) // TR
            lineTo(600f - offsetFactor, 540f - offsetFactor) // BR
            lineTo(120f + offsetFactor, 450f - offsetFactor) // BL
            close()
        }
        canvas.drawPath(paperPath, paperPaint)

        val textPaint = Paint().apply {
            color = android.graphics.Color.BLACK
            strokeWidth = 5.0f
            isAntiAlias = true
        }

        // Draw skewed lines
        canvas.drawLine(240f + offsetFactor, 180f + offsetFactor, 620f - offsetFactor, 220f + offsetFactor, textPaint)
        canvas.drawLine(220f + offsetFactor, 240f + offsetFactor, 600f - offsetFactor, 280f + offsetFactor, textPaint)
        canvas.drawLine(200f + offsetFactor, 300f + offsetFactor, 580f - offsetFactor, 340f + offsetFactor, textPaint)

        // Bold page number inside skewed document
        val pageNumPaint = Paint().apply {
            color = android.graphics.Color.RED
            textSize = 36f
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        canvas.drawText("Page ${pageIndex + 1}", 480f - offsetFactor, 460f - offsetFactor, pageNumPaint)

        return bitmap
    }

    private fun enhanceDocument(src: Bitmap, magicColor: Boolean, grayscale: Boolean): Bitmap {
        try {
            val width = src.width
            val height = src.height
            val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            
            // Downsample to 20% to compute low-frequency background illumination
            val scale = 0.2f
            val sw = (width * scale).toInt().coerceAtLeast(10)
            val sh = (height * scale).toInt().coerceAtLeast(10)
            val small = Bitmap.createScaledBitmap(src, sw, sh, true)
            
            val smallPixels = IntArray(sw * sh)
            small.getPixels(smallPixels, 0, sw, 0, 0, sw, sh)
            
            // Blur with large local block radius to estimate slow-varying desk shadow/ambient light
            val blurRadius = (sw / 6).coerceAtLeast(3)
            val blurredSmallPixels = boxBlurRGB(smallPixels, sw, sh, blurRadius)
            
            val blurredSmall = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            blurredSmall.setPixels(blurredSmallPixels, 0, sw, 0, 0, sw, sh)
            
            // Scaled back to full-res representation
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
                
                // Illumination Division: E = I / B * 255
                var nr = (sr.toFloat() / bgr.toFloat() * 255f).toInt().coerceIn(0, 255)
                var ng = (sg.toFloat() / bgg.toFloat() * 255f).toInt().coerceIn(0, 255)
                var nb = (sb.toFloat() / bgb.toFloat() * 255f).toInt().coerceIn(0, 255)
                
                val gray = 0.299f * nr + 0.587f * ng + 0.114f * nb
                
                if (grayscale) {
                    val gVal = gray.toInt().coerceIn(0, 255)
                    nr = gVal
                    ng = gVal
                    nb = gVal
                } else if (!magicColor) {
                    // For BLACK_WHITE (high contrast document clear for exam papers / handwritten questions)
                    // We perform thresholding or heavy contrast stretching to remove all paper grain
                    if (gray < 190f) {
                        val factor = (gray / 190f).toDouble().pow(1.5).toFloat()
                        val dark = (gray * factor).toInt().coerceIn(0, 255)
                        nr = dark
                        ng = dark
                        nb = dark
                    } else {
                        nr = 255
                        ng = 255
                        nb = 255
                    }
                } else {
                    // MAGIC_COLOR: preserve color of drawings/stamps, but flatten gray paper to pure white
                    if (gray < 220f) {
                        // Enhance contrast slightly
                        val factor = (gray / 220f).toDouble().pow(1.2).toFloat()
                        nr = (nr * factor).toInt().coerceIn(0, 255)
                        ng = (ng * factor).toInt().coerceIn(0, 255)
                        nb = (nb * factor).toInt().coerceIn(0, 255)
                    } else {
                        nr = 255
                        ng = 255
                        nb = 255
                    }
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
}
