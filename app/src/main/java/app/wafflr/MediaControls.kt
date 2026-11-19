package app.wafflr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState

/**
 * Headphone buttons and lock-screen / notification controls while Wafflr is reading:
 * play/pause, previous and next sentence, and stop. Uses Android's own MediaSession, so a
 * headset button press or the lock-screen player controls the read.
 */
class MediaControls(private val context: Context, private val reader: Reader) : ReaderListener {
    private val nm = context.getSystemService(NotificationManager::class.java)!!
    private var lastSource = ""

    private val session = MediaSession(context, "Wafflr").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = reader.resume()
            override fun onPause() = reader.pause()
            override fun onSkipToNext() = reader.next()
            override fun onSkipToPrevious() = reader.previous()
            override fun onStop() = reader.stop()
        })
    }

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reading controls", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Pause, skip and stop while Wafflr is reading"
                setShowBadge(false)
            },
        )
        reader.addListener(this)
    }

    override fun onFinished(result: SpeechResult) { lastSource = "" }

    override fun onStatus(status: ReaderStatus) {
        when (status.state) {
            ReaderState.IDLE -> {
                nm.cancel(NOTIFICATION_ID)
                session.setPlaybackState(state(PlaybackState.STATE_STOPPED, 0))
                session.isActive = false
            }
            else -> {
                val playing = status.state != ReaderState.PAUSED
                session.setPlaybackState(
                    state(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, status.index.toLong()),
                )
                val title = if (status.total > 0) "Reading ${status.index + 1} of ${status.total}" else "Getting ready…"
                session.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, "Wafflr")
                        .build(),
                )
                session.isActive = true
                nm.notify(NOTIFICATION_ID, notification(title, playing))
            }
        }
    }

    private fun state(s: Int, position: Long) = PlaybackState.Builder()
        .setActions(
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP,
        )
        .setState(s, position, 1f)
        .build()

    private fun notification(title: String, playing: Boolean): Notification {
        fun action(icon: Int, label: String, what: String) = Notification.Action.Builder(
            Icon.createWithResource(context, icon), label,
            PendingIntent.getBroadcast(
                context, what.hashCode(),
                Intent(context, MediaButtonReceiver::class.java).setAction(what),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()

        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_speaker)
            .setContentTitle("Wafflr")
            .setContentText(title)
            .setContentIntent(open)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(R.drawable.ic_previous, "Previous", ACTION_PREVIOUS))
            .addAction(
                if (playing) action(R.drawable.ic_pause, "Pause", ACTION_TOGGLE)
                else action(R.drawable.ic_play, "Play", ACTION_TOGGLE),
            )
            .addAction(action(R.drawable.ic_next, "Next", ACTION_NEXT))
            .addAction(action(R.drawable.ic_stop, "Stop", ACTION_STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    /** Handles taps on the notification's buttons. */
    class MediaButtonReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val reader = WafflrApp.instance.reader
            when (intent.action) {
                ACTION_TOGGLE -> reader.togglePause()
                ACTION_NEXT -> reader.next()
                ACTION_PREVIOUS -> reader.previous()
                ACTION_STOP -> reader.stop()
            }
        }
    }

    companion object {
        private const val CHANNEL = "reading"
        private const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE = "app.wafflr.TOGGLE"
        const val ACTION_NEXT = "app.wafflr.NEXT"
        const val ACTION_PREVIOUS = "app.wafflr.PREVIOUS"
        const val ACTION_STOP = "app.wafflr.STOP"
    }
}
