package com.android.systemui.common.networkmode

import android.telephony.SubscriptionManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.compose.theme.LocalAndroidColorScheme
import kotlin.math.abs

@Composable
fun NetworkModeWidget(
    controller: NetworkModeController,
    modifier: Modifier = Modifier,
    border: Modifier = Modifier,
) {
    DisposableEffect(controller) {
        controller.acquireListening()
        onDispose { controller.releaseListening() }
    }

    val state by controller.state.collectAsState(initial = controller.state.value)
    val dragOffsets = remember { mutableStateMapOf<Int, Float>() }
    val view = LocalView.current

    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(24.dp))
                .background(LocalAndroidColorScheme.current.surfaceEffect1)
                .then(border)
                .pointerInput(state.simStates) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val targetSubId =
                            resolveTargetSubId(
                                state = state,
                                touchY = down.position.y,
                                heightPx = size.height.toFloat(),
                            )
                        if (targetSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return@awaitEachGesture

                        controller.setFocusedSubId(targetSubId)

                        var totalDx = 0f
                        var totalDy = 0f
                        var lockHorizontal: Boolean? = null
                        var pointer = down
                        val touchSlop = viewConfiguration.touchSlop

                        while (pointer.pressed) {
                            val event = awaitPointerEvent()
                            val change =
                                event.changes.firstOrNull { it.id == pointer.id }
                                    ?: event.changes.firstOrNull()
                                    ?: break
                            val delta = change.position - change.previousPosition
                            totalDx += delta.x
                            totalDy += delta.y
                            pointer = change

                            if (lockHorizontal == null) {
                                if (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop) {
                                    lockHorizontal = abs(totalDx) > abs(totalDy)
                                }
                            }

                            if (lockHorizontal == true) {
                                change.consume()
                                dragOffsets[targetSubId] = totalDx
                            }

                            if (!change.pressed) {
                                break
                            }
                        }

                        if (lockHorizontal == true) {
                            val threshold = size.width * 0.24f
                            val direction =
                                when {
                                    totalDx <= -threshold -> 1
                                    totalDx >= threshold -> -1
                                    else -> 0
                                }
                            if (direction != 0 && controller.requestAdjacentMode(targetSubId, direction)) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }

                        dragOffsets[targetSubId] = 0f
                    }
                }
                .padding(6.dp)
    ) {
        if (state.simStates.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Unavailable",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Box
        }

        val dualSim = state.simStates.size > 1
        if (dualSim) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.simStates.forEachIndexed { index, simState ->
                    SimPane(
                        simState = simState,
                        dragOffsetPx = dragOffsets[simState.subId] ?: 0f,
                        modifier = Modifier.weight(1f),
                    )
                    if (index == 0) {
                        Spacer(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        )
                    }
                }
            }
        } else {
            SimPane(
                simState = state.simStates.first(),
                dragOffsetPx = dragOffsets[state.simStates.first().subId] ?: 0f,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SimPane(
    simState: NetworkModeSimState,
    dragOffsetPx: Float,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    val paneBackground =
        if (simState.isFocused) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            Color.Transparent
        }
    val borderColor =
        when {
            simState.isFocused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
            simState.isDefaultData -> MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        }

    val previewDirection =
        when {
            dragOffsetPx < -8f -> 1
            dragOffsetPx > 8f -> -1
            else -> 0
        }
    val previewMode =
        if (previewDirection == 0) {
            null
        } else {
            adjacentMode(simState.availableModes, simState.displayMode, previewDirection)
        }

    Box(
        modifier =
            modifier
                .clip(shape)
                .background(paneBackground)
                .border(width = 1.dp, color = borderColor, shape = shape)
                .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = simState.simLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (simState.isDefaultData) {
                        Spacer(modifier = Modifier.size(4.dp))
                        Box(
                            modifier =
                                Modifier.size(6.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                when {
                    simState.isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.6.dp,
                        )
                    }
                    simState.isFailed -> {
                        Text(
                            text = "Failed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            BoxWithConstraints(
                modifier =
                    Modifier.fillMaxWidth()
                        .weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val modeText = simState.displayMode?.label ?: "--"

                if (previewMode != null) {
                    Text(
                        text = modeText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier =
                            Modifier.graphicsLayer {
                                translationX = dragOffsetPx
                            }
                    )
                    Text(
                        text = previewMode.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.95f),
                        modifier =
                            Modifier.graphicsLayer {
                                translationX =
                                    if (dragOffsetPx < 0f) {
                                        widthPx + dragOffsetPx
                                    } else {
                                        -widthPx + dragOffsetPx
                                    }
                            }
                    )
                } else {
                    AnimatedContent(
                        targetState = modeText,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "mode_transition",
                    ) { mode ->
                        Text(
                            text = mode,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Text(
                text =
                    when {
                        simState.isLoading -> "Applying..."
                        simState.carrierName.isBlank() -> "Unavailable"
                        else -> simState.carrierName
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun adjacentMode(
    availableModes: List<NetworkMode>,
    displayMode: NetworkMode?,
    direction: Int,
): NetworkMode? {
    if (availableModes.isEmpty()) return null
    val currentIndex = availableModes.indexOf(displayMode).takeIf { it >= 0 } ?: 0
    val delta = if (direction >= 0) 1 else -1
    val nextIndex = ((currentIndex + delta) % availableModes.size + availableModes.size) % availableModes.size
    return availableModes[nextIndex]
}

private fun resolveTargetSubId(
    state: NetworkModeTileState,
    touchY: Float,
    heightPx: Float,
): Int {
    if (state.simStates.isEmpty()) return SubscriptionManager.INVALID_SUBSCRIPTION_ID
    if (state.simStates.size == 1) return state.simStates.first().subId

    val topThreshold = heightPx * 0.45f
    val bottomThreshold = heightPx * 0.55f
    return when {
        touchY <= topThreshold -> state.simStates.first().subId
        touchY >= bottomThreshold -> state.simStates.last().subId
        else ->
            state.focusedSubId.takeIf { focused -> state.simStates.any { it.subId == focused } }
                ?: state.simStates.first().subId
    }
}
