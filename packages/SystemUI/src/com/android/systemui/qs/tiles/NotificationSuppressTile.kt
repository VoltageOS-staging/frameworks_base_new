/*
 * Copyright (C) 2025 VoltageOS
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
package com.android.systemui.qs.tiles

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.nano.MetricsProto.MetricsEvent
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import javax.inject.Inject

class NotificationSuppressTile @Inject constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger
) : QSTileImpl<BooleanState>(
    host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
    statusBarStateController, activityStarter, qsLogger
) {

    companion object {
        const val TILE_SPEC = "notif_suppress"
        private const val SETTING_KEY = Settings.System.NOTIFICATION_SOUND_VIB_SCREEN_ON
    }

    private val icon = ResourceIcon.get(R.drawable.ic_qs_notification_suppress)

    override fun newTileState(): BooleanState {
        return BooleanState().apply {
            handlesLongClick = false
        }
    }

    override fun handleClick(expandable: Expandable?) {
        val currentState = Settings.System.getInt(
            mContext.contentResolver,
            SETTING_KEY,
            1
        )
        
        val newState = if (currentState == 1) 0 else 1
        Settings.System.putInt(mContext.contentResolver, SETTING_KEY, newState)
        refreshState()
    }

    override fun handleUpdateState(state: BooleanState, arg: Any?) {
        val isSuppressed = Settings.System.getInt(
            mContext.contentResolver,
            SETTING_KEY,
            1
        ) == 0

        state.value = isSuppressed
        state.label = mContext.getString(R.string.quick_settings_notif_suppress_label)
        state.icon = icon

        if (isSuppressed) {
            state.state = Tile.STATE_ACTIVE
            state.secondaryLabel = mContext.getString(R.string.quick_settings_notif_suppress_on)
        } else {
            state.state = Tile.STATE_INACTIVE
            state.secondaryLabel = null
        }
    }

    override fun getLongClickIntent(): Intent {
        return Intent(Settings.ACTION_SOUND_SETTINGS)
    }

    override fun getTileLabel(): CharSequence {
        return mContext.getString(R.string.quick_settings_notif_suppress_label)
    }

    override fun getMetricsCategory(): Int = MetricsEvent.QS_PANEL
}
