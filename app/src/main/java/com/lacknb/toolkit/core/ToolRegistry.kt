package com.lacknb.toolkit.core

import com.lacknb.toolkit.features.image.ImageToTextTool
import com.lacknb.toolkit.features.image.PhotoToElectronicDocTool
import com.lacknb.toolkit.features.image.ExamPaperEraserTool
import com.lacknb.toolkit.features.system.AboutAppTool

object ToolRegistry {
    val allTools: List<Tool> by lazy {
        listOf(
            ImageToTextTool(),
            PhotoToElectronicDocTool(),
            ExamPaperEraserTool(),
            AboutAppTool()
        )
    }

    fun getToolsByCategory(category: ToolCategory): List<Tool> {
        return allTools.filter { it.category == category }
    }

    fun getToolById(id: String): Tool? {
        return allTools.find { it.id == id }
    }

    fun searchTools(query: String): List<Tool> {
        if (query.isBlank()) return allTools
        val trimmed = query.trim().lowercase()
        return allTools.filter {
            it.name.lowercase().contains(trimmed) ||
                    it.description.lowercase().contains(trimmed) ||
                    it.category.displayName.lowercase().contains(trimmed)
        }
    }
}
