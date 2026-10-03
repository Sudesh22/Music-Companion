
package com.sudesh.nowplaying

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

data class MediaSnapshot(
    val title: String,
    val artist: String,
    val duration: Long,
    val position: Long,
    val playing: Boolean,
    val artwork: Bitmap?
)

class MediaListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "NowPlayingBridge"

        const val ACTION_MEDIA_UPDATED =
            "com.sudesh.nowplaying.MEDIA_UPDATED"

        @Volatile
        var currentSnapshot: MediaSnapshot? = null
            private set

        @Volatile
        var activeController: MediaController? = null
            private set
    }

    private lateinit var mediaSessionManager: MediaSessionManager

    private val controllers =
        mutableMapOf<MediaController, MediaController.Callback>()

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
            Log.d(TAG, "Active sessions changed")
            updateControllers(sessions ?: emptyList())
        }

    override fun onListenerConnected() {
        super.onListenerConnected()

        mediaSessionManager =
            getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager

        val component = ComponentName(
            this,
            MediaListenerService::class.java
        )

        mediaSessionManager.addOnActiveSessionsChangedListener(
            sessionsListener,
            component
        )

        Log.d(TAG, "Notification listener connected")

        refreshSessions()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshSessions()
    }

    private fun refreshSessions() {
        if (!::mediaSessionManager.isInitialized) return

        try {
            val component = ComponentName(
                this,
                MediaListenerService::class.java
            )

            val sessions =
                mediaSessionManager.getActiveSessions(component)

            updateControllers(sessions)

        } catch (e: Exception) {
            Log.e(TAG, "Could not read media sessions", e)
        }
    }

    private fun updateControllers(
        sessions: List<MediaController>
    ) {
        val activeControllers = sessions.toSet()
        val previousController = activeController

        // Remove callbacks from inactive sessions.
        controllers.keys.toList().forEach { controller ->
            if (controller !in activeControllers) {
                controllers[controller]?.let {
                    controller.unregisterCallback(it)
                }

                controllers.remove(controller)
            }
        }

        // Register callbacks for new sessions.
        for (controller in sessions) {
            if (controller !in controllers) {

                val callback = object : MediaController.Callback() {

                    override fun onMetadataChanged(
                        metadata: MediaMetadata?
                    ) {
                        Log.d(
                            TAG,
                            "Metadata changed: ${controller.packageName}"
                        )

                        if (controller == activeController) {
                            publishSession(controller)
                        }
                    }

                    override fun onPlaybackStateChanged(
                        state: PlaybackState?
                    ) {
                        Log.d(
                            TAG,
                            "Playback state changed: ${controller.packageName}"
                        )

                        // Re-evaluate sessions because another app
                        // may have started or stopped playback.
                        refreshSessions()
                    }

                    override fun onSessionDestroyed() {
                        Log.d(
                            TAG,
                            "Media session destroyed: ${controller.packageName}"
                        )

                        refreshSessions()
                    }
                }

                controllers[controller] = callback
                controller.registerCallback(callback)
            }
        }

        // Keep the existing active controller if it is still playing.
        val selected = when {
            previousController != null &&
                previousController in activeControllers &&
                previousController.playbackState?.state ==
                    PlaybackState.STATE_PLAYING -> {
                previousController
            }

            else -> {
                sessions.firstOrNull {
                    it.playbackState?.state ==
                        PlaybackState.STATE_PLAYING
                } ?: previousController?.takeIf {
                    it in activeControllers
                } ?: sessions.firstOrNull()
            }
        }

        activeController = selected

        if (selected != null) {
            publishSession(selected)
        } else {
            currentSnapshot = null
            sendUpdate()
        }

        Log.d(TAG, "Active media sessions: ${sessions.size}")
        Log.d(TAG, "Selected session: ${selected?.packageName}")
    }

    private fun publishSession(controller: MediaController) {

        // Ignore updates from controllers that are no longer active.
        if (controller != activeController) return

        val metadata = controller.metadata
        val playback = controller.playbackState

        val title = metadata?.getString(
            MediaMetadata.METADATA_KEY_TITLE
        ) ?: "Unknown title"

        val artist = metadata?.getString(
            MediaMetadata.METADATA_KEY_ARTIST
        ) ?: "Unknown artist"

        val duration = metadata?.getLong(
            MediaMetadata.METADATA_KEY_DURATION
        ) ?: 0L

        val playing =
            playback?.state == PlaybackState.STATE_PLAYING

        val position = calculatePosition(
            playback,
            duration
        )

        val artwork: Bitmap? =
            metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(
                    MediaMetadata.METADATA_KEY_ALBUM_ART
                )

        currentSnapshot = MediaSnapshot(
            title = title,
            artist = artist,
            duration = duration,
            position = position,
            playing = playing,
            artwork = artwork
        )

        Log.d(TAG, "Package: ${controller.packageName}")
        Log.d(TAG, "Title: $title")
        Log.d(TAG, "Artist: $artist")
        Log.d(TAG, "Duration: $duration ms")
        Log.d(TAG, "Position: $position ms")
        Log.d(TAG, "Playing: $playing")

        sendUpdate()
    }

    /**
     * Estimates the current playback position using Android's
     * PlaybackState position, update timestamp and playback speed.
     */
    private fun calculatePosition(
        playback: PlaybackState?,
        duration: Long
    ): Long {

        if (playback == null) return 0L

        var position = playback.position.coerceAtLeast(0L)

        if (
            playback.state == PlaybackState.STATE_PLAYING &&
            playback.lastPositionUpdateTime > 0L
        ) {
            val elapsed =
                (SystemClock.elapsedRealtime() -
                    playback.lastPositionUpdateTime).coerceAtLeast(0L)

            val advancement =
                (elapsed.toDouble() * playback.playbackSpeed.toDouble())
                    .toLong()

            position = (position + advancement).coerceAtLeast(0L)
        }

        if (duration > 0L) {
            position = position.coerceAtMost(duration)
        }

        return position
    }

    private fun sendUpdate() {
        sendBroadcast(
            Intent(ACTION_MEDIA_UPDATED).setPackage(packageName)
        )
    }

    override fun onListenerDisconnected() {

        controllers.forEach { (controller, callback) ->
            controller.unregisterCallback(callback)
        }

        controllers.clear()

        activeController = null
        currentSnapshot = null

        if (::mediaSessionManager.isInitialized) {
            mediaSessionManager.removeOnActiveSessionsChangedListener(
                sessionsListener
            )
        }

        sendUpdate()

        super.onListenerDisconnected()
    }
}