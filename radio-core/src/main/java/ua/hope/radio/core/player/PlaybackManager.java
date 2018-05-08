package ua.hope.radio.core.player;

import android.os.SystemClock;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

/**
 * Manage the interactions among the container service, the queue manager and the actual playback.
 */
public class PlaybackManager implements Playback.Callback {

	private static final String TAG = PlaybackManager.class.getSimpleName();

	private Playback mPlayback;
	private PlaybackServiceCallback mServiceCallback;
	private MediaSessionCallback mMediaSessionCallback;

	public PlaybackManager(PlaybackServiceCallback serviceCallback, Playback playback) {
		mServiceCallback = serviceCallback;
		mMediaSessionCallback = new MediaSessionCallback();
		mPlayback = playback;
		mPlayback.setCallback(this);
	}

	public Playback getPlayback() {
		return mPlayback;
	}

	public MediaSessionCompat.Callback getMediaSessionCallback() {
		return mMediaSessionCallback;
	}

	/**
	 * Handle a request to play music
	 */
	public void handlePlayRequest() {
		Log.d(TAG, "handlePlayRequest: mState=" + mPlayback.getState());
		mServiceCallback.onPlaybackStart();
		mPlayback.play();
	}

	/**
	 * Handle a request to pause music
	 */
	public void handlePauseRequest() {
		Log.d(TAG, "handlePauseRequest: mState=" + mPlayback.getState());
		if (mPlayback.isPlaying()) {
			mPlayback.pause();
			mServiceCallback.onPlaybackPaused();
		}
	}

	/**
	 * Handle a request to stop music
	 *
	 * @param withError Error message in case the stop has an unexpected cause. The error
	 *                  message will be set in the PlaybackState and will be visible to
	 *                  MediaController clients.
	 */
	public void handleStopRequest(String withError) {
		Log.d(TAG, "handleStopRequest: mState=" + mPlayback.getState() + " error="+ withError);
		mPlayback.stop(true);
		mServiceCallback.onPlaybackStop();
		updatePlaybackState(withError);
	}


	/**
	 * Update the current media player state, optionally showing an error message.
	 *
	 * @param error if not null, error message to present to the user.
	 */
	public void updatePlaybackState(String error) {
		Log.d(TAG, "updatePlaybackState, playback state=" + mPlayback.getState());
		long position = PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN;
		if (mPlayback != null && mPlayback.isConnected()) {
			position = mPlayback.getCurrentStreamPosition();
		}

		//noinspection ResourceType
		PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
				.setActions(getAvailableActions());

		int state = mPlayback.getState();

		// If there is an error message, send it to the playback state:
		if (error != null) {
			// Error states are really only supposed to be used for errors that cause playback to
			// stop unexpectedly and persist until the user takes action to fix it.
			stateBuilder.setErrorMessage(100, error);
			state = PlaybackStateCompat.STATE_ERROR;
			mServiceCallback.onPlaybackStop();
		}
		//noinspection ResourceType
		stateBuilder.setState(state, position, 1.0f, SystemClock.elapsedRealtime());

		// Set the activeQueueItemId if the current index is valid.
		stateBuilder.setActiveQueueItemId(1L);

		mServiceCallback.onPlaybackStateUpdated(stateBuilder.build());

		if (state == PlaybackStateCompat.STATE_PAUSED) {
			mServiceCallback.onPlaybackPaused();
		}
		if (state == PlaybackStateCompat.STATE_PLAYING ||
				state == PlaybackStateCompat.STATE_PAUSED) {
			mServiceCallback.onNotificationRequired();
		}
	}

	private long getAvailableActions() {
		long actions =
				PlaybackStateCompat.ACTION_PLAY_PAUSE |
						PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID;
		if (mPlayback.isPlaying()) {
			actions |= PlaybackStateCompat.ACTION_PAUSE;
		} else {
			actions |= PlaybackStateCompat.ACTION_PLAY;
		}
		return actions;
	}

	/**
	 * Implementation of the Playback.Callback interface
	 */
	@Override
	public void onCompletion() {
		handleStopRequest(null);
	}

	@Override
	public void onPlaybackStatusChanged(int state) {
		updatePlaybackState(null);
	}

	@Override
	public void onError(String error) {
		updatePlaybackState(error);
	}

	public void destroy() {
		mMediaSessionCallback = null;
		mServiceCallback = null;
		mPlayback.stop(false);
		mPlayback = null;
	}

	public interface PlaybackServiceCallback {

		void onPlaybackStart();

		void onNotificationRequired();

		void onPlaybackStop();

		void onPlaybackPaused();

		void onPlaybackStateUpdated(PlaybackStateCompat newState);
	}

	private class MediaSessionCallback extends MediaSessionCompat.Callback {
		@Override
		public void onPlay() {
			Log.d(TAG, "play");
			handlePlayRequest();
		}

		@Override
		public void onPause() {
			Log.d(TAG, "pause. current state=" + mPlayback.getState());
			handlePauseRequest();
		}

		@Override
		public void onStop() {
			Log.d(TAG, "stop. current state=" + mPlayback.getState());
			handleStopRequest(null);
		}
	}
}
