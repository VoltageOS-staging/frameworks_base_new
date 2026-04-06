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
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AppVolume
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.UiBackground
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.appvolume.data.repository.VolumeDialogAppVolumeRepository
import com.android.systemui.volume.dialog.appvolume.shared.model.VolumeDialogAppVolumeModel
import com.android.systemui.volume.dialog.appvolume.shared.model.VolumeDialogAppVolumeStateModel
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialog
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogStateInteractor
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogVisibilityInteractor
import com.android.systemui.volume.dialog.shared.model.VolumeDialogVisibilityModel
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Provides dialog-local state for the inline app volume extension. */
@VolumeDialogScope
class VolumeDialogAppVolumeInteractor
@Inject
constructor(
    @Application private val context: Context,
    @UiBackground private val uiBackgroundContext: CoroutineContext,
    @VolumeDialog private val coroutineScope: CoroutineScope,
    private val packageManager: PackageManager,
    private val repository: VolumeDialogAppVolumeRepository,
    private val visibilityInteractor: VolumeDialogVisibilityInteractor,
    volumeDialogStateInteractor: VolumeDialogStateInteractor,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    val state: StateFlow<VolumeDialogAppVolumeStateModel> = repository.state
    val isButtonVisible: Flow<Boolean> = state.map { it.selectedApp != null }.distinctUntilChanged()
    val isExpanded: Flow<Boolean> = state.map { it.isExpanded }.distinctUntilChanged()
    val selectedApp: Flow<VolumeDialogAppVolumeModel?> = state.map { it.selectedApp }

    init {
        observeShowAppVolumeSetting()
            .onEach { refreshState() }
            .launchIn(coroutineScope)

        volumeDialogStateInteractor.volumeDialogState
            .map { it.activeStream to it.streamModels.size }
            .distinctUntilChanged()
            .onEach { refreshState() }
            .launchIn(coroutineScope)

        visibilityInteractor.dialogVisibility
            .onEach { visibility ->
                when (visibility) {
                    is VolumeDialogVisibilityModel.Visible -> {
                        repository.updateState { it.copy(isExpanded = false) }
                        refreshState()
                    }
                    is VolumeDialogVisibilityModel.Dismissed -> {
                        repository.updateState { it.copy(isExpanded = false) }
                    }
                    is VolumeDialogVisibilityModel.Invisible -> Unit
                }
            }
            .launchIn(coroutineScope)
    }

    fun onButtonClicked() {
        visibilityInteractor.resetDismissTimeout()
        coroutineScope.launch {
            refreshState()
            repository.updateState { current ->
                if (current.selectedApp == null) {
                    current.copy(isExpanded = false)
                } else {
                    current.copy(isExpanded = !current.isExpanded)
                }
            }
        }
    }

    fun onSliderChanged(volumePercent: Float) {
        visibilityInteractor.resetDismissTimeout()
        val selectedApp = repository.state.value.selectedApp ?: return
        coroutineScope.launch {
            val normalizedVolume = (volumePercent / 100f).coerceIn(0f, 1f)
            if (selectedApp.isMuted && normalizedVolume > 0f) {
                audioManager?.setAppMute(selectedApp.packageName, false)
            }
            audioManager?.setAppVolume(selectedApp.packageName, normalizedVolume)
            refreshState()
        }
    }

    private suspend fun refreshState() {
        val appVolume =
            if (isShowAppVolumeEnabled()) {
                resolveSelectedAppVolume()
            } else {
                null
            }
        repository.updateState { current ->
            current.copy(
                selectedApp = appVolume,
                isExpanded = current.isExpanded && appVolume != null,
            )
        }
    }

    private fun observeShowAppVolumeSetting(): Flow<Unit> =
        callbackFlow {
            val handler = Handler(Looper.getMainLooper())
            val observer =
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(Unit)
                    }
                }
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SHOW_APP_VOLUME),
                false,
                observer,
                UserHandle.USER_CURRENT
            )
            trySend(Unit)
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        }

    private fun isShowAppVolumeEnabled(): Boolean {
        return Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.SHOW_APP_VOLUME,
            0,
            UserHandle.USER_CURRENT,
        ) == 1
    }

    private suspend fun resolveSelectedAppVolume(): VolumeDialogAppVolumeModel? =
        withContext(uiBackgroundContext) {
            val appVolume = audioManager?.listAppVolumes()?.firstOrNull(AppVolume::isActive)
            appVolume?.toModel()
        }

    private fun AppVolume.toModel(): VolumeDialogAppVolumeModel {
        val appInfo =
            try {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_ANY_USER,
                )
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Unable to resolve app volume package: $packageName", e)
                null
            }

        val label = appInfo?.loadLabel(packageManager)?.toString().orEmpty().ifEmpty { packageName }
        val iconDrawable =
            appInfo?.loadIcon(packageManager) ?: context.getDrawable(R.drawable.ic_app_volume)!!

        return VolumeDialogAppVolumeModel(
            packageName = packageName,
            label = label,
            volumePercent = if (isMuted) 0f else (volume * 100f).coerceIn(0f, 100f),
            isMuted = isMuted,
            icon =
                Icon.Loaded(
                    drawable = iconDrawable,
                    contentDescription = ContentDescription.Loaded(label),
                    resId = if (appInfo == null) R.drawable.ic_app_volume else null,
                ),
        )
    }

    private companion object {
        const val TAG = "VolDialogAppVolume"
    }
}
