package ua.hope.radio.player

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.AudioAttributes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerNotificationManager
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

@OptIn(UnstableApi::class)
class AudioPlaybackService : LifecycleService() {
    private var playerNotificationManager: PlayerNotificationManager? = null
    lateinit var exoPlayer: ExoPlayer
        private set
    private val tracksMetadata: TracksMetadata = TracksMetadata()
    private var updateStreamInfoJob: Deferred<Unit>? = null

    override fun onCreate() {
        super.onCreate()

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()

        exoPlayer.addListener(PlayerEventListener())

        playerNotificationManager = PlayerNotificationManager.Builder(
            applicationContext,
            NOTIFICATION_ID,
            NOTIFICATION_CHANNEL
        )
            .setChannelDescriptionResourceId(R.string.audio_notification_channel_name)
            .setChannelNameResourceId(R.string.app_name)
            .setMediaDescriptionAdapter(object : PlayerNotificationManager.MediaDescriptionAdapter {
                override fun getCurrentContentTitle(player: Player): String {
                    return trackInfo.value?.title ?: "..."
                }

                override fun createCurrentContentIntent(player: Player): PendingIntent? =
                    PendingIntent.getActivity(
                        applicationContext,
                        0,
                        Intent(applicationContext, RadioActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )

                override fun getCurrentContentText(player: Player): String {
                    return trackInfo.value?.artist ?: ""
                }

                override fun getCurrentLargeIcon(
                    player: Player,
                    callback: PlayerNotificationManager.BitmapCallback
                ): Bitmap? {
                    return BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
                }
            })
            .setNotificationListener(object : PlayerNotificationManager.NotificationListener {
                override fun onNotificationCancelled(
                    notificationId: Int,
                    dismissedByUser: Boolean
                ) {
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            startForeground(
                                notificationId,
                                notification,
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                            )
                        } else {
                            startForeground(notificationId, notification)
                        }
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
        val group =
            (exoPlayer.trackSelector as DefaultTrackSelector).currentMappedTrackInfo?.getTrackGroups(
                RENDER_IDX
            )
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
            builder.clearOverrides()
            if (trackId != TracksMetadata.ADAPTIVE) {
                val overrides = TrackSelectionOverride(groups.get(TRACK_GROUP_IDX), trackId)
                builder.addOverride(overrides)
                trackSelector.parameters = builder.build()
            }
        }
    }

    private fun startStreamInfoJob() {
        updateStreamInfoJob =
            CoroutineScope(Dispatchers.IO).launchPeriodicAsync(5.seconds.inWholeMilliseconds) {
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

        override fun onPlaybackStateChanged(playbackState: Int) {
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
