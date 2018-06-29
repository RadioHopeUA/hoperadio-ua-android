package ua.hope.radio.core.player;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.support.annotation.Nullable;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;

import java.lang.ref.WeakReference;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import ua.hope.radio.core.R;
import ua.hope.radio.core.UpdateTrackRunnable;
import ua.hope.radio.core.activity.RadioActivity;

public class AudioService extends Service implements PlaybackManager.PlaybackServiceCallback {

	// The action of the incoming Intent indicating that it contains a command
	// to be executed (see {@link #onStartCommand})
	public static final String ACTION_CMD = "ua.hope.radio.core.player.ACTION_CMD";
	// The key in the extras of the incoming Intent indicating the command that
	// should be executed (see {@link #onStartCommand})
	public static final String CMD_NAME = "CMD_NAME";
	// A value of a CMD_NAME key in the extras of the incoming Intent that
	// indicates that the music playback should be paused (see {@link #onStartCommand})
	public static final String CMD_PAUSE = "CMD_PAUSE";
	private static final String TAG = AudioService.class.getSimpleName();
	// Delay stopSelf by using a handler. 30 min
	private static final int STOP_DELAY = 30*60*1000;
	private final DelayedStopHandler mDelayedStopHandler = new DelayedStopHandler(this);
	private final IBinder mBinder = new LocalBinder();
	private PlaybackManager mPlaybackManager;
	private MediaSessionCompat mSession;
	private MediaNotificationManager mMediaNotificationManager;
	private BroadcastReceiver mMediaButtonReceiver;
	private ScheduledFuture mScheduledTask;
	private UpdateTrackRunnable updateTrackRunnable;
	private ScheduledThreadPoolExecutor exec;
	private Handler mCurrentTrackHandler = new Handler(msg -> {
		String resp = msg.obj.toString();
		String[] splitted = resp.split(" - ");
		if (splitted.length == 2) {
			mSession.setMetadata(new MediaMetadataCompat.Builder()
					.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, splitted[0])
					.putString(MediaMetadataCompat.METADATA_KEY_TITLE, splitted[1])
					.build());
		}
		return true;
	});

	public boolean isPlaying() {
		return mPlaybackManager !=null && mPlaybackManager.getPlayback() != null && mPlaybackManager.getPlayback().isPlaying();
	}

	/*
	 * (non-Javadoc)
	 * @see android.app.Service#onCreate()
	 */
	@Override
	public void onCreate() {
		super.onCreate();
		Log.d(TAG, "onCreate");

		// Register media button receiver
		mMediaButtonReceiver = new MediaButtonReceiver();
		IntentFilter filter = new IntentFilter(Intent.ACTION_MEDIA_BUTTON);
		registerReceiver(mMediaButtonReceiver, filter);

		LocalPlayback playback = new LocalPlayback(this);
		mPlaybackManager = new PlaybackManager(this, playback);

		// Start a new MediaSession
		mSession = new MediaSessionCompat(this, "MusicService");
		mSession.setCallback(mPlaybackManager.getMediaSessionCallback());
		mSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
				MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

		Context context = getApplicationContext();
		Intent intent = new Intent(context, RadioActivity.class);
		PendingIntent pi = PendingIntent.getActivity(context, 99 /*request code*/,
				intent, PendingIntent.FLAG_UPDATE_CURRENT);
		mSession.setSessionActivity(pi);
		mSession.setExtras(new Bundle());

		mPlaybackManager.updatePlaybackState(null);

		try {
			mMediaNotificationManager = new MediaNotificationManager(this);
		} catch (RemoteException e) {
			throw new IllegalStateException("Could not create a MediaNotificationManager", e);
		}

		updateTrackRunnable = new UpdateTrackRunnable(mCurrentTrackHandler, getString(R.string.radio_info_url));
		exec = new ScheduledThreadPoolExecutor(1);
	}

	private void startTrackInfoScheduler() {
		stopTrackInfoScheduler();
		mScheduledTask = exec.scheduleAtFixedRate(updateTrackRunnable, 0, 5, TimeUnit.SECONDS);
	}

	private void stopTrackInfoScheduler() {
		if (mScheduledTask != null) {
			mScheduledTask.cancel(true);
		}
		exec.remove(updateTrackRunnable);
		exec.purge();
	}

	public MediaSessionCompat.Token getSessionToken() {
		return mSession.getSessionToken();
	}

	/**
	 * (non-Javadoc)
	 *
	 * @see Service#onStartCommand(Intent, int, int)
	 */
	@Override
	public int onStartCommand(Intent startIntent, int flags, int startId) {
		if (startIntent != null) {
			String action = startIntent.getAction();
			String command = startIntent.getStringExtra(CMD_NAME);
			if (ACTION_CMD.equals(action)) {
				if (CMD_PAUSE.equals(command)) {
					mPlaybackManager.handlePauseRequest();
				}
			} else {
				// Try to handle the intent as a media button event wrapped by MediaButtonReceiver
				MediaButtonReceiver.handleIntent(mSession, startIntent);
			}
		}
		// Reset the delay handler to enqueue a message to stop the service if
		// nothing is playing.
		mDelayedStopHandler.removeCallbacksAndMessages(null);
		mDelayedStopHandler.sendEmptyMessageDelayed(0, STOP_DELAY);
		return START_STICKY;
	}

	/**
	 * (non-Javadoc)
	 *
	 * @see Service#onDestroy()
	 */
	@Override
	public void onDestroy() {
		Log.d(TAG, "onDestroy");
		// Service is being killed, so make sure we release our resources
		mPlaybackManager.destroy();
		mPlaybackManager = null;
		mMediaNotificationManager.destroy();
		mMediaNotificationManager = null;

		mDelayedStopHandler.removeCallbacksAndMessages(null);
		unregisterReceiver(mMediaButtonReceiver);
		mSession.release();
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	/**
	 * Callback method called from PlaybackManager whenever the music is about to play.
	 */
	@Override
	public void onPlaybackStart() {
		mSession.setActive(true);

		mDelayedStopHandler.removeCallbacksAndMessages(null);

		// The service needs to continue running even after the bound client (usually a
		// MediaController) disconnects, otherwise the music playback will stop.
		// Calling startService(Intent) will keep the service running until it is explicitly killed.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			startForegroundService(new Intent(getApplicationContext(), AudioService.class));
		} else {
			startService(new Intent(getApplicationContext(), AudioService.class));
		}
		mMediaNotificationManager.startNotification();
		startTrackInfoScheduler();
	}

	/**
	 * Callback method called from PlaybackManager whenever the music stops playing.
	 */
	@Override
	public void onPlaybackStop() {
		mSession.setActive(false);
		// Reset the delayed stop handler, so after STOP_DELAY it will be executed again,
		// potentially stopping the service.
		mDelayedStopHandler.removeCallbacksAndMessages(null);
		mDelayedStopHandler.sendEmptyMessageDelayed(0, STOP_DELAY);
		stopTrackInfoScheduler();
		mMediaNotificationManager.stopNotification();
	}

	@Override
	public void onPlaybackPaused() {
		stopTrackInfoScheduler();
		mSession.setActive(false);
		mMediaNotificationManager.pauseNotification();
	}

	@Override
	public void onNotificationRequired() {
		mMediaNotificationManager.startNotification();
	}

	@Override
	public void onPlaybackStateUpdated(PlaybackStateCompat newState) {
		mSession.setPlaybackState(newState);
	}

	@Nullable
	public TrackGroupArray getTrackInfo() {
		if (mPlaybackManager != null && mPlaybackManager.getPlayback() != null) {
			MappingTrackSelector.MappedTrackInfo info = mPlaybackManager.getPlayback().getTrackInfo();
			if (info != null && info.getRendererCount() > 1) {
				return info.getTrackGroups(1);
			}
		}
		return null;
	}

	public int getSelectedTrackId() {
		return mPlaybackManager.getPlayback().getSelectedTrackId();
	}

	public void selectTrack(int id) {
		if (mPlaybackManager != null && mPlaybackManager.getPlayback() != null) {
			mPlaybackManager.getPlayback().selectTrack(id);
		}
	}

	/**
	 * A simple handler that stops the service if playback is not active (playing)
	 */
	private static class DelayedStopHandler extends Handler {
		private final WeakReference<AudioService> mWeakReference;

		private DelayedStopHandler(AudioService service) {
			mWeakReference = new WeakReference<>(service);
		}

		@Override
		public void handleMessage(Message msg) {
			AudioService service = mWeakReference.get();
			if (service != null && service.mPlaybackManager.getPlayback() != null) {
				if (service.mPlaybackManager.getPlayback().isPlaying() || service.mPlaybackManager.getPlayback().getState() == PlaybackState.STATE_PAUSED) {
					Log.d(TAG, "Ignoring delayed stop since the media player is in use.");
					return;
				}
				Log.d(TAG, "Stopping service with delay handler.");
				service.stopSelf();
			}
		}
	}

	public class LocalBinder extends Binder {
		public AudioService getService() {
			// Return this instance of LocalService so clients can call public methods
			return AudioService.this;
		}
	}
}
