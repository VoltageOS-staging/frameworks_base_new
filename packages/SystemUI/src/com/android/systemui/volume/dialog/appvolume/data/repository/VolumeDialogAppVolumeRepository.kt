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

package com.android.systemui.volume.dialog.appvolume.data.repository

import com.android.systemui.volume.dialog.appvolume.shared.model.VolumeDialogAppVolumeStateModel
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Holds current dialog-local app volume extension state. */
@VolumeDialogScope
class VolumeDialogAppVolumeRepository @Inject constructor() {

    private val mutableState = MutableStateFlow(VolumeDialogAppVolumeStateModel())
    val state: StateFlow<VolumeDialogAppVolumeStateModel> = mutableState.asStateFlow()

    fun updateState(update: (VolumeDialogAppVolumeStateModel) -> VolumeDialogAppVolumeStateModel) {
        mutableState.update(update)
    }
}
