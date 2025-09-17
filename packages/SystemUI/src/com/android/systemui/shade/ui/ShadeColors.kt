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
import android.content.res.Resources
import android.graphics.Color
import android.provider.Settings
import com.android.internal.graphics.ColorUtils
import com.android.systemui.res.R

object ShadeColors {
    /**
     * Calculates the main shade panel background color.
     * Signature is compatible with the dual-tone patch.
     */
    @JvmStatic
    fun Resources.shadePanel(blurSupported: Boolean, context: Context): Int {
        return if (blurSupported) {
            shadePanelStandard(context)
        } else {
            shadePanelFallback()
        }
    }

    /**
     * Calculates the notification scrim color.
     * Signature is compatible with the dual-tone patch.
     * The context parameter is unused here but required for compatibility with callers.
     */
    @JvmStatic
    fun Resources.notificationScrim(blurSupported: Boolean, context: Context): Int {
        return if (blurSupported) {
            notificationScrimStandard()
        } else {
            notificationScrimFallback()
        }
    }

    private fun Resources.isNightModeActive(): Boolean {
        return (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun Resources.shadePanelStandard(context: Context): Int {
        return if (isNightModeActive()) {
            shadePanelStandardDark(context)
        } else {
            shadePanelStandardLight(context)
        }
    }

    private fun Resources.shadePanelStandardLight(context: Context): Int {
        val useDualTone = Settings.System.getInt(context.contentResolver, Settings.System.QS_DUAL_TONE, 1) == 1

        if (useDualTone) {
            // High opacity pastel. A solid light base with a strong accent.
            val topLayerAlpha = 0.40f // Strong accent for a clear pastel color
            val layerAbove = ColorUtils.setAlphaComponent(
                getColor(R.color.shade_panel_base, null),
                (topLayerAlpha * 255).toInt()
            )
            // Highly opaque base to make the background solid.
            val layerBelow = ColorUtils.setAlphaComponent(Color.WHITE, (0.90f * 255).toInt())
            return ColorUtils.compositeColors(layerAbove, layerBelow)
        } else {
            // Standard/Fallback logic when dual-tone is off
            val layerAbove = ColorUtils.setAlphaComponent(
                getColor(R.color.shade_panel_base, null),
                (0.7f * 255).toInt()
            )
            val layerBelow = ColorUtils.setAlphaComponent(Color.WHITE, (0.14f * 255).toInt())
            return ColorUtils.compositeColors(layerAbove, layerBelow)
        }
    }

    private fun Resources.shadePanelStandardDark(context: Context): Int {
        val useDualTone = Settings.System.getInt(context.contentResolver, Settings.System.QS_DUAL_TONE, 1) == 1

        if (useDualTone) {
            // REVISED: The "sweet spot". Solid feel but with perceptible blur.
            val topLayerAlpha = 0.10f // Subtle, premium tint
            val layerAbove = ColorUtils.setAlphaComponent(
                getColor(R.color.shade_panel_base, null),
                (topLayerAlpha * 255).toInt()
            )
            // Reduced from 95% to allow some transparency for the blur effect.
            val layerBelow = ColorUtils.setAlphaComponent(Color.BLACK, (0.88f * 255).toInt())
            return ColorUtils.compositeColors(layerAbove, layerBelow)
        } else {
            // REVISED: A new fallback logic that is opaque but more colorful.
            val topLayerAlpha = 0.35f // A more vibrant accent color
            val layerAbove = ColorUtils.setAlphaComponent(
                getColor(R.color.shade_panel_base, null),
                (topLayerAlpha * 255).toInt()
            )
            // A solid base to prevent it from being too transparent.
            val layerBelow = ColorUtils.setAlphaComponent(Color.BLACK, (0.85f * 255).toInt())
            return ColorUtils.compositeColors(layerAbove, layerBelow)
        }
    }

    private fun Resources.shadePanelFallback(): Int {
        return ColorUtils.blendARGB(getColor(R.color.nt_scrim_behind_1), getColor(R.color.nt_scrim_behind_2), 0.3f)
    }

    private fun Resources.notificationScrimStandard(): Int {
        return if (isNightModeActive()) {
            notificationScrimStandardDark()
        } else {
            notificationScrimStandardLight()
        }
    }

    private fun Resources.notificationScrimStandardLight(): Int {
        val layerAbove = ColorUtils.setAlphaComponent(
            getColor(R.color.notification_scrim_base, null),
            (0.62f * 255).toInt()
        )
        val layerBelow = ColorUtils.setAlphaComponent(Color.WHITE, (0.2f * 255).toInt())
        return ColorUtils.compositeColors(layerAbove, layerBelow)
    }

    private fun Resources.notificationScrimStandardDark(): Int {
        val layerAbove = ColorUtils.setAlphaComponent(
            getColor(R.color.notification_scrim_base, null),
            (0.65f * 255).toInt()
        )
        val layerBelow = ColorUtils.setAlphaComponent(Color.WHITE, (0.2f * 255).toInt())
        return ColorUtils.compositeColors(layerAbove, layerBelow)
    }

    private fun Resources.notificationScrimFallback(): Int {
        return getColor(R.color.notification_scrim_fallback, null)
    }
}
