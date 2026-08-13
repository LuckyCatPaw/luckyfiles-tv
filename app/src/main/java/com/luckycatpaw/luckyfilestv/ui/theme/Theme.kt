package com.luckycatpaw.luckyfilestv.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val FileManagerColorScheme = darkColorScheme(
    primary = AppPrimary,
    onPrimary = AppOnPrimary,

    primaryContainer = AppPrimaryContainer,
    onPrimaryContainer = AppOnPrimaryContainer,

    secondary = AppSecondary,
    onSecondary = AppOnSecondary,

    secondaryContainer = AppSecondaryContainer,
    onSecondaryContainer = AppOnSecondaryContainer,

    tertiary = AppTertiary,
    onTertiary = AppOnTertiary,

    tertiaryContainer = AppTertiaryContainer,
    onTertiaryContainer = AppOnTertiaryContainer,

    background = AppBackground,
    onBackground = AppText,

    surface = AppSurface,
    onSurface = AppText,

    surfaceVariant = AppSurfaceVariant,
    onSurfaceVariant = AppTextMuted,

    border = AppBorder,
    borderVariant = AppBorderMuted,

    scrim = AppBackground
)

@Composable
fun LuckyFilesTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = FileManagerColorScheme,
        content = content
    )
}
