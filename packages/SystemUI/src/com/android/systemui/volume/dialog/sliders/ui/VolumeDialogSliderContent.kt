/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.volume.dialog.sliders.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.haptics.slider.SliderHapticFeedbackFilter
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.volume.dialog.sliders.ui.compose.SliderTrack
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogSliderStateModel
import com.android.systemui.volume.haptics.ui.VolumeHapticsConfigsProvider
import com.android.systemui.volume.ui.compose.slider.AccessibilityParams
import com.android.systemui.volume.ui.compose.slider.Haptics
import com.android.systemui.volume.ui.compose.slider.Slider
import com.android.systemui.volume.ui.compose.slider.SliderIcon
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun rememberVolumeDialogSliderColors(): SliderColors =
    SliderDefaults.colors(
        activeTickColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        disabledActiveTickColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )

@Composable
fun rememberVolumeDialogSliderHaptics(
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    isVolumeDialogVertical: Boolean,
): Haptics =
    Haptics.Enabled(
        hapticsViewModelFactory = hapticsViewModelFactory,
        hapticConfigs =
            VolumeHapticsConfigsProvider.continuousConfigs(SliderHapticFeedbackFilter()),
        orientation =
            if (isVolumeDialogVertical) {
                Orientation.Vertical
            } else {
                Orientation.Horizontal
            },
    )

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VolumeDialogSliderContent(
    stateFlow: Flow<VolumeDialogSliderStateModel?>,
    onValueChanged: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit,
    onSliderDragStarted: () -> Unit,
    onSliderDragFinished: () -> Unit,
    onTouchEvent: (PointerEvent) -> Unit,
    colors: SliderColors,
    haptics: Haptics,
    isVolumeDialogVertical: Boolean,
    showTrackIcon: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val collectedSliderStateModel by stateFlow.collectAsStateWithLifecycle(null)
    val sliderStateModel = collectedSliderStateModel ?: return
    val interactionSource = remember { MutableInteractionSource() }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect {
            when (it) {
                is DragInteraction.Start -> onSliderDragStarted()
                is DragInteraction.Cancel -> onSliderDragFinished()
                is DragInteraction.Stop -> onSliderDragFinished()
            }
        }
    }

    Slider(
        value = sliderStateModel.value,
        valueRange = sliderStateModel.valueRange,
        onValueChanged = onValueChanged,
        onValueChangeFinished = { onValueChangeFinished(it) },
        isEnabled = !sliderStateModel.isDisabled,
        isReverseDirection = true,
        isVertical = isVolumeDialogVertical,
        colors = colors,
        interactionSource = interactionSource,
        haptics = haptics,
        stepDistance = 1f,
        track = { sliderState ->
            val trackIcon = sliderStateModel.icon.takeIf { showTrackIcon }
            SliderTrack(
                sliderState,
                colors = colors,
                isEnabled = !sliderStateModel.isDisabled,
                isVertical = isVolumeDialogVertical,
                activeTrackEndIcon =
                    trackIcon?.let { icon ->
                        { iconsState ->
                            SliderIcon(
                                icon = {
                                    Icon(
                                        icon = icon,
                                        tint = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                isVisible = !iconsState.isInactiveTrackEndIconVisible,
                            )
                        }
                    },
                inactiveTrackEndIcon =
                    trackIcon?.let { icon ->
                        { iconsState ->
                            SliderIcon(
                                icon = {
                                    Icon(
                                        icon = icon,
                                        tint = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                isVisible = iconsState.isInactiveTrackEndIconVisible,
                            )
                        }
                    },
            )
        },
        thumb = { sliderState, interactions ->
            SliderDefaults.Thumb(
                sliderState = sliderState,
                interactionSource = interactions,
                enabled = !sliderStateModel.isDisabled,
                colors = colors,
                thumbSize =
                    if (isVolumeDialogVertical) {
                        DpSize(52.dp, 4.dp)
                    } else {
                        DpSize(4.dp, 52.dp)
                    },
            )
        },
        accessibilityParams = AccessibilityParams(contentDescription = sliderStateModel.label),
        modifier =
            modifier.pointerInput(Unit) {
                coroutineScope {
                    val currentContext = currentCoroutineContext()
                    awaitPointerEventScope {
                        while (currentContext.isActive) {
                            onTouchEvent(awaitPointerEvent())
                        }
                    }
                }
            },
    )
}
