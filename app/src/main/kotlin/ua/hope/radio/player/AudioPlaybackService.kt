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
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.TrackSelectionOverrides
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
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
import kotlin.time.Duration.Companion.seconds

class AudioPlaybackService : LifecycleService() {
    private var playerNotificationManager: PlayerNotificationManager? = null
    lateinit var exoPlayer: ExoPlayer
        private set
    private val tracksMetadata: TracksMetadata = TracksMetadata()
    private var updateStreamInfoJob: Deferred<Unit>? = null

    override fun onCreate() {
        super.onCreate()

        exoPlayer = ExoPlayer.Builder(this).build()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.CONTENT_TYPE_MUSIC)
            .build()
        exoPlayer.setAudioAttributes(audioAttributes, true)
        exoPlayer.addListener(PlayerEventListener())

        playerNotificationManager = PlayerNotificationManager.Builder(applicationContext, NOTIFICATION_ID, NOTIFICATION_CHANNEL)
            .setChannelDescriptionResourceId(R.string.audio_notification_channel_name)
            .setChannelNameResourceId(R.string.app_name)
            .setMediaDescriptionAdapter(object : PlayerNotificationManager.MediaDescriptionAdapter {
                override fun getCurrentContentTitle(player: Player): String {
                    return trackInfo.value?.title ?: "..."
                }

                @Nullable
                override fun createCurrentContentIntent(player: Player): PendingIntent? =
                    PendingIntent.getActivity(
                        applicationContext,
                        0,
                        Intent(applicationContext, RadioActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
            })
            .setNotificationListener(object : PlayerNotificationManager.NotificationListener {
                override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
                    status.value = PlayerState.Stopped
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()

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
            })
            .setSmallIconResourceId(R.drawable.ic_stat_audio)
            .build()
        .apply {
            // Omit skip previous and next actions.
            setUseNextAction(false)
            setUsePreviousAction(false)
            setUseNextActionInCompactView(false)
            setUsePreviousActionInCompactView(false)
            // Omit stop action.
            setUseStopAction(false)
            setUseRewindAction(false)
            setUseFastForwardAction(false)

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
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        val hlsMediaSource = HlsMediaSource.Factory(DefaultHttpDataSource.Factory())
            .setAllowChunklessPreparation(true)
            .createMediaSource(MediaItem.fromUri(url))
        exoPlayer.setMediaSource(hlsMediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun getTracksMetadata(): TracksMetadata {
        tracksMetadata.tracks.clear()
        tracksMetadata.tracks[TracksMetadata.ADAPTIVE] = 0
        val group = (exoPlayer.trackSelector as DefaultTrackSelector).currentMappedTrackInfo?.getTrackGroups(RENDER_IDX)
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
        val trackSelector = exoPlayer.trackSelector as DefaultTrackSelector
        val groups = trackSelector.currentMappedTrackInfo?.getTrackGroups(RENDER_IDX)
        if (groups != null) {
            val builder = trackSelector.buildUponParameters()
            builder.setTrackSelectionOverrides(TrackSelectionOverrides.EMPTY)
            if (trackId != TracksMetadata.ADAPTIVE) {
                val overrides = TrackSelectionOverrides.Builder()
                    .addOverride(TrackSelectionOverrides.TrackSelectionOverride(groups.get(TRACK_GROUP_IDX), listOf(trackId)))
                    .build()
                builder.setTrackSelectionOverrides(overrides)
                trackSelector.parameters = builder.build()
            }
        }
    }

    private fun startStreamInfoJob() {
        updateStreamInfoJob = CoroutineScope(Dispatchers.IO).launchPeriodicAsync(5.seconds.inWholeMilliseconds) {
            Timber.d("Getting track info")
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
                Timber.e(t, "Unable to get track info")
            }
        }
    }

    private inner class PlayerEventListener : Player.Listener {
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    updateStreamInfoJob?.cancel()
                    trackInfo.value = StreamInfo.EMPTY
                    status.value = PlayerState.Buffering
                }
                Player.STATE_READY -> {
                    if (exoPlayer.playWhenReady) {
                        status.value = PlayerState.Playing(exoPlayer)
                        startStreamInfoJob()
                    } else {
                        updateStreamInfoJob?.cancel()
                        trackInfo.value = StreamInfo.EMPTY
                        status.value = PlayerState.Stopped
                    }
                }
                Player.STATE_ENDED,
                Player.STATE_IDLE -> {
                    stopForeground(true)
                    updateStreamInfoJob?.cancel()
                    trackInfo.value = StreamInfo.EMPTY
                    status.value = PlayerState.Stopped
                }
            }
        }

        override fun onPlayerError(e: PlaybackException) {
            updateStreamInfoJob?.cancel()
            trackInfo.value = StreamInfo.EMPTY
            status.value = PlayerState.Error
        }
    }

    companion object {
        private val okHttpClient: OkHttpClient = OkHttpClient()
        private const val RENDER_IDX = 1
        private const val TRACK_GROUP_IDX = 0
        const val NOTIFICATION_ID = 100
        const val NOTIFICATION_CHANNEL = "hope_radio_ua_audio_channel"
        val status = SingleLiveEvent<PlayerState>()
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
