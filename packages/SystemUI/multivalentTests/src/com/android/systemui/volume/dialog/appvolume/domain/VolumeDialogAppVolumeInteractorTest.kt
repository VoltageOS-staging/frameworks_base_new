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

package com.android.systemui.volume.dialog.appvolume.domain

import android.content.Context
import android.content.packageManager
import android.content.pm.ApplicationInfo
import android.content.testableContext
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AppVolume
import android.media.AudioManager
import android.os.UserHandle
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.currentValue
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.verify
import com.android.systemui.util.mockito.whenever
import com.android.systemui.volume.Events
import com.android.systemui.volume.dialog.appvolume.ui.viewmodel.volumeDialogAppVolumeSliderViewModel
import com.android.systemui.volume.dialog.data.repository.volumeDialogVisibilityRepository
import com.android.systemui.volume.dialog.shared.model.VolumeDialogVisibilityModel
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val ACTIVE_PACKAGE = "test.pkg.active"
private const val SECOND_ACTIVE_PACKAGE = "test.pkg.second"
private const val MISSING_PACKAGE = "test.pkg.missing"

@SmallTest
@RunWith(AndroidJUnit4::class)
class VolumeDialogAppVolumeInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val audioManager = mock<AudioManager>()
    private val appInfo = mock<ApplicationInfo>()

    @Before
    fun setUp() {
        with(kosmos) {
            testableContext.addMockSystemService(Context.AUDIO_SERVICE, audioManager)
            Settings.System.putIntForUser(
                testableContext.contentResolver,
                Settings.System.SHOW_APP_VOLUME,
                0,
                UserHandle.USER_CURRENT,
            )

            whenever(appInfo.loadLabel(packageManager)).thenReturn("Active App")
            whenever(appInfo.loadIcon(packageManager)).thenReturn(ColorDrawable(Color.RED))
            whenever(packageManager.getApplicationInfo(eq(ACTIVE_PACKAGE), any<Int>()))
                .thenReturn(appInfo)
            whenever(packageManager.getApplicationInfo(eq(SECOND_ACTIVE_PACKAGE), any<Int>()))
                .thenReturn(appInfo)
            whenever(packageManager.getApplicationInfo(eq(MISSING_PACKAGE), any<Int>()))
                .thenThrow(android.content.pm.PackageManager.NameNotFoundException())
        }
    }

    @Test
    fun buttonHidden_whenShowAppVolumeDisabled() {
        with(kosmos) {
            whenever(audioManager.listAppVolumes()).thenReturn(arrayListOf(activeAppVolume()))

            val underTest = volumeDialogAppVolumeInteractor

            runCurrent()

            assertThat(currentValue(underTest.state).selectedApp).isNull()
        }
    }

    @Test
    fun buttonHidden_whenNoActiveApps() {
        with(kosmos) {
            enableShowAppVolume()
            whenever(audioManager.listAppVolumes())
                .thenReturn(arrayListOf(activeAppVolume(active = false)))

            val underTest = volumeDialogAppVolumeInteractor

            runCurrent()

            assertThat(currentValue(underTest.state).selectedApp).isNull()
        }
    }

    @Test
    fun firstActiveAppSelected() {
        with(kosmos) {
            enableShowAppVolume()
            whenever(audioManager.listAppVolumes())
                .thenReturn(
                    arrayListOf(
                        activeAppVolume(packageName = ACTIVE_PACKAGE, active = true, volume = 0.4f),
                        activeAppVolume(packageName = SECOND_ACTIVE_PACKAGE, active = true, volume = 0.7f),
                    )
                )

            val underTest = volumeDialogAppVolumeInteractor

            runCurrent()

            val selectedApp = currentValue(underTest.state).selectedApp
            assertThat(selectedApp?.packageName).isEqualTo(ACTIVE_PACKAGE)
            assertThat(selectedApp?.label).isEqualTo("Active App")
            assertThat(selectedApp?.volumePercent).isEqualTo(40f)
        }
    }

    @Test
    fun missingPackageInfo_fallsBackSafely() {
        with(kosmos) {
            enableShowAppVolume()
            whenever(audioManager.listAppVolumes())
                .thenReturn(
                    arrayListOf(
                        activeAppVolume(packageName = MISSING_PACKAGE, active = true, volume = 0.25f)
                    )
                )

            val underTest = volumeDialogAppVolumeInteractor

            runCurrent()

            val selectedApp = currentValue(underTest.state).selectedApp
            assertThat(selectedApp?.label).isEqualTo(MISSING_PACKAGE)
            assertThat(selectedApp?.icon?.resId)
                .isEqualTo(com.android.systemui.res.R.drawable.ic_app_volume)
        }
    }

    @Test
    fun buttonClick_togglesExpansion_andDialogVisibleCollapses() {
        with(kosmos) {
            enableShowAppVolume()
            whenever(audioManager.listAppVolumes()).thenReturn(arrayListOf(activeAppVolume()))

            val underTest = volumeDialogAppVolumeInteractor

            runCurrent()
            underTest.onButtonClicked()
            runCurrent()
            assertThat(currentValue(underTest.state).isExpanded).isTrue()

            volumeDialogVisibilityRepository.updateVisibility {
                VolumeDialogVisibilityModel.Visible(Events.SHOW_REASON_VOLUME_CHANGED, false, 0)
            }
            runCurrent()

            assertThat(currentValue(underTest.state).isExpanded).isFalse()
        }
    }

    @Test
    fun sliderWrite_callsSetAppVolume_andRefreshesState() {
        with(kosmos) {
            enableShowAppVolume()
            whenever(audioManager.listAppVolumes())
                .thenReturn(
                    arrayListOf(activeAppVolume(volume = 0.2f)),
                    arrayListOf(activeAppVolume(volume = 0.55f)),
                )

            val underTest = volumeDialogAppVolumeInteractor
            val sliderViewModel = volumeDialogAppVolumeSliderViewModel

            runCurrent()
            underTest.onButtonClicked()
            runCurrent()

            sliderViewModel.setVolume(55f, true)
            runCurrent()

            verify(audioManager).setAppVolume(ACTIVE_PACKAGE, 0.55f)
            assertThat(currentValue(sliderViewModel.state)?.value).isEqualTo(55f)
        }
    }

    private fun Kosmos.enableShowAppVolume() {
        Settings.System.putIntForUser(
            testableContext.contentResolver,
            Settings.System.SHOW_APP_VOLUME,
            1,
            UserHandle.USER_CURRENT,
        )
    }

    private fun activeAppVolume(
        packageName: String = ACTIVE_PACKAGE,
        active: Boolean = true,
        volume: Float = 0.3f,
        muted: Boolean = false,
    ): AppVolume =
        mock {
            whenever(this.packageName).thenReturn(packageName)
            whenever(this.isActive).thenReturn(active)
            whenever(this.volume).thenReturn(volume)
            whenever(this.isMuted).thenReturn(muted)
        }
}
