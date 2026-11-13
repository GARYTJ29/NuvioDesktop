package com.nuvio.app.features.player.desktop

/** The player on screen, if any, for app-wide features such as the power menu. */
internal object ActiveDesktopPlayer {
    @Volatile
    var controller: NativePlayerController? = null

    /** Called for each user action in the player overlay (the idle check listens). */
    @Volatile
    var onUserActivity: (() -> Unit)? = null
}
