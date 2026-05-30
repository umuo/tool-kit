package com.lacknb.toolkit.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lacknb.toolkit.core.Tool
import com.lacknb.toolkit.core.ToolCategory
import com.lacknb.toolkit.core.ToolRegistry
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onToolClick: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(ToolCategory.IMAGE_SCANNING) }
    val coroutineScope = rememberCoroutineScope()

    // Filter tools based on search query or selected category
    val filteredTools = remember(searchQuery, selectedCategory) {
        if (searchQuery.isNotBlank()) {
            ToolRegistry.searchTools(searchQuery)
        } else {
            ToolRegistry.getToolsByCategory(selectedCategory)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF1E1E1E), Color(0xFF121212))
                    )
                )
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // Dashboard Title / App Bar Area
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "极致工具箱",
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 24.sp,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "万能轻量底座 • 极致交互体验",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF2E2E2E)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "特色",
                        tint = Color(0xFFFFD700),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Glassmorphic Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 8.dp, shape = RoundedCornerShape(14.dp)),
                placeholder = {
                    Text(
                        text = "搜索全局工具，如：OCR、笔迹擦除...",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "搜索图标",
                        tint = Color.LightGray
                    )
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF3F51B5),
                    unfocusedBorderColor = Color(0xFF2E2E2E),
                    focusedContainerColor = Color(0xFF1E1E1E),
                    unfocusedContainerColor = Color(0xFF1E1E1E),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(14.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Only show category slider if not actively searching
            if (searchQuery.isBlank()) {
                val categories = ToolCategory.values()
                val pagerState = rememberPagerState(
                    initialPage = categories.indexOf(selectedCategory).coerceAtLeast(0),
                    pageCount = { categories.size }
                )

                LaunchedEffect(pagerState.currentPage) {
                    val newCategory = categories[pagerState.currentPage]
                    if (selectedCategory != newCategory) {
                        selectedCategory = newCategory
                    }
                }

                Text(
                    text = "分类浏览",
                    color = Color.LightGray,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Category selector row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEachIndexed { index, category ->
                        val isSelected = selectedCategory == category
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(index)
                                    }
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) Color(0xFF3F51B5).copy(alpha = 0.2f) else Color(0xFF1E1E1E)
                            ),
                            border = if (isSelected) {
                                BorderStroke(1.5.dp, Color(0xFF3F51B5))
                            } else {
                                BorderStroke(1.dp, Color(0xFF2E2E2E))
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) Color(0xFF3F51B5) else Color(0xFF2E2E2E)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = category.icon,
                                        contentDescription = null,
                                        tint = if (isSelected) Color.White else Color.Gray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = category.displayName,
                                    color = if (isSelected) Color.White else Color.LightGray,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { page ->
                    val pageCategory = categories[page]
                    val catTools = ToolRegistry.getToolsByCategory(pageCategory)
                    
                    if (catTools.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "暂无相关工具",
                                    color = Color.Gray,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "敬请期待功能更新",
                                    color = Color.DarkGray,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 20.dp)
                        ) {
                            items(catTools) { tool ->
                                ToolCardItem(tool, onToolClick)
                            }
                        }
                    }
                }

            } else {
                // Show search header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "搜索结果 (${filteredTools.size} 个工具)",
                        color = Color.LightGray,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "清除",
                        color = Color(0xFF3F51B5),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { searchQuery = "" }
                    )
                }

                // Grid of Tools
                if (filteredTools.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "没有找到符合条件的工具",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "换个关键词搜一搜吧",
                                color = Color.DarkGray,
                                fontSize = 12.sp
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 20.dp)
                    ) {
                        items(filteredTools) { tool ->
                            ToolCardItem(tool, onToolClick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCardItem(tool: Tool, onToolClick: (String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clickable { onToolClick(tool.id) }
            .shadow(elevation = 4.dp, shape = RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        border = BorderStroke(1.dp, Color(0xFF2E2E2E)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Tool Icon with colorful circular background
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(
                            colors = getToolColors(tool.id)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = tool.icon,
                    contentDescription = tool.name,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column {
                Text(
                    text = tool.name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = tool.description,
                    color = Color.Gray,
                    fontSize = 10.sp,
                    maxLines = 2,
                    lineHeight = 13.sp
                )
            }
        }
    }
}

// Helper to assign a dynamic gradient to tool icons
private fun getToolColors(toolId: String): List<Color> {
    return when (toolId) {
        "image_to_text" -> listOf(Color(0xFF3F51B5), Color(0xFF00BCD4))
        "photo_to_electronic_doc" -> listOf(Color(0xFFFF5722), Color(0xFFFF9800))
        "exam_paper_eraser" -> listOf(Color(0xFF00E676), Color(0xFF00B0FF))
        else -> listOf(Color(0xFF757575), Color(0xFFBDBDBD))
    }
}
