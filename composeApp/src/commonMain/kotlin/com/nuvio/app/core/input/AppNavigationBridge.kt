package com.nuvio.app.core.input

import com.nuvio.app.AppScreenTab
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lets platform input that lives outside the Compose tree (the desktop gamepad
 * poller) see where the app is and ask it to switch tabs or reload, without
 * reaching into MainAppContent's state.
 */
object AppNavigationBridge {
    private val tabRequestFlow = MutableSharedFlow<AppScreenTab>(extraBufferCapacity = 4)
    val tabRequests: SharedFlow<AppScreenTab> = tabRequestFlow.asSharedFlow()

    private val reloadRequestFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reloadRequests: SharedFlow<Unit> = reloadRequestFlow.asSharedFlow()

    private val currentTabState = MutableStateFlow(AppScreenTab.Home)
    val currentTab: StateFlow<AppScreenTab> = currentTabState.asStateFlow()

    private val onRootTabsState = MutableStateFlow(true)

    /** True while the tab bar's root screen is showing, not a pushed route. */
    val isOnRootTabs: StateFlow<Boolean> = onRootTabsState.asStateFlow()

    private val playerRouteState = MutableStateFlow(false)
    val isPlayerRouteActive: StateFlow<Boolean> = playerRouteState.asStateFlow()

    fun requestTab(tab: AppScreenTab) {
        tabRequestFlow.tryEmit(tab)
    }

    fun requestReload() {
        reloadRequestFlow.tryEmit(Unit)
    }

    fun cycleTab(delta: Int) {
        val tabs = AppScreenTab.entries
        val index = tabs.indexOf(currentTabState.value).coerceAtLeast(0)
        requestTab(tabs[(index + delta).mod(tabs.size)])
    }

    fun reportState(tab: AppScreenTab, onRootTabs: Boolean, playerRouteActive: Boolean) {
        currentTabState.value = tab
        onRootTabsState.value = onRootTabs
        playerRouteState.value = playerRouteActive
    }
}
