package com.lacknb.toolkit.core

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

enum class ToolCategory(
    val id: String,
    val displayName: String,
    val description: String,
    val icon: ImageVector
) {
    IMAGE_SCANNING(
        id = "image_scanning",
        displayName = "图像扫描",
        description = "图片文字提取、手写笔迹擦除、高保真电子文档生成",
        icon = Icons.Default.Image
    ),
    UTILITIES(
        id = "utilities",
        displayName = "日常工具",
        description = "日常便利计算、文本转换、便捷助手",
        icon = Icons.Default.Work
    ),
    SYSTEM(
        id = "system",
        displayName = "系统检测",
        description = "设备参数、网络环境与性能监测",
        icon = Icons.Default.Settings
    )
}
