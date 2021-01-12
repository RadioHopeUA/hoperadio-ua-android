package ua.hope.radio.player

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.IBinder
import androidx.annotation.Nullable
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultControlDispatcher
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import ua.hope.radio.R
import ua.hope.radio.activity.RadioActivity
import ua.hope.radio.utils.SingleLiveEvent
import kotlin.time.seconds

@OptIn(kotlin.time.ExperimentalTime::class)
class AudioPlaybackService : LifecycleService() {
    private var playerNotificationManager: PlayerNotificationManager? = null
    lateinit var exoPlayer: SimpleExoPlayer
        private set
    private lateinit var trackSelector: DefaultTrackSelector
    private val tracksMetadata: TracksMetadata = TracksMetadata()
    private var updateStreamInfoJob: Deferred<Unit>? = null

    override fun onCreate() {
        super.onCreate()

        trackSelector = DefaultTrackSelector(this)
        exoPlayer = SimpleExoPlayer.Builder(this).setTrackSelector(trackSelector).build()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.CONTENT_TYPE_SPEECH)
            .build()
        exoPlayer.setAudioAttributes(audioAttributes, true)
        exoPlayer.addListener(PlayerEventListener())

        playerNotificationManager = PlayerNotificationManager.createWithNotificationChannel(
            applicationContext,
            NOTIFICATION_CHANNEL,
            R.string.app_name,
            R.string.audio_notification_channel_name,
            NOTIFICATION_ID,
            object : PlayerNotificationManager.MediaDescriptionAdapter {
                override fun getCurrentContentTitle(player: Player): String {
                    return trackInfo.value?.title ?: "..."
                }

                @Nullable
                override fun createCurrentContentIntent(player: Player): PendingIntent? =
                    PendingIntent.getActivity(
                        applicationContext,
                        0,
                        Intent(applicationContext, RadioActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT
                    )

                @Nullable
                override fun getCurrentContentText(player: Player): String {
                    return trackInfo.value?.artist ?: ""
                }

                @Nullable
                override fun getCurrentLargeIcon(
                    player: Player,
                    callback: PlayerNotificationManager.BitmapCallback
                ): Bitmap? {
                    return BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
                }
            },
            object : PlayerNotificationManager.NotificationListener {
                override fun onNotificationStarted(
                    notificationId: Int,
                    notification: Notification
                ) {
                    startForeground(notificationId, notification)
                }

                override fun onNotificationCancelled(notificationId: Int) {
                    status.value = PlayerStatus.Stopped
                    exoPlayer.stop(true)

                    stopSelf()
                }

                override fun onNotificationPosted(
                    notificationId: Int,
                    notification: Notification,
                    ongoing: Boolean
                ) {
                    if (ongoing) {
                        // Make sure the service will not get destroyed while playing media.
                        startForeground(notificationId, notification)
                    } else {
                        // Make notification cancellable.
                        stopForeground(false)
                    }
                }
            }
        ).apply {
            // Omit skip previous and next actions.
            setUseNextAction(false)
            setUsePreviousAction(false)
            setUseNextActionInCompactView(false)
            setUsePreviousActionInCompactView(false)
            // Omit stop action.
            setUseStopAction(false)
            setControlDispatcher(DefaultControlDispatcher(0, 0))

            setSmallIcon(R.drawable.ic_stat_audio)
            setColor(ContextCompat.getColor(applicationContext, R.color.colorPrimary))

            setPlayer(exoPlayer)
        }
    }

    override fun onDestroy() {
        playerNotificationManager?.setPlayer(null)
        exoPlayer.release()
        updateStreamInfoJob?.cancel()

        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)

        return AudioServiceBinder()
    }

    inner class AudioServiceBinder : Binder() {
        val service
            get() = this@AudioPlaybackService
    }

    fun play(url: String) {
        exoPlayer.stop(true)
        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun getTracksMetadata(): TracksMetadata {
        tracksMetadata.tracks.clear()
        tracksMetadata.tracks[TracksMetadata.ADAPTIVE] = 0
        val group = trackSelector.currentMappedTrackInfo?.getTrackGroups(RENDER_IDX)
        if (group != null) {
            val len = group[TRACK_GROUP_IDX].length
            for (index in 0 until len) {
                val format = group[0].getFormat(index)
                tracksMetadata.tracks[index] = format.bitrate / 1000
            }
        }
        return tracksMetadata
    }

    fun selectTrack(trackId: Int) {
        tracksMetadata.selected = trackId
        val group = trackSelector.currentMappedTrackInfo?.getTrackGroups(RENDER_IDX)
        if (group != null) {
            val builder = trackSelector.buildUponParameters()
            builder.clearSelectionOverrides()
            if (trackId != TracksMetadata.ADAPTIVE) {
                builder.setSelectionOverride(RENDER_IDX, group, DefaultTrackSelector.SelectionOverride(TRACK_GROUP_IDX, trackId))
                trackSelector.parameters = builder.build()
            }
        }
    }

    private fun startStreamInfoJob() {
        updateStreamInfoJob = CoroutineScope(Dispatchers.IO).launchPeriodicAsync(5.seconds.toLongMilliseconds()) {
            Timber.d("Get track info")
            try {
                val request = Request.Builder()
                    .url(getString(R.string.radio_info_url))
                    .build()
                val response = okHttpClient.newCall(request).execute()
                val info = response.body?.string()
                if (info != null) {
                    CoroutineScope(Dispatchers.Main).launch {
                        trackInfo.value = StreamInfo.from(info)
                    }
                }
            } catch (t: Throwable) {
                Timber.e(t)
            }
        }
    }

    private inner class PlayerEventListener : Player.EventListener {
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    updateStreamInfoJob?.cancel()
                    trackInfo.value = StreamInfo.EMPTY
                    status.value = PlayerStatus.Buffering
                }
                Player.STATE_READY -> {
                    if (exoPlayer.playWhenReady) {
                        status.value = PlayerStatus.Playing(exoPlayer)
                        startStreamInfoJob()
                    } else {
                        updateStreamInfoJob?.cancel()
                        trackInfo.value = StreamInfo.EMPTY
                        status.value = PlayerStatus.Stopped
                    }
                }
                Player.STATE_ENDED,
                Player.STATE_IDLE -> {
                    stopForeground(true)
                    updateStreamInfoJob?.cancel()
                    trackInfo.value = StreamInfo.EMPTY
                    status.value = PlayerStatus.Stopped
                }
            }
        }

        override fun onPlayerError(e: ExoPlaybackException) {
            updateStreamInfoJob?.cancel()
            trackInfo.value = StreamInfo.EMPTY
            status.value = PlayerStatus.Error
        }
    }

    companion object {
        private val okHttpClient: OkHttpClient = OkHttpClient()
        private const val RENDER_IDX = 1
        private const val TRACK_GROUP_IDX = 0
        const val NOTIFICATION_ID = 100
        const val NOTIFICATION_CHANNEL = "hope_radio_ua_audio_channel"
        val status = SingleLiveEvent<PlayerStatus>()
        val trackInfo = SingleLiveEvent<StreamInfo>()
    }
}

fun CoroutineScope.launchPeriodicAsync(repeatMillis: Long, action: () -> Unit) = this.async {
    if (repeatMillis > 0) {
        while (true) {
            action()
            delay(repeatMillis)
        }
    } else {
        action()
    }
}
