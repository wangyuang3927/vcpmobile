package com.vcp.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = VcpPrimary,
    onPrimary = VcpOnPrimary,
    primaryContainer = VcpPrimaryContainer,
    onPrimaryContainer = VcpOnPrimaryContainer,
    secondary = VcpSecondary,
    onSecondary = VcpOnSecondary,
    secondaryContainer = VcpSecondaryContainer,
    onSecondaryContainer = VcpOnSecondaryContainer,
)

private val DarkColorScheme = darkColorScheme(
    primary = VcpPrimaryContainer,
    onPrimary = VcpOnPrimaryContainer,
    primaryContainer = VcpPrimary,
    onPrimaryContainer = VcpOnPrimary,
    secondary = VcpSecondaryContainer,
    onSecondary = VcpOnSecondaryContainer,
    secondaryContainer = VcpSecondary,
    onSecondaryContainer = VcpOnSecondary,
)

@Composable
fun VcpMobileTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
