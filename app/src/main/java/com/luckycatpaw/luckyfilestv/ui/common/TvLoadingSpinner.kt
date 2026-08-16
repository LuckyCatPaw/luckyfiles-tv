package com.luckycatpaw.luckyfilestv.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme

@Composable
internal fun TvLoadingSpinner(label: String, modifier: Modifier = Modifier, size: Dp = 38.dp) {
    val transition = rememberInfiniteTransition(label = label)
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(850, easing = LinearEasing)),
        label = "$label-rotation"
    )
    val color = MaterialTheme.colorScheme.primary

    Canvas(modifier.size(size)) {
        drawArc(
            color = color,
            startAngle = rotation,
            sweepAngle = 275f,
            useCenter = false,
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}
