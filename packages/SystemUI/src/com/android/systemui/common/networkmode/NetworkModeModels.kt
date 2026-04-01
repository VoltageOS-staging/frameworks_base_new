package com.android.systemui.common.networkmode

import android.telephony.SubscriptionManager

enum class NetworkMode(val label: String) {
    MODE_5G("5G"),
    MODE_4G("4G"),
    MODE_3G("3G");

    companion object {
        val orderedModes = listOf(MODE_5G, MODE_4G, MODE_3G)
    }
}

data class NetworkModeSimState(
    val subId: Int,
    val slotIndex: Int,
    val carrierName: String,
    val simLabel: String,
    val availableModes: List<NetworkMode>,
    val currentMode: NetworkMode?,
    val displayMode: NetworkMode?,
    val effectiveSupportedMask: Long,
    val isFocused: Boolean,
    val isDefaultData: Boolean,
    val isLoading: Boolean,
    val isFailed: Boolean,
)

data class NetworkModeTileState(
    val simStates: List<NetworkModeSimState> = emptyList(),
    val defaultDataSubId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
    val focusedSubId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
) {
    val isAvailable: Boolean
        get() = simStates.isNotEmpty()

    val defaultDataSimState: NetworkModeSimState?
        get() =
            simStates.firstOrNull { it.subId == defaultDataSubId }
                ?: simStates.firstOrNull()

    val canSwitchDefaultDataSim: Boolean
        get() = simStates.size > 1
}
