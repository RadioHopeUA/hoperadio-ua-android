package ua.hope.radio.core.activity;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.crashlytics.android.Crashlytics;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;

import ua.hope.radio.core.BuildConfig;
import ua.hope.radio.core.R;
import ua.hope.radio.core.player.AudioService;
import ua.hope.radio.core.player.LocalPlayback;

/**
 * Created by vitalii on 8/7/17.
 */

public class RadioActivity extends AppCompatActivity {
	private static final String TAG = RadioActivity.class.getSimpleName();
	private static final int MENU_GROUP_TRACKS = 1;

	// Callback that ensures that we are showing the controls
	private final MediaControllerCompat.Callback mMediaControllerCallback =
			new MediaControllerCompat.Callback() {
				@Override
				public void onPlaybackStateChanged(@NonNull PlaybackStateCompat state) {
					RadioActivity.this.onPlaybackStateChanged(state);
				}

				@Override
				public void onMetadataChanged(MediaMetadataCompat metadata) {
					String artist = metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST);
					String title = metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE);
					artistNameText.setText(artist);
					songNameText.setText(title);
				}
			};
	protected AudioService mService;
	protected Button audioButton;
	protected Button wwwButton;
	protected ImageButton playButton;
	protected TextView statusText;
	protected TextView songNameText;
	protected TextView artistNameText;
	protected PopupMenu mPopupMenu;
	protected int mPlayButtonDrawableResId;
	protected int mPauseButtonDrawableResId;

	private boolean serviceBinded = false;
	private ServiceConnection mConnection = new ServiceConnection() {
		// Called when the connection with the service is established
		public void onServiceConnected(ComponentName className, IBinder service) {
			// Because we have bound to an explicit
			// service that is running in our own process, we can
			// cast its IBinder to a concrete class and directly access it.
			AudioService.LocalBinder binder = (AudioService.LocalBinder) service;
			mService = binder.getService();
			serviceBinded = true;
			Log.d(TAG, "onServiceConnected");
			try {
				connectToSession(mService.getSessionToken());
			} catch (RemoteException e) {
				Log.e(TAG, "could not connect media controller", e);
				if (BuildConfig.BUILD_TYPE.equals("release")) {
					Crashlytics.logException(e);
				}
			}
			if (mService.isPlaying()) {
				if (playButton != null) playButton.setImageResource(mPauseButtonDrawableResId);
				if (audioButton != null) audioButton.setVisibility(View.VISIBLE);
			} else {
				if (playButton != null) playButton.setImageResource(mPlayButtonDrawableResId);
				if (audioButton != null) audioButton.setVisibility(View.INVISIBLE);
			}
		}

		// Called when the connection with the service disconnects unexpectedly
		public void onServiceDisconnected(ComponentName className) {
			Log.e(TAG, "onServiceDisconnected");
			serviceBinded = false;
		}
	};

	private void connectToSession(MediaSessionCompat.Token token) throws RemoteException {
		MediaControllerCompat mediaController = new MediaControllerCompat(this, token);
		MediaControllerCompat.setMediaController(this, mediaController);
		mediaController.registerCallback(mMediaControllerCallback);
	}

	private void onPlaybackStateChanged(PlaybackStateCompat state) {
		Log.d(TAG, "onPlaybackStateChanged " + state);
		if (state == null) {
			return;
		}
		boolean enablePlay = false;
		switch (state.getState()) {
			case PlaybackStateCompat.STATE_PAUSED:
			case PlaybackStateCompat.STATE_STOPPED:
				enablePlay = true;
				break;
			case PlaybackStateCompat.STATE_ERROR:
				enablePlay = true;
				Log.e(TAG, "error, playback state: " + state.getErrorMessage());
				Toast.makeText(this, state.getErrorMessage(), Toast.LENGTH_LONG).show();
				break;
		}

		if (enablePlay) {
			playButton.setImageResource(mPlayButtonDrawableResId);
		} else {
			playButton.setImageResource(mPauseButtonDrawableResId);
		}
	}

	@Override
	protected void onStart() {
		super.onStart();
		Log.d(TAG, "Activity onStart");

		// Bind to LocalService
		Intent intent = new Intent(this, AudioService.class);
		bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
	}

	@Override
	protected void onStop() {
		super.onStop();
		Log.d(TAG, "Activity onStop");
		MediaControllerCompat controllerCompat = MediaControllerCompat.getMediaController(this);
		if (controllerCompat != null) {
			controllerCompat.unregisterCallback(mMediaControllerCallback);
		}
		if (serviceBinded) {
			unbindService(mConnection);
			serviceBinded = false;
		}
	}

	protected void onCreated() {
		wwwButton.setOnClickListener(v -> {
			//Creating the instance of PopupMenu
			Context wrapper = new ContextThemeWrapper(getBaseContext(), R.style.AppTheme);
			PopupMenu popup = new PopupMenu(wrapper, wwwButton);
			//Inflating the Popup using xml file
			popup.getMenuInflater().inflate(R.menu.www_popup, popup.getMenu());

			//registering popup with OnMenuItemClickListener
			popup.setOnMenuItemClickListener(item -> {
				String url = null;
				int i = item.getItemId();
				if (i == R.id.website) {
					url = getString(R.string.website_url);
				} else if (i == R.id.fb) {
					url = getString(R.string.fb_url);
				} else if (i == R.id.vk) {
					url = getString(R.string.vk_url);
				} else if (i == R.id.twitter) {
					url = getString(R.string.twitter_url);
				} else if (i == R.id.write_us) {
					url = getString(R.string.mailto_url);
				}
				if (url != null) {
					Intent intent = new Intent(Intent.ACTION_VIEW);
					intent.setData(Uri.parse(url));
					if (item.getItemId() == R.id.write_us) {
						intent.putExtra("android.intent.extra.SUBJECT", "\"" + getString(R.string.app_name) + "\" Android");
					}
					if (intent.resolveActivity(getPackageManager()) != null) {
						startActivity(intent);
					}
				}

				return true;
			});
			popup.show();
		});
		playButton.setOnClickListener(v -> {
			int pbState = MediaControllerCompat.getMediaController(this).getPlaybackState().getState();
			if (pbState == PlaybackStateCompat.STATE_PLAYING) {
				playButton.setImageResource(mPlayButtonDrawableResId);
				audioButton.setVisibility(View.INVISIBLE);
				MediaControllerCompat.getMediaController(this).getTransportControls().pause();
			} else {
				playButton.setImageResource(mPauseButtonDrawableResId);
				audioButton.setVisibility(View.VISIBLE);
				MediaControllerCompat.getMediaController(this).getTransportControls().play();
			}
		});
		songNameText.setText("");
		artistNameText.setText("");
		mPopupMenu = new PopupMenu(this, audioButton);
		mPopupMenu.setOnMenuItemClickListener(item -> {
			if (item.getGroupId() == MENU_GROUP_TRACKS) {
				mService.selectTrack(item.getItemId());
				return true;
			}
			return false;
		});
		audioButton.setOnClickListener(v -> {
			TrackGroupArray trackGroupArray = mService.getTrackInfo();
			if (trackGroupArray != null && trackGroupArray.length > 0) {
				mPopupMenu.getMenu().clear();
				TrackGroup trackGroup = trackGroupArray.get(0);
				mPopupMenu.getMenu().add(MENU_GROUP_TRACKS, LocalPlayback.ADAPTIVE_TRACK_ID, Menu.NONE, getString(R.string.audio_auto));
				for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
					mPopupMenu.getMenu().add(MENU_GROUP_TRACKS, trackIndex, Menu.NONE, getString(R.string.audio_kbits, trackGroup.getFormat(trackIndex).bitrate/1000));
				}

				int selectedTrackId = mService.getSelectedTrackId();
				if (trackGroup.length > 0) {
					mPopupMenu.getMenu().setGroupCheckable(MENU_GROUP_TRACKS, true, true);
					mPopupMenu.getMenu().findItem(selectedTrackId).setChecked(true);
				}
				mPopupMenu.show();
			}
		});
	}
}
