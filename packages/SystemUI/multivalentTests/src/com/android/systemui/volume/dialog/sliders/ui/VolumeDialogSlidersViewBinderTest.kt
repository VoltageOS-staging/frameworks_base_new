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

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.plugins.fakeVolumeDialogController
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.verify
import com.android.systemui.volume.dialog.appvolume.data.repository.volumeDialogAppVolumeRepository
import com.android.systemui.volume.dialog.appvolume.shared.model.VolumeDialogAppVolumeModel
import com.android.systemui.volume.dialog.appvolume.ui.binder.VolumeDialogAppVolumeSliderViewBinder
import com.android.systemui.volume.dialog.appvolume.ui.viewmodel.volumeDialogAppVolumeSliderViewModel
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.volumeDialogSlidersViewModel
import com.android.systemui.volume.dialog.ui.viewmodel.volumeDialogViewModel
import com.android.systemui.res.R
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class VolumeDialogSlidersViewBinderTest : SysuiTestCase() {

    private val kosmos = testKosmos().useUnconfinedTestDispatcher()

    @Test
    fun appSliderBound_asLastFloatingChild() =
        kosmos.runTest {
            fakeVolumeDialogController.updateState {
                activeStream = android.media.AudioManager.STREAM_SYSTEM
                states.put(android.media.AudioManager.STREAM_SYSTEM, buildStreamState())
                states.put(android.media.AudioManager.STREAM_MUSIC, buildStreamState())
            }
            volumeDialogAppVolumeRepository.updateState {
                it.copy(
                    selectedApp =
                        VolumeDialogAppVolumeModel(
                            packageName = "app.pkg",
                            label = "App",
                            volumePercent = 30f,
                            isMuted = false,
                            icon =
                                Icon.Loaded(
                                    ColorDrawable(Color.RED),
                                    ContentDescription.Loaded("App"),
                                ),
                        ),
                    isExpanded = true,
                )
            }

            val appSliderBinder = mock<VolumeDialogAppVolumeSliderViewBinder>()
            val underTest =
                VolumeDialogSlidersViewBinder(
                    volumeDialogSlidersViewModel,
                    volumeDialogViewModel,
                    volumeDialogAppVolumeSliderViewModel,
                    appSliderBinder,
                )
            val root = LayoutInflater.from(context).inflate(R.layout.volume_dialog, null, false)
            val floatingContainer =
                root.requireViewById<LinearLayout>(R.id.volume_dialog_floating_sliders_container)

            with(underTest) { applicationCoroutineScope.bind(root) }
            runCurrent()

            verify(appSliderBinder)
                .bind(eq(floatingContainer.getChildAt(floatingContainer.childCount - 1)))
        }

    private fun buildStreamState(
        build: com.android.systemui.plugins.VolumeDialogController.StreamState.() -> Unit = {}
    ): com.android.systemui.plugins.VolumeDialogController.StreamState {
        return com.android.systemui.plugins.VolumeDialogController.StreamState().apply(build)
    }
}
