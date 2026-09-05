package com.luckycatpaw.luckyfilestv.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Neutral dark surfaces plus a single accent.
 *
 * The surfaces carry no hue on purpose. A tinted background bleeds into every
 * thumbnail drawn on top of it, and tinted body text lowers the contrast the
 * viewer actually perceives from across the room. Colour is reserved for the
 * accent, which therefore always means "this is where you are".
 */

// Surfaces, from furthest back to closest to the viewer.
val AppBackground = Color(0xFF0A0A0C)
val AppSurface = Color(0xFF141417)
val AppSurfaceVariant = Color(0xFF1E1E23)

/** Focused/elevated surface. Mapped to `primaryContainer`. */
val AppElevated = Color(0xFF2B2B33)
val AppOnElevated = Color(0xFFF4F4F7)

// The accent. Swap these two lines to re-skin the app.
val AppPrimary = Color(0xFF7C6CFF)
val AppOnPrimary = Color(0xFF08080A)

val AppSecondary = Color(0xFFA79CFF)
val AppOnSecondary = Color(0xFF08080A)

val AppSecondaryContainer = Color(0xFF24242C)
val AppOnSecondaryContainer = Color(0xFFE6E4F5)

val AppTertiary = Color(0xFF4ADE9B)
val AppOnTertiary = Color(0xFF04140C)

val AppTertiaryContainer = Color(0xFF16281F)
val AppOnTertiaryContainer = Color(0xFFC6F3DD)

val AppError = Color(0xFFFF6B6B)
val AppOnError = Color(0xFF2A0708)

// Text.
val AppText = Color(0xFFF4F4F7)
val AppTextMuted = Color(0xFF93939E)

// Structural lines. Deliberately not the accent: a border says "edge",
// the accent says "focused", and the two need to stay distinguishable.
val AppBorder = Color(0xFF3A3A44)
val AppBorderMuted = Color(0xFF26262C)

val AppScrim = Color(0xFF000000)
