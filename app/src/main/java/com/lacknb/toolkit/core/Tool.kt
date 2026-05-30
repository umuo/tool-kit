package com.lacknb.toolkit.core

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

interface Tool {
    val id: String
    val name: String
    val icon: ImageVector
    val description: String
    val category: ToolCategory

    @Composable
    fun Content(onBack: () -> Unit)
}
