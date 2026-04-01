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

package com.android.systemui.qs.composefragment

import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class QSFragmentComposeMotionTest {

    @Test
    fun calculateQsOpenSettlePeakScale_fastVelocity_hasSubtleOvershoot() {
        val peakScale = calculateQsOpenSettlePeakScale(estimatedOpenVelocity = 4.5f)

        assertThat(peakScale).isGreaterThan(1f)
        assertThat(peakScale).isWithin(1e-6f).of(1.02f)
    }

    @Test
    fun calculateQsOpenSettlePeakScale_slowVelocity_staysAtRest() {
        val peakScale = calculateQsOpenSettlePeakScale(estimatedOpenVelocity = 0.5f)

        assertThat(peakScale).isEqualTo(1f)
    }

    @Test
    fun shouldTriggerQsFullOpenSettle_onlyWhenOpeningIntoFullQs() {
        assertThat(
                shouldTriggerQsFullOpenSettle(
                    wasFullyExpanded = false,
                    isFullyExpanded = true,
                    estimatedOpenVelocity = 4.5f,
                    isEditing = false,
                    isOverscrollSuppressed = false,
                )
            )
            .isTrue()

        assertThat(
                shouldTriggerQsFullOpenSettle(
                    wasFullyExpanded = true,
                    isFullyExpanded = true,
                    estimatedOpenVelocity = 4.5f,
                    isEditing = false,
                    isOverscrollSuppressed = false,
                )
            )
            .isFalse()

        assertThat(
                shouldTriggerQsFullOpenSettle(
                    wasFullyExpanded = false,
                    isFullyExpanded = true,
                    estimatedOpenVelocity = 4.5f,
                    isEditing = true,
                    isOverscrollSuppressed = false,
                )
            )
            .isFalse()

        assertThat(
                shouldTriggerQsFullOpenSettle(
                    wasFullyExpanded = false,
                    isFullyExpanded = true,
                    estimatedOpenVelocity = 4.5f,
                    isEditing = false,
                    isOverscrollSuppressed = true,
                )
            )
            .isFalse()

        assertThat(
                shouldTriggerQsFullOpenSettle(
                    wasFullyExpanded = false,
                    isFullyExpanded = true,
                    estimatedOpenVelocity = 0.5f,
                    isEditing = false,
                    isOverscrollSuppressed = false,
                )
            )
            .isFalse()
    }
}
