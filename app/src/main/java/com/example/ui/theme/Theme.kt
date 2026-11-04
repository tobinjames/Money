package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.graphics.Color

private val DarkColorScheme =
  darkColorScheme(
    primary = Indigo100,
    onPrimary = Slate900,
    primaryContainer = Indigo700,
    onPrimaryContainer = Indigo50,
    secondary = Slate300,
    onSecondary = Slate900,
    secondaryContainer = Slate800,
    onSecondaryContainer = Slate100,
    background = Slate900,
    onBackground = Color(0xFFF8FAFC),
    surface = Slate800,
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Slate700,
    onSurfaceVariant = Slate300,
    outline = Slate500,
    outlineVariant = Slate700,
    error = ErrorRed
  )

private val LightColorScheme =
  lightColorScheme(
    primary = Indigo600,
    onPrimary = Color.White,
    primaryContainer = Indigo50,
    onPrimaryContainer = Indigo800,
    secondary = Slate700,
    onSecondary = Color.White,
    secondaryContainer = Slate100,
    onSecondaryContainer = Slate900,
    background = BackgroundF8,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate700,
    outline = Slate400,
    outlineVariant = Slate200,
    error = ErrorRed
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is disabled by default to show our clean minimalist brand theme
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
