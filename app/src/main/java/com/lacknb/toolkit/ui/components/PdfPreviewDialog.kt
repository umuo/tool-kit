package com.lacknb.toolkit.ui.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPreviewDialog(
    bitmaps: List<Bitmap>,
    defaultFileName: String = "Document_${System.currentTimeMillis()}",
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isProcessing by remember { mutableStateOf(false) }
    var savedToPhone by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(text = "PDF 文档预览", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "关闭")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF1E1E1E),
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White
                    )
                )
            },
            bottomBar = {
                BottomAppBar(
                    containerColor = Color(0xFF1E1E1E),
                    contentColor = Color.White,
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 微信分享
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = !isProcessing) {
                                    isProcessing = true
                                    coroutineScope.launch {
                                        val uri = generatePdfToCache(context, bitmaps, defaultFileName)
                                        if (uri != null) {
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "application/pdf"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(intent, "分享PDF文件"))
                                        } else {
                                            Toast.makeText(context, "生成PDF失败", Toast.LENGTH_SHORT).show()
                                        }
                                        isProcessing = false
                                    }
                                }
                                .padding(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = "微信分享", tint = Color(0xFF4CAF50))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("微信分享", fontSize = 12.sp, color = Color.LightGray)
                        }

                        // 保存到手机
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = !isProcessing) {
                                    isProcessing = true
                                    coroutineScope.launch {
                                        val uri = savePdfToMediaStore(context, bitmaps, defaultFileName)
                                        if (uri != null) {
                                            savedToPhone = true
                                            Toast.makeText(context, "已成功保存到文件管理器 Documents/ToolkitScanner", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                                        }
                                        isProcessing = false
                                    }
                                }
                                .padding(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SaveAlt,
                                contentDescription = "保存",
                                tint = if (savedToPhone) Color.Gray else Color(0xFF03A9F4)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(if (savedToPhone) "已保存" else "保存到手机", fontSize = 12.sp, color = Color.LightGray)
                        }

                        // 直接打印
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = !isProcessing) {
                                    isProcessing = true
                                    coroutineScope.launch {
                                        val uri = generatePdfToCache(context, bitmaps, defaultFileName)
                                        if (uri != null) {
                                            printPdf(context, uri, defaultFileName)
                                        } else {
                                            Toast.makeText(context, "生成PDF失败", Toast.LENGTH_SHORT).show()
                                        }
                                        isProcessing = false
                                    }
                                }
                                .padding(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Print, contentDescription = "打印", tint = Color(0xFFFF9800))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("直接打印", fontSize = 12.sp, color = Color.LightGray)
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF121212))
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    itemsIndexed(bitmaps) { index, bmp ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Card(
                                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White)
                            ) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Page ${index + 1}",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "- ${index + 1} -",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                if (isProcessing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color(0xFF03A9F4))
                    }
                }
            }
        }
    }
}

private suspend fun generatePdfToCache(context: Context, bitmaps: List<Bitmap>, fileName: String): Uri? {
    return withContext(Dispatchers.IO) {
        try {
            val pdfDocument = android.graphics.pdf.PdfDocument()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            for (i in bitmaps.indices) {
                val bitmap = bitmaps[i]
                val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, i + 1).create()
                val page = pdfDocument.startPage(pageInfo)

                val canvas = page.canvas
                val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                val dstRect = Rect(0, 0, 595, 842)
                canvas.drawBitmap(bitmap, srcRect, dstRect, paint)

                pdfDocument.finishPage(page)
            }

            // Save to cache directory
            val file = File(context.cacheDir, "$fileName.pdf")
            FileOutputStream(file).use { out ->
                pdfDocument.writeTo(out)
            }
            pdfDocument.close()

            // Return FileProvider URI
            FileProvider.getUriForFile(context, "com.lacknb.toolkit.fileprovider", file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

private suspend fun savePdfToMediaStore(context: Context, bitmaps: List<Bitmap>, fileName: String): Uri? {
    return withContext(Dispatchers.IO) {
        try {
            val pdfDocument = android.graphics.pdf.PdfDocument()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            for (i in bitmaps.indices) {
                val bitmap = bitmaps[i]
                val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, i + 1).create()
                val page = pdfDocument.startPage(pageInfo)

                val canvas = page.canvas
                val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                val dstRect = Rect(0, 0, 595, 842)
                canvas.drawBitmap(bitmap, srcRect, dstRect, paint)

                pdfDocument.finishPage(page)
            }

            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.pdf")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOCUMENTS + "/ToolkitScanner")
            }
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    pdfDocument.writeTo(out)
                }
            }
            pdfDocument.close()
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

private fun printPdf(context: Context, uri: Uri, fileName: String) {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    val printAdapter = object : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback?,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback?.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder("$fileName.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build()
            callback?.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor?,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback?
        ) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destination?.fileDescriptor).use { output ->
                        input.copyTo(output)
                    }
                }
                if (cancellationSignal?.isCanceled == true) {
                    callback?.onWriteCancelled()
                } else {
                    callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                }
            } catch (e: Exception) {
                callback?.onWriteFailed(e.message)
            }
        }
    }
    printManager.print("${fileName}_Print", printAdapter, null)
}
