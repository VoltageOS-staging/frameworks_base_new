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

package com.android.systemui.volume.dialog.appvolume.ui.binder

import android.view.View
import androidx.compose.ui.platform.ComposeView
import com.android.compose.theme.PlatformTheme
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.appvolume.ui.viewmodel.VolumeDialogAppVolumeSliderViewModel
import com.android.systemui.volume.dialog.domain.interactor.DesktopAudioTileDetailsFeatureInteractor
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.sliders.ui.VolumeDialogSliderContent
import com.android.systemui.volume.dialog.sliders.ui.rememberVolumeDialogSliderColors
import com.android.systemui.volume.dialog.sliders.ui.rememberVolumeDialogSliderHaptics
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import javax.inject.Inject

@VolumeDialogScope
class VolumeDialogAppVolumeSliderViewBinder
@Inject
constructor(
    private val viewModel: VolumeDialogAppVolumeSliderViewModel,
    private val hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    desktopAudioTileDetailsFeatureInteractor: DesktopAudioTileDetailsFeatureInteractor,
) {
    private val isVolumeDialogVertical = !desktopAudioTileDetailsFeatureInteractor.isEnabled()

    fun bind(view: View) {
        val sliderComposeView: ComposeView = view.requireViewById(R.id.volume_dialog_slider)
        sliderComposeView.setContent {
            PlatformTheme {
                VolumeDialogSliderContent(
                    stateFlow = viewModel.state,
                    onValueChanged = { value -> viewModel.setVolume(value, true) },
                    onValueChangeFinished = viewModel::onSliderChangeFinished,
                    onSliderDragStarted = viewModel::onSliderDragStarted,
                    onSliderDragFinished = viewModel::onSliderDragFinished,
                    onTouchEvent = viewModel::onTouchEvent,
                    colors = rememberVolumeDialogSliderColors(),
                    haptics = rememberVolumeDialogSliderHaptics(hapticsViewModelFactory, isVolumeDialogVertical),
                    isVolumeDialogVertical = isVolumeDialogVertical,
                )
            }
        }
    }
}
