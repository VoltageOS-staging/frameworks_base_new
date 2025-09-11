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

import android.content.res.Configuration
import android.content.res.Resources
import android.content.Context
import android.graphics.Color
import android.provider.Settings
import com.android.internal.graphics.ColorUtils
import com.android.systemui.res.R

object ShadeColors {
    
    // Cache for better performance
    private var cachedDualToneSetting: Boolean? = null
    private var lastContextHashCode: Int = 0
    
    @JvmStatic
    fun Resources.shadePanel(blurSupported: Boolean, context: Context): Int {
        return if (blurSupported) {
            shadePanelStandard(context)
        } else {
            shadePanelFallback()
        }
    }

    @JvmStatic
    fun Resources.notificationScrim(blurSupported: Boolean, context: Context): Int {
        return if (blurSupported) {
            notificationScrimStandard(context)
        } else {
            notificationScrimFallback()
        }
    }

    /**
     * Check if device is in dark mode
     */
    private fun Resources.isNightModeActive(): Boolean {
        return (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == 
               Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Get dual tone setting with caching for better performance
     */
    private fun getDualToneSetting(context: Context?): Boolean {
        if (context == null) return true
        
        val currentHashCode = context.hashCode()
        if (cachedDualToneSetting != null && lastContextHashCode == currentHashCode) {
            return cachedDualToneSetting!!
        }
        
        val dualTone = try {
            Settings.System.getInt(context.contentResolver, "qs_dual_tone", 1) == 1
        } catch (e: Exception) {
            true // Safe fallback
        }
        
        cachedDualToneSetting = dualTone
        lastContextHashCode = currentHashCode
        return dualTone
    }

    @JvmStatic
    private fun Resources.shadePanelStandard(context: Context): Int {
        val useDualTone = getDualToneSetting(context)
        val isNightMode = isNightModeActive()
        
        // Create multiple layers for more sophisticated blending
        val baseLayer = getColor(R.color.shade_panel_base, null)
        
        // Adjust opacity based on theme and dual tone setting
        val primaryAlpha = when {
            isNightMode && useDualTone -> 0.75f
            isNightMode -> 0.8f
            useDualTone -> 0.6f
            else -> 0.65f
        }
        
        val secondaryAlpha = when {
            isNightMode && useDualTone -> 0.18f
            isNightMode -> 0.12f
            useDualTone -> 0.15f
            else -> 0.1f
        }
        
        // Primary layer (the main shade color)
        val primaryLayer = ColorUtils.setAlphaComponent(
            baseLayer,
            (primaryAlpha * 255).toInt()
        )
        
        // Secondary layer (for depth and contrast)
        val secondaryLayer = if (useDualTone) {
            // Use white tinting for dual tone
            ColorUtils.setAlphaComponent(Color.WHITE, (secondaryAlpha * 255).toInt())
        } else {
            // Use base color variation for single tone
            val adjustedBase = if (isNightMode) {
                ColorUtils.blendARGB(baseLayer, Color.WHITE, 0.1f)
            } else {
                ColorUtils.blendARGB(baseLayer, Color.BLACK, 0.05f)
            }
            ColorUtils.setAlphaComponent(adjustedBase, (secondaryAlpha * 255).toInt())
        }
        
        // Composite the layers
        return ColorUtils.compositeColors(primaryLayer, secondaryLayer)
    }

    @JvmStatic
    private fun Resources.shadePanelFallback(): Int {
        return ColorUtils.blendARGB(
            getColor(R.color.nt_scrim_behind_1), 
            getColor(R.color.nt_scrim_behind_2), 
            0.5f
        )
    }

    @JvmStatic
    private fun Resources.notificationScrimStandard(context: Context): Int {
        val isNightMode = isNightModeActive()
        val useDualTone = getDualToneSetting(context)
        
        // Base scrim layer
        val baseScrim = getColor(R.color.notification_scrim_base, null)
        
        // Adjust alpha based on theme for better visibility
        val scrimAlpha = when {
            isNightMode -> 0.85f
            else -> 0.75f
        }
        
        val primaryScrim = ColorUtils.setAlphaComponent(
            baseScrim,
            (scrimAlpha * 255).toInt()
        )
        
        // Add subtle tinting layer if dual tone is enabled
        return if (useDualTone) {
            val tintAlpha = if (isNightMode) 0.08f else 0.12f
            val tintLayer = ColorUtils.setAlphaComponent(
                Color.WHITE, 
                (tintAlpha * 255).toInt()
            )
            ColorUtils.compositeColors(primaryScrim, tintLayer)
        } else {
            primaryScrim
        }
    }

    @JvmStatic
    private fun Resources.notificationScrimFallback(): Int {
        return getColor(R.color.notification_scrim_fallback, null)
    }
    
    /**
     * Clear cache when settings might have changed
     */
    @JvmStatic
    fun clearCache() {
        cachedDualToneSetting = null
        lastContextHashCode = 0
    }
}
