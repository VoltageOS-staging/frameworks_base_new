package com.android.systemui.volume.dialog.appvolume.domain

import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** Exposes [VolumeDialogAppVolumeButtonViewModel]. */
@VolumeDialogScope
class VolumeDialogAppVolumeButtonInteractor
@Inject
constructor(
    private val interactor: VolumeDialogAppVolumeInteractor,
) {
    val isVisible: Flow<Boolean> = interactor.isButtonVisible
    val isExpanded: Flow<Boolean> = interactor.isExpanded

    fun onButtonClicked() {
        interactor.onButtonClicked()
    }
}
