/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.shade.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import com.android.internal.graphics.ColorUtils
import com.android.systemui.res.R

object ShadeColors {
    /**
     * Calculate notification shade panel color.
     *
     * @param context Context to resolve colors.
     * @param blurSupported Whether blur is enabled (can be off due to battery saver)
     * @param withScrim Whether to composite a scrim when blur is enabled (used by legacy shade).
     * @return color for the shade panel.
     */
    @JvmStatic
    fun shadePanel(context: Context, blurSupported: Boolean, withScrim: Boolean): Int {
        return if (blurSupported) {
            if (withScrim) {
                ColorUtils.compositeColors(
                    shadePanelStandard(context),
                    shadePanelScrimBehind(context),
                )
            } else {
                shadePanelStandard(context)
            }
        } else {
            shadePanelFallback(context)
        }
    }

    @JvmStatic
    fun notificationScrim(context: Context, blurSupported: Boolean): Int {
        return if (blurSupported) {
            notificationScrimStandard(context)
        } else {
            notificationScrimFallback(context)
        }
    }

    @JvmStatic
    fun shadePanelScrimBehind(context: Context): Int {
        return context.resources.getColor(
            com.android.internal.R.color.shade_panel_scrim,
            context.theme,
        )
    }

    private fun Context.isNightModeActive(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == 
                Configuration.UI_MODE_NIGHT_YES
    }

    @JvmStatic
    private fun shadePanelStandard(context: Context): Int {
        return if (context.isNightModeActive()) {
            shadePanelStandardDark(context)
        } else {
            shadePanelStandardLight(context)
        }
    }

    private fun shadePanelStandardLight(context: Context): Int {
        val topLayerAlpha = 0.45f
        
        val layerAbove = ColorUtils.setAlphaComponent(
            context.getColor(R.color.shade_panel_base),
            (topLayerAlpha * 255).toInt()
        )
        
        val layerBelow = ColorUtils.setAlphaComponent(
            Color.parseColor("#F5F5F5"), 
            (0.25f * 255).toInt()
        )
        
        return ColorUtils.compositeColors(layerAbove, layerBelow)
    }

    private fun shadePanelStandardDark(context: Context): Int {
        val topLayerAlpha = 0.50f
        
        val layerAbove = ColorUtils.setAlphaComponent(
            context.getColor(R.color.shade_panel_base),
            (topLayerAlpha * 255).toInt()
        )
        
        val layerBelow = ColorUtils.setAlphaComponent(
            Color.parseColor("#1A1A1A"),
            (0.30f * 255).toInt()
        )
        return ColorUtils.compositeColors(layerAbove, layerBelow)
    }

    @JvmStatic
    private fun shadePanelFallback(context: Context): Int {
        return context.getColor(R.color.shade_panel_fallback)
    }

    @JvmStatic
    private fun notificationScrimStandard(context: Context): Int {
        return ColorUtils.setAlphaComponent(
            context.getColor(R.color.notification_scrim_base),
            (0.35f * 255).toInt(),
        )
    }

    @JvmStatic
    private fun notificationScrimFallback(context: Context): Int {
        return context.getColor(R.color.notification_scrim_fallback)
    }
}
