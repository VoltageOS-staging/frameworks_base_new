/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.compose.ui.util.fastForEachIndexed
import com.android.app.tracing.coroutines.launchInTraced
import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.appvolume.ui.binder.VolumeDialogAppVolumeSliderViewBinder
import com.android.systemui.volume.dialog.appvolume.ui.viewmodel.VolumeDialogAppVolumeSliderViewModel
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.sliders.dagger.VolumeDialogSliderComponent
import com.android.systemui.volume.dialog.sliders.ui.viewmodel.VolumeDialogSlidersViewModel
import com.android.systemui.volume.dialog.ui.binder.ViewBinder
import com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach

@VolumeDialogScope
class VolumeDialogSlidersViewBinder
@Inject
constructor(
    private val viewModel: VolumeDialogSlidersViewModel,
    private val dialogViewModel: VolumeDialogViewModel,
    private val appVolumeSliderViewModel: VolumeDialogAppVolumeSliderViewModel,
    private val appVolumeSliderViewBinder: VolumeDialogAppVolumeSliderViewBinder,
) : ViewBinder {

    override fun CoroutineScope.bind(view: View) {
        val floatingSlidersContainer: ViewGroup =
            view.requireViewById(R.id.volume_dialog_floating_sliders_container)
        val mainSliderContainer: View =
            view.requireViewById(R.id.volume_dialog_main_slider_container)
        val background: View = view.requireViewById(R.id.volume_dialog_background)
        val bottomSection: View = view.requireViewById(R.id.volume_dialog_bottom_section_container)
        val topSection: View = view.requireViewById(R.id.volume_dialog_top_section_container)

        launchTraced("VDSVB#addTouchableBounds") {
            dialogViewModel.addTouchableBounds(mainSliderContainer, floatingSlidersContainer)
        }
        combine(viewModel.sliders, appVolumeSliderViewModel.state) { uiModel, appSliderState ->
                uiModel to (appSliderState != null)
            }
            .onEach { (uiModel, shouldShowAppSlider) ->
                bindSlider(
                    uiModel.sliderComponent,
                    mainSliderContainer,
                    arrayOf(mainSliderContainer, background, bottomSection, topSection),
                )

                val floatingSliderViewBinders = uiModel.floatingSliderComponent
                floatingSlidersContainer.ensureChildCount(
                    viewLayoutId = R.layout.volume_dialog_slider_floating,
                    count = floatingSliderViewBinders.size,
                    appSliderView = floatingSlidersContainer.getAppSliderView(),
                )
                floatingSliderViewBinders.fastForEachIndexed { index, sliderComponent ->
                    val sliderContainer = floatingSlidersContainer.getChildAt(index)
                    bindSlider(sliderComponent, sliderContainer, arrayOf(sliderContainer))
                }
                if (shouldShowAppSlider) {
                    val sliderContainer =
                        floatingSlidersContainer.getAppSliderView()
                            ?: LayoutInflater.from(floatingSlidersContainer.context).inflate(
                                R.layout.volume_dialog_slider_floating_app,
                                floatingSlidersContainer,
                                false,
                            )
                    if (sliderContainer.parent == null) {
                        floatingSlidersContainer.addView(sliderContainer)
                    }
                    appVolumeSliderViewBinder.bind(sliderContainer)
                } else {
                    floatingSlidersContainer.removeAppSliderView()
                }
            }
            .launchInTraced("VDSVB#sliders", this)
    }

    private fun CoroutineScope.bindSlider(
        component: VolumeDialogSliderComponent,
        sliderContainer: View,
        viewsToAnimate: Array<View>,
    ) {
        with(component.sliderViewBinder()) { bind(sliderContainer) }
        with(component.overscrollViewBinder()) { bind(sliderContainer, viewsToAnimate) }
    }
}

private fun ViewGroup.getAppSliderView(): View? =
    findViewById(R.id.volume_dialog_app_slider_container)

private fun ViewGroup.removeAppSliderView() {
    getAppSliderView()?.let(::removeView)
}

private fun ViewGroup.ensureChildCount(
    @LayoutRes viewLayoutId: Int,
    count: Int,
    appSliderView: View? = null,
) {
    val systemChildCount = childCount - if (appSliderView != null) 1 else 0
    val childCountDelta = systemChildCount - count
    when {
        childCountDelta > 0 -> {
            removeViews(count, childCountDelta)
        }
        childCountDelta < 0 -> {
            val inflater = LayoutInflater.from(context)
            repeat(-childCountDelta) {
                val child = inflater.inflate(viewLayoutId, this, false)
                val insertIndex = appSliderView?.let(::indexOfChild)?.takeIf { it >= 0 } ?: childCount
                addView(child, insertIndex)
            }
        }
    }
}
