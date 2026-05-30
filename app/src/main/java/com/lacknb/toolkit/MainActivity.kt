package com.lacknb.toolkit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.activity.compose.BackHandler
import com.lacknb.toolkit.core.ToolRegistry
import com.lacknb.toolkit.ui.screens.MainScreen
import com.lacknb.toolkit.ui.theme.ToolkitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ToolkitTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var activeToolId by remember { mutableStateOf<String?>(null) }

                    if (activeToolId == null) {
                        // Render Dashboard
                        MainScreen(
                            onToolClick = { toolId ->
                                activeToolId = toolId
                            }
                        )
                    } else {
                        // Intercept system back press to return to dashboard
                        BackHandler {
                            activeToolId = null
                        }

                        // Find and render the active tool
                        val activeTool = ToolRegistry.getToolById(activeToolId!!)
                        if (activeTool != null) {
                            activeTool.Content(
                                onBack = {
                                    activeToolId = null
                                }
                            )
                        } else {
                            // Fallback in case of registration error
                            activeToolId = null
                        }
                    }
                }
            }
        }
    }
}