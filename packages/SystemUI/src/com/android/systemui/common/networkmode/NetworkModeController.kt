package com.android.systemui.common.networkmode

import android.content.ContentResolver
import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val FOCUS_SETTING = "qs_networkmode_focus_sub"
private const val LOADING_TIMEOUT_MS = 5_000L
private const val INVALID_SUB_ID = SubscriptionManager.INVALID_SUBSCRIPTION_ID
private const val MAX_VISIBLE_SUBS = 2

private val GSM_MODE_MASK: Long =
    TelephonyManager.NETWORK_TYPE_BITMASK_GSM or
        TelephonyManager.NETWORK_TYPE_BITMASK_GPRS or
        TelephonyManager.NETWORK_TYPE_BITMASK_EDGE or
        TelephonyManager.NETWORK_TYPE_BITMASK_CDMA or
        TelephonyManager.NETWORK_TYPE_BITMASK_1xRTT
private val WCDMA_MODE_MASK: Long =
    TelephonyManager.NETWORK_TYPE_BITMASK_UMTS or
        TelephonyManager.NETWORK_TYPE_BITMASK_HSDPA or
        TelephonyManager.NETWORK_TYPE_BITMASK_HSUPA or
        TelephonyManager.NETWORK_TYPE_BITMASK_HSPA or
        TelephonyManager.NETWORK_TYPE_BITMASK_HSPAP or
        TelephonyManager.NETWORK_TYPE_BITMASK_TD_SCDMA
private val LEGACY_MODE_MASK: Long = GSM_MODE_MASK or WCDMA_MODE_MASK
private val LTE_MODE_MASK: Long = TelephonyManager.NETWORK_TYPE_BITMASK_LTE
private val NR_MODE_MASK: Long = TelephonyManager.NETWORK_TYPE_BITMASK_NR

class NetworkModeController private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver
    private val subscriptionManager = SubscriptionManager.from(appContext)
    private val telephonyManager = TelephonyManager.from(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val _state = MutableStateFlow(NetworkModeTileState())

    private val registeredCallbacks = linkedMapOf<Int, RegisteredAllowedTypesCallback>()
    private val pendingChanges = linkedMapOf<Int, PendingChange>()
    private val failedSubIds = linkedSetOf<Int>()

    private var listeningClients = 0

    private val subscriptionsChangedListener =
        object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                refreshState()
            }
        }

    private val activeDataCallback =
        object : TelephonyCallback(), TelephonyCallback.ActiveDataSubscriptionIdListener {
            override fun onActiveDataSubscriptionIdChanged(subId: Int) {
                refreshState()
            }
        }

    val state: StateFlow<NetworkModeTileState> = _state.asStateFlow()

    fun interface Listener {
        fun onStateChanged(state: NetworkModeTileState)
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onStateChanged(_state.value)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun acquireListening() {
        listeningClients++
        if (listeningClients != 1) return

        subscriptionManager.addOnSubscriptionsChangedListener(
            appContext.mainExecutor,
            subscriptionsChangedListener,
        )
        telephonyManager.registerTelephonyCallback(appContext.mainExecutor, activeDataCallback)
        refreshState()
    }

    fun releaseListening() {
        if (listeningClients == 0) return
        listeningClients--
        if (listeningClients != 0) return

        unregisterAllCallbacks()
        runCatching { telephonyManager.unregisterTelephonyCallback(activeDataCallback) }
        runCatching {
            subscriptionManager.removeOnSubscriptionsChangedListener(subscriptionsChangedListener)
        }
    }

    fun setFocusedSubId(subId: Int) {
        if (_state.value.focusedSubId == subId) return
        if (_state.value.simStates.none { it.subId == subId }) return

        persistFocusedSubId(subId)
        emitState(
            _state.value.copy(
                focusedSubId = subId,
                simStates =
                    _state.value.simStates.map { sim ->
                        sim.copy(isFocused = sim.subId == subId)
                    },
            )
        )
    }

    fun cycleModeForDefaultDataSim(): Boolean {
        val subId = _state.value.defaultDataSimState?.subId ?: return false
        return requestAdjacentMode(subId, 1)
    }

    fun switchDefaultDataSim(): Boolean {
        val simStates = _state.value.simStates
        if (simStates.size < 2) return false

        val currentSubId =
            _state.value.defaultDataSubId.takeIf { defaultSubId ->
                simStates.any { it.subId == defaultSubId }
            } ?: simStates.first().subId
        val currentIndex = simStates.indexOfFirst { it.subId == currentSubId }.takeIf { it >= 0 } ?: 0
        val nextSimState = simStates[(currentIndex + 1) % simStates.size]

        emitState(
            _state.value.copy(
                defaultDataSubId = nextSimState.subId,
                simStates =
                    _state.value.simStates.map { sim ->
                        sim.copy(isDefaultData = sim.subId == nextSimState.subId)
                    },
            )
        )

        scope.launch {
            withContext(Dispatchers.IO) { switchDefaultDataSubId(nextSimState.subId) }
            refreshState()
        }

        return true
    }

    fun requestAdjacentMode(subId: Int, direction: Int): Boolean {
        val simState = _state.value.simStates.firstOrNull { it.subId == subId } ?: return false
        val availableModes = simState.availableModes
        if (availableModes.size < 2) return false

        val currentMode = simState.displayMode ?: simState.currentMode ?: availableModes.first()
        val currentIndex = availableModes.indexOf(currentMode).takeIf { it >= 0 } ?: 0
        val delta = if (direction >= 0) 1 else -1
        val nextIndex = floorMod(currentIndex + delta, availableModes.size)
        return requestMode(subId, availableModes[nextIndex])
    }

    fun requestMode(subId: Int, targetMode: NetworkMode): Boolean {
        val simState = _state.value.simStates.firstOrNull { it.subId == subId } ?: return false
        val targetMask = buildModeMask(targetMode, simState.effectiveSupportedMask)
        if (targetMask == 0L) return false

        pendingChanges.remove(subId)?.waiter?.cancel()
        failedSubIds.remove(subId)

        val pendingChange = PendingChange(targetMode = targetMode, waiter = CompletableDeferred())
        pendingChanges[subId] = pendingChange

        emitState(
            _state.value.copy(
                simStates =
                    _state.value.simStates.map { current ->
                        if (current.subId != subId) current
                        else {
                            current.copy(
                                displayMode = targetMode,
                                isLoading = true,
                                isFailed = false,
                            )
                        }
                    },
            )
        )

        scope.launch {
            val telephonyForSub = telephonyManager.createForSubscriptionId(subId)
            val writeSucceeded =
                runCatching {
                        withContext(Dispatchers.IO) {
                            telephonyForSub.setAllowedNetworkTypesForReason(
                                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER,
                                targetMask,
                            )
                        }
                    }
                    .isSuccess

            if (!writeSucceeded) {
                pendingChanges.remove(subId)
                failedSubIds.add(subId)
                refreshState()
                return@launch
            }

            val confirmed = withTimeoutOrNull(LOADING_TIMEOUT_MS) { pendingChange.waiter.await() } != null
            if (!confirmed && pendingChanges[subId] === pendingChange) {
                pendingChanges.remove(subId)
                failedSubIds.add(subId)
                refreshState()
            }
        }

        return true
    }

    fun refreshState() {
        if (listeningClients == 0) return

        scope.launch {
            val subscriptions = withContext(Dispatchers.IO) { getActiveSubscriptions() }
            syncPerSubCallbacks(subscriptions)

            val defaultDataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
            val focusedSubId = resolveFocusedSubId(subscriptions, defaultDataSubId)
            val simStates = subscriptions.map { buildSimState(it, focusedSubId, defaultDataSubId) }

            emitState(
                NetworkModeTileState(
                    simStates = simStates,
                    defaultDataSubId = defaultDataSubId,
                    focusedSubId = focusedSubId,
                )
            )
        }
    }

    private fun getActiveSubscriptions(): List<SubscriptionInfo> {
        val infos =
            runCatching { subscriptionManager.activeSubscriptionInfoList.orEmpty() }
                .getOrDefault(emptyList())

        return infos
            .filter { info ->
                info.simSlotIndex >= 0 &&
                    !info.isOpportunistic &&
                    info.profileClass != SubscriptionManager.PROFILE_CLASS_PROVISIONING
            }
            .sortedBy { it.simSlotIndex }
            .take(MAX_VISIBLE_SUBS)
    }

    private fun syncPerSubCallbacks(activeSubscriptions: List<SubscriptionInfo>) {
        val activeSubIds = activeSubscriptions.map { it.subscriptionId }.toSet()
        val removedSubIds = registeredCallbacks.keys.filter { it !in activeSubIds }
        removedSubIds.forEach { subId ->
            registeredCallbacks.remove(subId)?.unregister()
            pendingChanges.remove(subId)?.waiter?.cancel()
            failedSubIds.remove(subId)
        }

        activeSubscriptions.forEach { info ->
            if (registeredCallbacks.containsKey(info.subscriptionId)) return@forEach

            val telephonyForSub = telephonyManager.createForSubscriptionId(info.subscriptionId)
            val callback =
                object : TelephonyCallback(), TelephonyCallback.AllowedNetworkTypesListener {
                    override fun onAllowedNetworkTypesChanged(reason: Int, allowedNetworkType: Long) {
                        if (
                            reason == TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER ||
                                reason == TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_CARRIER
                        ) {
                            refreshState()
                        }
                    }
                }

            telephonyForSub.registerTelephonyCallback(appContext.mainExecutor, callback)
            registeredCallbacks[info.subscriptionId] =
                RegisteredAllowedTypesCallback(telephonyForSub, callback)
        }
    }

    private fun buildSimState(
        info: SubscriptionInfo,
        focusedSubId: Int,
        defaultDataSubId: Int,
    ): NetworkModeSimState {
        val telephonyForSub = telephonyManager.createForSubscriptionId(info.subscriptionId)
        val userMask =
            safeGetAllowedNetworkTypes(
                telephonyForSub,
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER,
            )
        val supportedMask =
            safeGetSupportedMask(telephonyForSub)
                .takeIf { it > 0L }
                ?: userMask.takeIf { it > 0L }
                ?: Long.MAX_VALUE
        val carrierMask =
            safeGetAllowedNetworkTypes(
                telephonyForSub,
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_CARRIER,
            )
                .takeIf { it > 0L }
                ?: supportedMask
        val effectiveSupportedMask = supportedMask and carrierMask
        val availableModes =
            NetworkMode.orderedModes.filter { buildModeMask(it, effectiveSupportedMask) != 0L }

        val classifiedMode = classifyMode(userMask and effectiveSupportedMask)
        val pendingChange = pendingChanges[info.subscriptionId]
        if (pendingChange != null && classifiedMode == pendingChange.targetMode) {
            pendingChanges.remove(info.subscriptionId)
            pendingChange.waiter.complete(Unit)
            failedSubIds.remove(info.subscriptionId)
        }

        val displayMode =
            pendingChanges[info.subscriptionId]?.targetMode
                ?: classifiedMode
                ?: availableModes.firstOrNull()

        return NetworkModeSimState(
            subId = info.subscriptionId,
            slotIndex = info.simSlotIndex,
            carrierName = info.carrierName?.toString().orEmpty().ifBlank { "Carrier" },
            simLabel = "SIM ${info.simSlotIndex + 1}",
            availableModes = availableModes,
            currentMode = classifiedMode,
            displayMode = displayMode,
            effectiveSupportedMask = effectiveSupportedMask,
            isFocused = info.subscriptionId == focusedSubId,
            isDefaultData = info.subscriptionId == defaultDataSubId,
            isLoading = pendingChanges.containsKey(info.subscriptionId),
            isFailed = failedSubIds.contains(info.subscriptionId),
        )
    }

    private fun resolveFocusedSubId(
        subscriptions: List<SubscriptionInfo>,
        defaultDataSubId: Int,
    ): Int {
        val validSubIds = subscriptions.map { it.subscriptionId }.toSet()
        val persistedSubId = readFocusedSubId()

        val resolvedSubId =
            when {
                persistedSubId in validSubIds -> persistedSubId
                defaultDataSubId in validSubIds -> defaultDataSubId
                else -> subscriptions.firstOrNull()?.subscriptionId ?: INVALID_SUB_ID
            }

        if (resolvedSubId != INVALID_SUB_ID) {
            persistFocusedSubId(resolvedSubId)
        }
        return resolvedSubId
    }

    private fun readFocusedSubId(): Int =
        runCatching {
                Settings.Secure.getIntForUser(
                    contentResolver,
                    FOCUS_SETTING,
                    UserHandle.USER_CURRENT,
                )
            }
            .getOrDefault(INVALID_SUB_ID)

    private fun persistFocusedSubId(subId: Int) {
        runCatching {
            Settings.Secure.putIntForUser(
                contentResolver,
                FOCUS_SETTING,
                subId,
                UserHandle.USER_CURRENT,
            )
        }
    }

    private fun switchDefaultDataSubId(targetSubId: Int) {
        val subscriptions = getActiveSubscriptions()

        subscriptions.forEach { info ->
            if (info.subscriptionId != targetSubId) return@forEach
            val telephonyForSub = telephonyManager.createForSubscriptionId(info.subscriptionId)
            runCatching { telephonyForSub.setDataEnabled(true) }
        }

        runCatching { subscriptionManager.setDefaultDataSubId(targetSubId) }

        subscriptions.forEach { info ->
            val telephonyForSub = telephonyManager.createForSubscriptionId(info.subscriptionId)
            val dataEnabled = runCatching { telephonyForSub.getDataEnabled() }.getOrDefault(false)
            if (info.isOpportunistic && dataEnabled) return@forEach

            runCatching { telephonyForSub.setDataEnabled(info.subscriptionId == targetSubId) }
        }
    }

    private fun safeGetSupportedMask(telephonyForSub: TelephonyManager): Long =
        runCatching { telephonyForSub.supportedRadioAccessFamily }.getOrDefault(0L)

    private fun safeGetAllowedNetworkTypes(
        telephonyForSub: TelephonyManager,
        reason: Int,
    ): Long = runCatching { telephonyForSub.getAllowedNetworkTypesForReason(reason) }.getOrDefault(0L)

    private fun unregisterAllCallbacks() {
        registeredCallbacks.values.forEach { it.unregister() }
        registeredCallbacks.clear()
        pendingChanges.values.forEach { it.waiter.cancel() }
        pendingChanges.clear()
    }

    private fun emitState(state: NetworkModeTileState) {
        _state.value = state
        listeners.forEach { it.onStateChanged(state) }
    }

    private fun floorMod(value: Int, size: Int): Int {
        return ((value % size) + size) % size
    }

    private data class PendingChange(
        val targetMode: NetworkMode,
        val waiter: CompletableDeferred<Unit>,
    )

    private data class RegisteredAllowedTypesCallback(
        val telephonyManager: TelephonyManager,
        val callback: TelephonyCallback,
    ) {
        fun unregister() {
            runCatching { telephonyManager.unregisterTelephonyCallback(callback) }
        }
    }

    companion object {
        fun buildModeMask(mode: NetworkMode, supportedMask: Long): Long {
            val familyMask =
                when (mode) {
                    NetworkMode.MODE_5G -> NR_MODE_MASK or LTE_MODE_MASK or LEGACY_MODE_MASK
                    NetworkMode.MODE_4G -> LTE_MODE_MASK or LEGACY_MODE_MASK
                    NetworkMode.MODE_3G -> LEGACY_MODE_MASK
                }
            return familyMask and supportedMask
        }

        fun classifyMode(mask: Long): NetworkMode? {
            return when {
                mask and NR_MODE_MASK != 0L -> NetworkMode.MODE_5G
                mask and LTE_MODE_MASK != 0L -> NetworkMode.MODE_4G
                mask and LEGACY_MODE_MASK != 0L -> NetworkMode.MODE_3G
                else -> null
            }
        }

        @Volatile private var instance: NetworkModeController? = null

        fun get(context: Context): NetworkModeController {
            return instance
                ?: synchronized(this) {
                    instance ?: NetworkModeController(context).also { instance = it }
                }
        }
    }
}
