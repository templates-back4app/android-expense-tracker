package com.back4app.expensetracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF1F3A5F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3F7),
    secondary = Color(0xFF4E6A8A),
    tertiary = Color(0xFF7A5A2F),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA9C7F0),
    onPrimary = Color(0xFF07213F),
    primaryContainer = Color(0xFF2B4769),
    secondary = Color(0xFFB6C8DD),
    tertiary = Color(0xFFE2C195),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
