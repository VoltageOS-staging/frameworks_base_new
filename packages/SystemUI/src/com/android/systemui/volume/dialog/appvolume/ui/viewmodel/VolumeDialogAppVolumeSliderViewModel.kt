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

package com.android.systemui.volume.dialog.appvolume.ui.viewmodel

import androidx.compose.ui.input.pointer.PointerEvent
import com.android.systemui.util.time.SystemClock
import com.android.systemui.volume.dialog.appvolume.domain.VolumeDialogAppVolumeInteractor
import com.android.systemui.volume.dialog.appvolume.shared.model.VolumeDialogAppVolumeModel
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialog
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogSliderStateModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

private const val APP_VOLUME_UPDATE_GRACE_PERIOD = 1000
private const val APP_VOLUME_MIN = 0f
private const val APP_VOLUME_MAX = 100f

@VolumeDialogScope
class VolumeDialogAppVolumeSliderViewModel
@Inject
constructor(
    private val interactor: VolumeDialogAppVolumeInteractor,
    @VolumeDialog coroutineScope: CoroutineScope,
    private val systemClock: SystemClock,
) {
    private val userVolumeUpdates = MutableStateFlow<VolumeUpdate?>(null)

    val state: StateFlow<VolumeDialogSliderStateModel?> =
        combine(interactor.state, userVolumeUpdates) { appVolumeState, currentVolumeUpdate ->
                appVolumeState.selectedApp
                    ?.takeIf { appVolumeState.isExpanded }
                    ?.toSliderState(currentVolumeUpdate, getTimestampMillis())
            }
            .stateIn(coroutineScope, SharingStarted.Eagerly, null)

    fun setVolume(volume: Float, fromUser: Boolean) {
        if (fromUser) {
            val packageName = interactor.state.value.selectedApp?.packageName ?: return
            userVolumeUpdates.value =
                VolumeUpdate(
                    packageName = packageName,
                    volume = volume.coerceIn(APP_VOLUME_MIN, APP_VOLUME_MAX),
                    timestampMillis = getTimestampMillis(),
                )
            interactor.onSliderChanged(userVolumeUpdates.value!!.volume)
        }
    }

    fun onSliderDragStarted() = Unit

    fun onSliderDragFinished() = Unit

    fun onSliderChangeFinished(volume: Float) = Unit

    fun onTouchEvent(pointerEvent: PointerEvent) = Unit

    private fun VolumeDialogAppVolumeModel.toSliderState(
        currentVolumeUpdate: VolumeUpdate?,
        timestampMillis: Long,
    ): VolumeDialogSliderStateModel {
        val isInGracePeriod =
            currentVolumeUpdate != null &&
                currentVolumeUpdate.packageName == packageName &&
                timestampMillis - currentVolumeUpdate.timestampMillis < APP_VOLUME_UPDATE_GRACE_PERIOD
        val sliderValue =
            if (isInGracePeriod) {
                currentVolumeUpdate.volume
            } else {
                volumePercent
            }
        return VolumeDialogSliderStateModel(
            value = sliderValue,
            isDisabled = false,
            valueRange = APP_VOLUME_MIN..APP_VOLUME_MAX,
            icon = icon,
            label = label,
        )
    }

    private fun getTimestampMillis(): Long = systemClock.uptimeMillis()

    private data class VolumeUpdate(
        val packageName: String,
        val volume: Float,
        val timestampMillis: Long,
    )
}
