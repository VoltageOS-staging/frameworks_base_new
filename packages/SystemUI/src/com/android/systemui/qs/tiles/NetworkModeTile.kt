package com.android.systemui.qs.tiles

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.nano.MetricsProto
import com.android.systemui.animation.Expandable
import com.android.systemui.common.networkmode.NetworkMode
import com.android.systemui.common.networkmode.NetworkModeController
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

class NetworkModeTile @Inject constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
) : QSTileImpl<BooleanState>(
    host,
    uiEventLogger,
    backgroundLooper,
    mainHandler,
    falsingManager,
    metricsLogger,
    statusBarStateController,
    activityStarter,
    qsLogger,
) {

    private val controller = NetworkModeController.get(mContext)
    private val listener = NetworkModeController.Listener { refreshState() }

    override fun newTileState(): BooleanState {
        return BooleanState().apply {
            handlesLongClick = true
        }
    }

    override fun handleSetListening(listening: Boolean) {
        super.handleSetListening(listening)
        if (listening) {
            controller.addListener(listener)
            controller.acquireListening()
        } else {
            controller.removeListener(listener)
            controller.releaseListening()
        }
    }

    override fun handleClick(expandable: Expandable?) {
        if (controller.cycleModeForDefaultDataSim()) {
            refreshState()
        }
    }

    override fun handleUpdateState(state: BooleanState, arg: Any?) {
        val tileState = controller.state.value
        val summarySim = tileState.defaultDataSimState

        state.label = mContext.getString(R.string.quick_settings_network_mode_label)
        state.icon = maybeLoadResourceIcon(R.drawable.ic_qs_network_mode)
        state.handlesLongClick = true

        if (!tileState.isAvailable || summarySim == null) {
            state.value = false
            state.secondaryLabel = mContext.getString(R.string.tile_unavailable)
            state.state = Tile.STATE_UNAVAILABLE
            return
        }

        state.value = true
        state.secondaryLabel =
            when {
                summarySim.isLoading -> mContext.getString(R.string.qs_network_mode_applying)
                summarySim.displayMode == null -> summarySim.simLabel
                else -> "${summarySim.simLabel} ${summarySim.displayMode.label}"
            }
        state.stateDescription = state.secondaryLabel
        state.contentDescription = "${state.label}, ${summarySim.carrierName}, ${state.secondaryLabel}"
        state.state = if (summarySim.displayMode == NetworkMode.MODE_3G) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
    }

    override fun getLongClickIntent(): Intent {
        return Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)
    }

    override fun getTileLabel(): CharSequence {
        return mContext.getString(R.string.quick_settings_network_mode_label)
    }

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.VOLTAGE

    override fun handleDestroy() {
        super.handleDestroy()
        controller.removeListener(listener)
    }

    companion object {
        const val TILE_SPEC = "networkmode"
    }
}
