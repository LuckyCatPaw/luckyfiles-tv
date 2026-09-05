package com.luckycatpaw.luckyfilestv.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
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
fun LuckyFilesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FileManagerColorScheme,
        content = content
    )
}

/**
 * The root every screen sits in: the theme, a background surface and a box that fills it.
 *
 * The box is part of it rather than left to the caller because both screens need one for the
 * same reason — an overlay is drawn on top of the content, not beside it, and that only
 * works inside a stacking container. Written out per screen, the three levels were already
 * one indentation apart between the browser and the picker.
 */
@Composable
internal fun AppScreenScaffold(content: @Composable BoxScope.() -> Unit) {
    LuckyFilesTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            colors = SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                content = content
            )
        }
    }
}
