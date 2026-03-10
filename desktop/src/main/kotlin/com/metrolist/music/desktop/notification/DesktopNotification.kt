package com.metrolist.music.desktop.notification

import com.metrolist.music.desktop.playback.DesktopPlayer
import com.metrolist.music.desktop.playback.SongInfo
import com.metrolist.music.desktop.settings.PreferencesManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon

/**
 * Manages the system tray icon for notifications and minimize-to-tray.
 * Provides a right-click context menu with Show/Exit actions.
 */
object DesktopNotification {
    private var trayIcon: TrayIcon? = null
    private var observeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Callback invoked when user clicks "Show Metrolist" in tray menu or double-clicks the icon. */
    var onShowWindow: (() -> Unit)? = null

    /** Callback invoked when user clicks "Exit" in tray menu. */
    var onExitApp: (() -> Unit)? = null

    fun initialize(player: DesktopPlayer) {
        if (!SystemTray.isSupported()) {
            Timber.w("System tray not supported — notifications and tray minimize disabled")
            return
        }

        try {
            val iconStream = Thread.currentThread().contextClassLoader
                .getResourceAsStream("icon.png")
                ?: DesktopNotification::class.java.getResourceAsStream("/icon.png")
            val image = if (iconStream != null) {
                javax.imageio.ImageIO.read(iconStream)
            } else {
                Timber.w("icon.png not found for notification tray icon")
                java.awt.Toolkit.getDefaultToolkit().createImage(ByteArray(0))
            }

            // Build context menu
            val popup = PopupMenu()
            val showItem = MenuItem("Show Metrolist")
            showItem.addActionListener { onShowWindow?.invoke() }
            val exitItem = MenuItem("Exit")
            exitItem.addActionListener { onExitApp?.invoke() }
            popup.add(showItem)
            popup.addSeparator()
            popup.add(exitItem)

            trayIcon = TrayIcon(image, "Metrolist", popup).apply {
                isImageAutoSize = true
                addActionListener { onShowWindow?.invoke() } // Double-click on Windows
            }

            SystemTray.getSystemTray().add(trayIcon)
        } catch (e: Exception) {
            Timber.w("Failed to create tray icon: ${e.message}")
            return
        }

        // Watch for song changes to show notifications
        observeJob = scope.launch {
            var previousSongId: String? = null
            player.state.collectLatest { state ->
                val currentSong = state.currentSong
                if (currentSong != null && currentSong.id != previousSongId && state.isPlaying) {
                    previousSongId = currentSong.id
                    if (PreferencesManager.preferences.value.notificationsEnabled) {
                        showNowPlaying(currentSong)
                    }
                }
            }
        }
    }

    private fun showNowPlaying(song: SongInfo) {
        try {
            trayIcon?.displayMessage(
                song.title,
                song.artist,
                TrayIcon.MessageType.NONE
            )
        } catch (e: Exception) {
            Timber.w("Failed to show notification: ${e.message}")
        }
    }

    fun release() {
        observeJob?.cancel()
        trayIcon?.let {
            try {
                SystemTray.getSystemTray().remove(it)
            } catch (_: Exception) {}
        }
        trayIcon = null
        onShowWindow = null
        onExitApp = null
    }
}
