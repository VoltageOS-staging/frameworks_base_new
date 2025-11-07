/*
 * Copyright (C) 2025 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.common.slider

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.gestures.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.util.CustomAndroidColorScheme
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun LevelSliderWidget(
    interactor: LevelSliderInteractor,
    theme: LevelSliderTheme,
    dimens: LevelSliderDimens,
    modifier: Modifier = Modifier,
    isDozing: Boolean = false,
    border: Modifier = Modifier
) {
    val level by interactor.level
        .distinctUntilChanged { old, new ->
            (old * 100).toInt() == (new * 100).toInt()
        }
        .collectAsState(initial = interactor.getCurrentLevel())

    var dragLevel by remember { mutableFloatStateOf(level) }
    var isDragging by remember { mutableStateOf(false) }
    var isEnabled by remember { mutableStateOf(false) }

    val animatedLevel by animateFloatAsState(
        targetValue = if (isDragging) dragLevel else level,
        animationSpec = tween(200, easing = LinearOutSlowInEasing),
        label = "level_animation"
    )

    val density = LocalDensity.current

    // --- Color Definitions ---
    val colors = CustomAndroidColorScheme.current
    val activeTrackColor = if (isDozing) Color.Transparent else colors.primary
    val activeContentColor = if (isDozing) Color.White else colors.onPrimary
    val inactiveTrackColor = if (isDozing) Color.Transparent else colors.secondary
    val inactiveContentColor = if (isDozing) Color.Transparent else colors.onSurface
    
    // --- THE CORE FIX IS HERE ---
    // Define the two states for the progress bar's fill color
    val activeProgressFillColor = if (isDozing) Color.Transparent else colors.onPrimary.copy(alpha = 0.3f)
    val inactiveProgressFillColor = Color.Transparent

    // Animate the tile's main background color
    val animatedTrackColor by animateColorAsState(
        targetValue = if (isEnabled) activeTrackColor else inactiveTrackColor,
        animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing),
        label = "track_color_animation"
    )

    // Animate the icon and text color
    val animatedContentColor by animateColorAsState(
        targetValue = if (isEnabled) activeContentColor else inactiveContentColor,
        animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing),
        label = "content_color_animation"
    )

    // Animate the progress bar's color
    val animatedProgressFillColor by animateColorAsState(
        targetValue = if (isEnabled) activeProgressFillColor else inactiveProgressFillColor,
        animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing),
        label = "progress_fill_color_animation"
    )

    // Optional: Scale effect on enable
    val enabledScale by animateFloatAsState(
        targetValue = if (isEnabled) 1.05f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "enabled_scale"
    )

    LaunchedEffect(level) {
        if (!isDragging) dragLevel = level
    }

    BoxWithConstraints(
        modifier = modifier
            .height(dimens.height)
            .scale(enabledScale)
            .clip(CircleShape)
            .then(if (isDozing) Modifier.border(theme.dozeStroke, Color.White, CircleShape) else border)
            .pointerInput(Unit) {
                detectTapGestures {
                    isEnabled = !isEnabled
                    (interactor as? TapHandlingInteractor)?.onTap(isEnabled)
                }
            }
            .pointerInput(isEnabled) {
                if (isEnabled) {
                    detectHorizontalDragGestures(
                        onDragStart = { isDragging = true },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false }
                    ) { change, dragAmount ->
                        change.consume()
                        val delta = dragAmount / size.width
                        dragLevel = (dragLevel + delta).coerceIn(0f, 1f)
                        interactor.setLevel(dragLevel)
                    }
                }
            }
    ) {
        val boxWidthPx = with(density) { maxWidth.toPx() }
        val fillWidth = boxWidthPx * animatedLevel

        // Background of the entire track
        Box(Modifier.fillMaxSize().background(animatedTrackColor, CircleShape).clip(CircleShape))

        // The progress indicator bar, now using the animated color
        Canvas(Modifier.fillMaxSize().clip(CircleShape)) {
            drawRoundRect(
                color = animatedProgressFillColor,
                size = Size(fillWidth, size.height),
                cornerRadius = CornerRadius(size.height / 2)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = dimens.horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.labelPadding)
        ) {
            val icon = interactor.getIcon(animatedLevel)
            val label = interactor.getLabel(animatedLevel)

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = animatedContentColor,
                modifier = Modifier.size(dimens.iconSize)
            )

            Text(
                text = label,
                color = animatedContentColor,
                fontSize = 14.sp,
                fontWeight = if (isDragging) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(iterations = 1, initialDelayMillis = 2000)
            )
        }
    }
}

interface TapHandlingInteractor {
    fun onTap(enabled: Boolean)
}
