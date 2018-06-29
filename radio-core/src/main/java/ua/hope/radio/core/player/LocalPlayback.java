package ua.hope.radio.core.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.support.annotation.Nullable;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

import ua.hope.radio.core.R;

import static com.google.android.exoplayer2.C.CONTENT_TYPE_MUSIC;
import static com.google.android.exoplayer2.C.USAGE_MEDIA;

/**
 * A class that implements local media playback using {@link
 * com.google.android.exoplayer2.ExoPlayer}
 */
public final class LocalPlayback implements Playback {

	// The volume we set the media player to when we lose audio focus, but are
	// allowed to reduce the volume instead of stopping playback.
	private static final float VOLUME_DUCK = 0.2f;
	// The volume we set the media player when we have audio focus.
	private static final float VOLUME_NORMAL = 1.0f;
	private static final String TAG = LocalPlayback.class.getSimpleName();
	// we don't have audio focus, and can't duck (play at a low volume)
	private static final int AUDIO_NO_FOCUS_NO_DUCK = 0;
	// we don't have focus, but can duck (play at a low volume)
	private static final int AUDIO_NO_FOCUS_CAN_DUCK = 1;
	// we have full audio focus
	private static final int AUDIO_FOCUSED = 2;
	public static final int ADAPTIVE_TRACK_ID = 100;

	private static final DefaultBandwidthMeter BANDWIDTH_METER = new DefaultBandwidthMeter();
	private static final TrackSelection.Factory ADAPTIVE_FACTORY = new AdaptiveTrackSelection.Factory(BANDWIDTH_METER);

	private final Context mContext;
	private final AudioManager mAudioManager;
	private final ExoPlayerEventListener mEventListener = new ExoPlayerEventListener();
	private final IntentFilter mAudioNoisyIntentFilter =
			new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
	private boolean mPlayOnFocusGain;
	private Callback mCallback;
	private boolean mAudioNoisyReceiverRegistered;
	private int mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK;
	private SimpleExoPlayer mExoPlayer;
	private DefaultTrackSelector trackSelector;
	private TrackGroupArray lastSeenTrackGroupArray;
	private int selectedTrackId = ADAPTIVE_TRACK_ID;
	private final BroadcastReceiver mAudioNoisyReceiver =
			new BroadcastReceiver() {
				@Override
				public void onReceive(Context context, Intent intent) {
					if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
						Log.d(TAG, "Headphones disconnected.");
						if (isPlaying()) {
							Intent i = new Intent(context, AudioService.class);
							i.setAction(AudioService.ACTION_CMD);
							i.putExtra(AudioService.CMD_NAME, AudioService.CMD_PAUSE);
							mContext.startService(i);
						}
					}
				}
			};
	// Whether to return STATE_NONE or STATE_STOPPED when mExoPlayer is null;
	private boolean mExoPlayerNullIsStopped = false;
	private final AudioManager.OnAudioFocusChangeListener mOnAudioFocusChangeListener =
			new AudioManager.OnAudioFocusChangeListener() {
				@Override
				public void onAudioFocusChange(int focusChange) {
					Log.d(TAG, "onAudioFocusChange. focusChange="+ focusChange);
					switch (focusChange) {
						case AudioManager.AUDIOFOCUS_GAIN:
							mCurrentAudioFocusState = AUDIO_FOCUSED;
							break;
						case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
							// Audio focus was lost, but it's possible to duck (i.e.: play quietly)
							mCurrentAudioFocusState = AUDIO_NO_FOCUS_CAN_DUCK;
							break;
						case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
							// Lost audio focus, but will gain it back (shortly), so note whether
							// playback should resume
							mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK;
							mPlayOnFocusGain = mExoPlayer != null && mExoPlayer.getPlayWhenReady();
							break;
						case AudioManager.AUDIOFOCUS_LOSS:
							// Lost audio focus, probably "permanently"
							mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK;
							break;
					}

					if (mExoPlayer != null) {
						// Update the player state based on the change
						configurePlayerState();
					}
				}
			};

	public LocalPlayback(Context context) {
		Context applicationContext = context.getApplicationContext();
		this.mContext = applicationContext;

		this.mAudioManager =
				(AudioManager) applicationContext.getSystemService(Context.AUDIO_SERVICE);
	}

	@Override
	public void start() {
		// Nothing to do
	}

	@Override
	public void stop(boolean notifyListeners) {
		giveUpAudioFocus();
		unregisterAudioNoisyReceiver();
		releaseResources(true);
	}

	@Override
	public int getState() {
		if (mExoPlayer == null) {
			return mExoPlayerNullIsStopped
					? PlaybackStateCompat.STATE_STOPPED
					: PlaybackStateCompat.STATE_NONE;
		}
		switch (mExoPlayer.getPlaybackState()) {
			case Player.STATE_IDLE:
				return PlaybackStateCompat.STATE_PAUSED;
			case Player.STATE_BUFFERING:
				return PlaybackStateCompat.STATE_BUFFERING;
			case Player.STATE_READY:
				return mExoPlayer.getPlayWhenReady()
						? PlaybackStateCompat.STATE_PLAYING
						: PlaybackStateCompat.STATE_PAUSED;
			case Player.STATE_ENDED:
				return PlaybackStateCompat.STATE_PAUSED;
			default:
				return PlaybackStateCompat.STATE_NONE;
		}
	}

	@Override
	public void setState(int state) {
		// Nothing to do (mExoPlayer holds its own state).
	}

	@Override
	public boolean isConnected() {
		return true;
	}

	@Override
	public boolean isPlaying() {
		return mPlayOnFocusGain || (mExoPlayer != null && mExoPlayer.getPlayWhenReady());
	}

	@Override
	public long getCurrentStreamPosition() {
		return mExoPlayer != null ? mExoPlayer.getCurrentPosition() : 0;
	}

	@Override
	public void play() {
		mPlayOnFocusGain = true;
		tryToGetAudioFocus();
		registerAudioNoisyReceiver();

		if (mExoPlayer == null) {
			releaseResources(false); // release everything except the player

			if (mExoPlayer == null) {
				trackSelector = new DefaultTrackSelector(ADAPTIVE_FACTORY);
				lastSeenTrackGroupArray = null;
				mExoPlayer = ExoPlayerFactory.newSimpleInstance(new DefaultRenderersFactory(mContext), trackSelector);
				mExoPlayer.addListener(mEventListener);
			}

			// Android "O" makes much greater use of AudioAttributes, especially
			// with regards to AudioFocus. All of UAMP's tracks are music, but
			// if your content includes spoken word such as audiobooks or podcasts
			// then the content type should be set to CONTENT_TYPE_SPEECH for those
			// tracks.
			final AudioAttributes audioAttributes = new AudioAttributes.Builder()
					.setContentType(CONTENT_TYPE_MUSIC)
					.setUsage(USAGE_MEDIA)
					.build();
			mExoPlayer.setAudioAttributes(audioAttributes);

			// Produces DataSource instances through which media data is loaded.
			DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(
							mContext, Util.getUserAgent(mContext, mContext.getResources().getString(R.string.app_name)), null);
			// The MediaSource represents the media to be played.
			MediaSource mediaSource = new HlsMediaSource.Factory(dataSourceFactory)
					.createMediaSource(Uri.parse(mContext.getString(R.string.radio_stream_url)));

			// Prepares media to play (happens on background thread) and triggers
			// {@code onPlayerStateChanged} callback when the stream is ready to play.
			mExoPlayer.prepare(mediaSource);
		}

		configurePlayerState();
	}

	@Override
	public void pause() {
		// Pause player and cancel the 'foreground service' state.
		if (mExoPlayer != null) {
			mExoPlayer.setPlayWhenReady(false);
		}
		// While paused, retain the player instance, but give up audio focus.
		releaseResources(false);
		unregisterAudioNoisyReceiver();
	}

	@Override
	public void setCallback(Callback callback) {
		this.mCallback = callback;
	}

	@Override
	@Nullable
	public MappingTrackSelector.MappedTrackInfo getTrackInfo() {
		if (trackSelector != null) {
			return trackSelector.getCurrentMappedTrackInfo();
		}
		return null;
	}

	@Override
	public int getSelectedTrackId() {
		return selectedTrackId;
	}

	@Override
	public void selectTrack(int id) {
		TrackGroupArray trackGroupArray = trackSelector.getCurrentMappedTrackInfo().getTrackGroups(1);
		DefaultTrackSelector.ParametersBuilder parametersBuilder = trackSelector.buildUponParameters();
		parametersBuilder.clearSelectionOverrides();
		selectedTrackId = ADAPTIVE_TRACK_ID;
		if (id != 100) {
			parametersBuilder.setSelectionOverride(1, trackGroupArray, new DefaultTrackSelector.SelectionOverride(0, id));
			selectedTrackId = id;
		}
		trackSelector.setParameters(parametersBuilder);
	}

	private void tryToGetAudioFocus() {
		Log.d(TAG, "tryToGetAudioFocus");
		int result =
				mAudioManager.requestAudioFocus(
						mOnAudioFocusChangeListener,
						AudioManager.STREAM_MUSIC,
						AudioManager.AUDIOFOCUS_GAIN);
		if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
			mCurrentAudioFocusState = AUDIO_FOCUSED;
		} else {
			mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK;
		}
	}

	private void giveUpAudioFocus() {
		Log.d(TAG, "giveUpAudioFocus");
		if (mAudioManager.abandonAudioFocus(mOnAudioFocusChangeListener)
				== AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
			mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK;
		}
	}

	/**
	 * Reconfigures the player according to audio focus settings and starts/restarts it. This method
	 * starts/restarts the ExoPlayer instance respecting the current audio focus state. So if we
	 * have focus, it will play normally; if we don't have focus, it will either leave the player
	 * paused or set it to a low volume, depending on what is permitted by the current focus
	 * settings.
	 */
	private void configurePlayerState() {
		Log.d(TAG, "configurePlayerState. mCurrentAudioFocusState="+ mCurrentAudioFocusState);
		if (mCurrentAudioFocusState == AUDIO_NO_FOCUS_NO_DUCK) {
			// We don't have audio focus and can't duck, so we have to pause
			pause();
		} else {
			registerAudioNoisyReceiver();

			if (mCurrentAudioFocusState == AUDIO_NO_FOCUS_CAN_DUCK) {
				// We're permitted to play, but only if we 'duck', ie: play softly
				mExoPlayer.setVolume(VOLUME_DUCK);
			} else {
				mExoPlayer.setVolume(VOLUME_NORMAL);
			}

			// If we were playing when we lost focus, we need to resume playing.
			if (mPlayOnFocusGain) {
				mExoPlayer.setPlayWhenReady(true);
				mPlayOnFocusGain = false;
			}
		}
	}

	/**
	 * Releases resources used by the service for playback, which is mostly just the WiFi lock for
	 * local playback. If requested, the ExoPlayer instance is also released.
	 *
	 * @param releasePlayer Indicates whether the player should also be released
	 */
	private void releaseResources(boolean releasePlayer) {
		Log.d(TAG, "releaseResources. releasePlayer="+ releasePlayer);

		// Stops and releases player (if requested and available).
		if (releasePlayer && mExoPlayer != null) {
			mExoPlayer.release();
			mExoPlayer.removeListener(mEventListener);
			mExoPlayer = null;
			trackSelector = null;
			mExoPlayerNullIsStopped = true;
			mPlayOnFocusGain = false;
		}
	}

	private void registerAudioNoisyReceiver() {
		if (!mAudioNoisyReceiverRegistered) {
			mContext.registerReceiver(mAudioNoisyReceiver, mAudioNoisyIntentFilter);
			mAudioNoisyReceiverRegistered = true;
		}
	}

	private void unregisterAudioNoisyReceiver() {
		if (mAudioNoisyReceiverRegistered) {
			mContext.unregisterReceiver(mAudioNoisyReceiver);
			mAudioNoisyReceiverRegistered = false;
		}
	}

	private final class ExoPlayerEventListener implements Player.EventListener {
		@Override
		public void onTimelineChanged(Timeline timeline, Object manifest, int reason) {
			// Nothing to do.
		}

		@Override
		public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
			if (trackGroups != lastSeenTrackGroupArray) {
				MappingTrackSelector.MappedTrackInfo mappedTrackInfo = trackSelector.getCurrentMappedTrackInfo();
				if (mappedTrackInfo != null) {
					if (mappedTrackInfo.getTypeSupport(C.TRACK_TYPE_AUDIO)
							== MappingTrackSelector.MappedTrackInfo.RENDERER_SUPPORT_UNSUPPORTED_TRACKS) {
						Log.w(ExoPlayerEventListener.class.getSimpleName(), "Unsupported audio");
					}
				}
				lastSeenTrackGroupArray = trackGroups;
			}
		}

		@Override
		public void onLoadingChanged(boolean isLoading) {
			// Nothing to do.
		}

		@Override
		public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
			switch (playbackState) {
				case Player.STATE_IDLE:
				case Player.STATE_BUFFERING:
				case Player.STATE_READY:
					if (mCallback != null) {
						mCallback.onPlaybackStatusChanged(getState());
					}
					break;
				case Player.STATE_ENDED:
					// The media player finished playing the current song.
					if (mCallback != null) {
						mCallback.onCompletion();
					}
					break;
			}
		}

		@Override
		public void onPlayerError(ExoPlaybackException error) {
			final String what;
			switch (error.type) {
				case ExoPlaybackException.TYPE_SOURCE:
					//retry
					Log.e(TAG, "ExoPlayer error. Retrying");
					stop(true);
					play();
					return;
				case ExoPlaybackException.TYPE_RENDERER:
					what = error.getRendererException().getMessage();
					break;
				case ExoPlaybackException.TYPE_UNEXPECTED:
					what = error.getUnexpectedException().getMessage();
					break;
				default:
					what = "Unknown: " + error;
			}

			Log.e(TAG, "ExoPlayer error: what=" + what);
			if (mCallback != null) {
				mCallback.onError("ExoPlayer error " + what);
			}
		}

		@Override
		public void onPositionDiscontinuity(int reason) {
			// Nothing to do.
		}

		@Override
		public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
			// Nothing to do.
		}

		@Override
		public void onSeekProcessed() {
			// Nothing to do.
		}

		@Override
		public void onRepeatModeChanged(int repeatMode) {
			// Nothing to do.
		}

		@Override
		public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
			// Nothing to do.
		}
	}
}
