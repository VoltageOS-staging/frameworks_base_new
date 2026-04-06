package com.android.systemui.volume.dialog.appvolume.ui.viewmodel

import com.android.systemui.volume.dialog.appvolume.domain.VolumeDialogAppVolumeButtonInteractor
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** ViewModel for managing the app volume button in the volume dialog. */
class VolumeDialogAppVolumeButtonViewModel
@Inject
constructor(private val interactor: VolumeDialogAppVolumeButtonInteractor) {
    val isVisible: Flow<Boolean> = interactor.isVisible
    val isExpanded: Flow<Boolean> = interactor.isExpanded

    fun onButtonClicked() {
        interactor.onButtonClicked()
    }
}
