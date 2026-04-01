package com.android.systemui.common.networkmode

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.compose.theme.LocalAndroidColorScheme
import kotlin.math.roundToInt

@Composable
fun NetworkModeWidget(
    controller: NetworkModeController,
    modifier: Modifier = Modifier,
    border: Modifier = Modifier,
    tileHeight: Dp,
    iconOnly: Boolean = false,
) {

    val state by controller.state.collectAsState(initial = controller.state.value)

    if (iconOnly) {
        CompactNetworkModeWidget(
            controller = controller,
            state = state,
            modifier = modifier,
            border = border,
            tileHeight = tileHeight,
        )
    } else {
        SliderNetworkModeWidget(
            controller = controller,
            state = state,
            modifier = modifier,
            border = border,
            tileHeight = tileHeight,
        )
    }
}

@Composable
private fun CompactNetworkModeWidget(
    controller: NetworkModeController,
    state: NetworkModeTileState,
    modifier: Modifier,
    border: Modifier,
    tileHeight: Dp,
) {
    val simState = state.defaultDataSimState
    val currentMode = simState.displayModeOrCurrent()
    val containerColor =
        when {
            simState == null -> LocalAndroidColorScheme.current.surfaceEffect1
            simState.isFailed -> MaterialTheme.colorScheme.errorContainer
            currentMode == NetworkMode.MODE_3G -> LocalAndroidColorScheme.current.surfaceEffect1
            else -> MaterialTheme.colorScheme.primary
        }
    val contentColor =
        when {
            simState == null -> MaterialTheme.colorScheme.onSurfaceVariant
            simState.isFailed -> MaterialTheme.colorScheme.onErrorContainer
            currentMode == NetworkMode.MODE_3G -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onPrimary
        }
    val modeLabel =
        when {
            simState == null -> "--"
            simState.isLoading -> "..."
            else -> currentMode?.label ?: "--"
        }
    val view = LocalView.current

    Box(
        modifier =
            modifier
                .height(tileHeight)
                .clip(RoundedCornerShape(24.dp))
                .background(containerColor)
                .then(border)
                .pointerInput(simState?.subId, simState?.displayMode, simState?.isLoading) {
                    detectTapGestures(
                        onTap = {
                            if (controller.cycleModeForDefaultDataSim()) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        },
                        onLongPress = {
                            if (controller.switchDefaultDataSim()) {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            }
                        },
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        if (simState?.isLoading == true) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = contentColor,
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = modeLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    textAlign = TextAlign.Center,
                )
                if (simState != null && simState.carrierName.isNotBlank()) {
                    Text(
                        text = simState.carrierName,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderNetworkModeWidget(
    controller: NetworkModeController,
    state: NetworkModeTileState,
    modifier: Modifier,
    border: Modifier,
    tileHeight: Dp,
) {
    val simState = state.defaultDataSimState
    val availableModes =
        simState?.availableModes.orEmpty().ifEmpty { listOfNotNull(simState.displayModeOrCurrent()) }
    val currentMode = simState.displayModeOrCurrent()
    val maxOffset = (availableModes.size - 1).coerceAtLeast(1).toFloat()
    val targetPosition = modePosition(availableModes, currentMode)

    var dragOffset by remember(simState?.subId, availableModes) { mutableStateOf(targetPosition) }
    var isDragging by remember(simState?.subId) { mutableStateOf(false) }

    val animatedPosition by animateFloatAsState(
        targetValue = if (isDragging) dragOffset else targetPosition,
        animationSpec = tween(durationMillis = 250, easing = LinearOutSlowInEasing),
        label = "network_mode_position",
    )

    LaunchedEffect(targetPosition) {
        if (!isDragging) dragOffset = targetPosition
    }

    val view = LocalView.current

    Box(
        modifier =
            modifier
                .height(tileHeight)
                .clip(RoundedCornerShape(24.dp))
                .background(LocalAndroidColorScheme.current.surfaceEffect1)
                .then(border)
                .pointerInput(simState?.subId, availableModes, simState?.isLoading) {
                    detectTapGestures(
                        onTap = { tapOffset ->
                            val activeState = simState ?: return@detectTapGestures
                            if (activeState.isLoading || availableModes.size < 2) return@detectTapGestures

                            val sectionWidth = size.width / availableModes.size.toFloat()
                            val snappedIndex =
                                (tapOffset.x / sectionWidth).toInt().coerceIn(0, availableModes.lastIndex)

                            if (controller.requestMode(activeState.subId, availableModes[snappedIndex])) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        },
                        onLongPress = {
                            if (controller.switchDefaultDataSim()) {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            }
                        },
                    )
                }
                .pointerInput(simState?.subId, availableModes, simState?.isLoading) {
                    detectDragGestures(
                        onDragStart = {
                            isDragging = simState != null && !simState.isLoading && availableModes.size > 1
                            if (isDragging) {
                                dragOffset = targetPosition
                            }
                        },
                        onDragEnd = {
                            val activeState = simState
                            if (!isDragging || activeState == null || availableModes.isEmpty()) {
                                isDragging = false
                                return@detectDragGestures
                            }

                            isDragging = false
                            val snappedIndex = dragOffset.roundToInt().coerceIn(0, availableModes.size - 1)
                            if (controller.requestMode(activeState.subId, availableModes[snappedIndex])) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                        },
                    ) { change, dragAmount ->
                        if (!isDragging || simState == null || simState.isLoading || availableModes.size < 2) {
                            return@detectDragGestures
                        }

                        change.consume()
                        val trackWidth = size.width - tileHeight.toPx()
                        if (trackWidth <= 0f) return@detectDragGestures

                        val pixelPerUnit = trackWidth / maxOffset
                        if (pixelPerUnit <= 0f) return@detectDragGestures

                        dragOffset =
                            (dragOffset + (dragAmount.x / pixelPerUnit))
                                .coerceIn(0f, maxOffset)
                    }
                },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (simState != null && simState.carrierName.isNotBlank()) {
            Text(
                text = simState.carrierName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 14.dp, top = 6.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val currentIndex =
                if (availableModes.isEmpty()) -1
                else animatedPosition.roundToInt().coerceIn(0, availableModes.lastIndex)

            availableModes.forEachIndexed { index, _ ->
                Box(
                    modifier =
                        Modifier.size(6.dp)
                            .graphicsLayer {
                                alpha = if (currentIndex == index) 0f else 0.4f
                            }
                            .background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(50))
                )
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val thumbOffset =
                if (availableModes.size <= 1) {
                    (maxWidth - tileHeight) / 2f
                } else {
                    val step = (maxWidth - tileHeight) / (availableModes.size - 1)
                    step * animatedPosition
                }
            val thumbBackground =
                when {
                    simState == null -> LocalAndroidColorScheme.current.surfaceEffect2
                    simState.isFailed -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.primary
                }
            val thumbContent =
                when {
                    simState == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    simState.isFailed -> MaterialTheme.colorScheme.onErrorContainer
                    else -> MaterialTheme.colorScheme.onPrimary
                }
            val modeLabel = simState?.displayModeOrCurrent()?.label ?: "--"

            Box(
                modifier =
                    Modifier.offset(x = thumbOffset)
                        .size(tileHeight)
                        .padding(8.dp)
                        .background(thumbBackground, RoundedCornerShape(16.dp))
                        .border(2.dp, thumbBackground, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (simState?.isLoading == true) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = thumbContent,
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = modeLabel,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = thumbContent,
                        )
                        if (state.canSwitchDefaultDataSim && simState != null) {
                            Text(
                                text = simState.simLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = thumbContent.copy(alpha = 0.84f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun NetworkModeSimState?.displayModeOrCurrent(): NetworkMode? {
    return this?.displayMode ?: this?.currentMode
}

private fun modePosition(availableModes: List<NetworkMode>, mode: NetworkMode?): Float {
    if (availableModes.isEmpty()) return 0f
    val index = availableModes.indexOf(mode).takeIf { it >= 0 } ?: 0
    return index.toFloat()
}
