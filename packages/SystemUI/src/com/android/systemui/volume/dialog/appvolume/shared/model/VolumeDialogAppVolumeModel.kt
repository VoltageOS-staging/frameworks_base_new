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

package com.android.systemui.volume.dialog.appvolume.shared.model

import com.android.systemui.common.shared.model.Icon

/** Models a selected active app volume target for the inline volume dialog extension. */
data class VolumeDialogAppVolumeModel(
    val packageName: String,
    val label: String,
    val volumePercent: Float,
    val isMuted: Boolean,
    val icon: Icon.Loaded,
)

/** Full dialog-local state for the inline app volume extension. */
data class VolumeDialogAppVolumeStateModel(
    val selectedApp: VolumeDialogAppVolumeModel? = null,
    val isExpanded: Boolean = false,
)
