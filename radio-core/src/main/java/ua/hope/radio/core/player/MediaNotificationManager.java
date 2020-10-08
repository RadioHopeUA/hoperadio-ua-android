/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ua.hope.radio.core.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import ua.hope.radio.core.R;
import ua.hope.radio.core.utils.ResourceHelper;

import java.util.Locale;

/**
 * Keeps track of a notification and updates it automatically for a given
 * MediaSession. Maintaining a visible notification (usually) guarantees that the music service
 * won't be killed during playback.
 */
public class MediaNotificationManager extends BroadcastReceiver {
	private final static String[] noMediaStyleManufacturers = {"huawei", "symphony teleca"};
	public final static boolean showMediaStyle = !isManufacturerBannedForMediaStyleNotifications();
	public static final String ACTION_PAUSE = "ua.hope.radio.core.player.pause";
	public static final String ACTION_PLAY = "ua.hope.radio.core.player.play";
	public static final String ACTION_STOP = "ua.hope.radio.core.player.stop";
	private static final String TAG = MediaNotificationManager.class.getSimpleName();
	private static final String CHANNEL_ID = "ua.hope.radio.core.player.MUSIC_CHANNEL_ID";
	private static final int NOTIFICATION_ID = 330;
	private static final int REQUEST_CODE = 100;
	private AudioService mService;
	private final NotificationManager mNotificationManager;
	private final int mNotificationColor;
	private MediaSessionCompat.Token mSessionToken;
	private MediaControllerCompat mController;
	private MediaControllerCompat.TransportControls mTransportControls;
	private PlaybackStateCompat mPlaybackState;
	private boolean mStarted = false;

	private static boolean isManufacturerBannedForMediaStyleNotifications() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
			for (String manufacturer : noMediaStyleManufacturers)
				if (Build.MANUFACTURER.toLowerCase(Locale.getDefault()).contains(manufacturer))
					return true;
		return false;
	}

	private final MediaControllerCompat.Callback mCb = new MediaControllerCompat.Callback() {
		@Override
		public void onPlaybackStateChanged(@NonNull PlaybackStateCompat state) {
			mPlaybackState = state;
			Log.d(TAG, "Received new playback state " + state);
			if (state.getState() == PlaybackStateCompat.STATE_STOPPED ||
					state.getState() == PlaybackStateCompat.STATE_NONE) {
				stopNotification();
			} else {
				Notification notification = createNotification().build();
				if (notification != null) {
					mNotificationManager.notify(NOTIFICATION_ID, notification);
				}
			}
		}

		@Override
		public void onMetadataChanged(MediaMetadataCompat metadata) {
			Log.d(TAG, "Received new metadata "+ metadata);
			Notification notification = createNotification()
					.setContentTitle(metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
					.setContentText(metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
					.build();
			if (notification != null) {
				mNotificationManager.notify(NOTIFICATION_ID, notification);
			}
		}

		@Override
		public void onSessionDestroyed() {
			super.onSessionDestroyed();
			Log.d(TAG, "Session was destroyed, resetting to the new session token");
			try {
				updateSessionToken();
			} catch (RemoteException e) {
				Log.e(TAG,"could not connect media controller", e);
			}
		}
	};

	public MediaNotificationManager(AudioService service) throws RemoteException {
		mService = service;
		updateSessionToken();

		mNotificationColor = ResourceHelper.getThemeColor(mService, R.attr.colorPrimary,
				Color.DKGRAY);

		mNotificationManager = (NotificationManager) mService.getSystemService(Context.NOTIFICATION_SERVICE);

		// Cancel all notifications to handle the case where the Service was killed and
		// restarted by the system.
		if (mNotificationManager != null) {
			mNotificationManager.cancelAll();
		}
	}

	/**
	 * Posts the notification and starts tracking the session to keep it
	 * updated. The notification will automatically be removed if the session is
	 * destroyed before {@link #stopNotification} is called.
	 */
	public void startNotification() {
		if (!mStarted) {
			mPlaybackState = mController.getPlaybackState();

			// The notification must be updated after setting started to true
			Notification notification = createNotification().build();
			if (notification != null) {
				mController.registerCallback(mCb);
				IntentFilter filter = new IntentFilter();
				filter.addAction(ACTION_PAUSE);
				filter.addAction(ACTION_PLAY);
				mService.registerReceiver(this, filter);

				mService.startForeground(NOTIFICATION_ID, notification);
				mStarted = true;
			}
		}
	}

	/**
	 * Removes the notification and stops tracking the session. If the session
	 * was destroyed this has no effect.
	 */
	public void stopNotification() {
		if (mStarted) {
			mStarted = false;
			mController.unregisterCallback(mCb);
			try {
				mNotificationManager.cancel(NOTIFICATION_ID);
				mService.unregisterReceiver(this);
			} catch (IllegalArgumentException ex) {
				// ignore if the receiver is not registered.
			}
			mService.stopForeground(true);
		}
	}

	public void destroy() {
		stopNotification();
		mService = null;
	}

	public void pauseNotification() {
		if (mStarted) {
			mStarted = false;
			mService.stopForeground(false);
		}
	}

	@Override
	public void onReceive(Context context, Intent intent) {
		final String action = intent.getAction();
		Log.d(TAG, "Received intent with action " + action);
		if (action != null) {
			switch (action) {
				case ACTION_PAUSE:
					mTransportControls.pause();
					break;
				case ACTION_PLAY:
					mTransportControls.play();
					break;
				default:
					Log.w(TAG, "Unknown intent ignored. Action="+ action);
			}
		}
	}

	/**
	 * Update the state based on a change on the session token. Called either when
	 * we are running for the first time or when the media session owner has destroyed the session
	 * (see {@link android.media.session.MediaController.Callback#onSessionDestroyed()})
	 */
	private void updateSessionToken() throws RemoteException {
		if (mService == null) {
			return;
		}

		MediaSessionCompat.Token freshToken = mService.getSessionToken();
		if (mSessionToken == null && freshToken != null ||
				mSessionToken != null && !mSessionToken.equals(freshToken)) {
			if (mController != null) {
				mController.unregisterCallback(mCb);
			}
			mSessionToken = freshToken;
			if (mSessionToken != null) {
				mController = new MediaControllerCompat(mService, mSessionToken);
				mTransportControls = mController.getTransportControls();
				if (mStarted) {
					mController.registerCallback(mCb);
				}
			}
		}
	}

	private PendingIntent createContentIntent() {
		Intent openUI = mService.getPackageManager().getLaunchIntentForPackage(mService.getApplication().getPackageName());
		openUI.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
		return PendingIntent.getActivity(mService, REQUEST_CODE, openUI,
				PendingIntent.FLAG_CANCEL_CURRENT);
	}

	private NotificationCompat.Builder createNotification() {
		Log.d(TAG, "createNotification");
		if (mPlaybackState == null) {
			return null;
		}

		Bitmap art = BitmapFactory.decodeResource(mService.getResources(), R.mipmap.ic_launcher);

		// Notification channels are only supported on Android O+.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			createNotificationChannel();
		}

		final NotificationCompat.Builder notificationBuilder =
				new NotificationCompat.Builder(mService, CHANNEL_ID);

		PendingIntent mStopIntent = PendingIntent.getBroadcast(mService, REQUEST_CODE,
				new Intent(ACTION_STOP).setPackage(mService.getPackageName()), PendingIntent.FLAG_CANCEL_CURRENT);

		final int playPauseButtonPosition = addActions(notificationBuilder);
		notificationBuilder
				.setLargeIcon(art)
				.setSmallIcon(R.drawable.ic_stat_audio)
				.setColor(mNotificationColor)
				.setDeleteIntent(mStopIntent)
				.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
				.setOnlyAlertOnce(true)
				.setContentIntent(createContentIntent())
				.setContentTitle("")
				.setContentText("")
				.setCategory(NotificationCompat.CATEGORY_TRANSPORT);

		if (showMediaStyle) {
			notificationBuilder.setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
					// show only play/pause in compact view
					.setShowActionsInCompactView(playPauseButtonPosition)
					.setShowCancelButton(true)
					.setCancelButtonIntent(mStopIntent)
					.setMediaSession(mSessionToken)
			);
		}


		setNotificationPlaybackState(notificationBuilder);

		return notificationBuilder;
	}

	private int addActions(final NotificationCompat.Builder notificationBuilder) {
		Log.d(TAG, "updatePlayPauseAction");

		int playPauseButtonPosition = 0;

		// Play or pause button, depending on the current state.
		final String label;
		final int icon;
		final PendingIntent intent;
		if (mPlaybackState.getState() == PlaybackStateCompat.STATE_PLAYING) {
			label = mService.getString(R.string.pause);
			icon = R.drawable.ic_pause_black_24dp;
			intent = PendingIntent.getBroadcast(mService, REQUEST_CODE,
					new Intent(ACTION_PAUSE).setPackage(mService.getPackageName()), PendingIntent.FLAG_CANCEL_CURRENT);
		} else {
			label = mService.getString(R.string.play);
			icon = R.drawable.ic_play_arrow_black_24dp;
			intent = PendingIntent.getBroadcast(mService, REQUEST_CODE,
					new Intent(ACTION_PLAY).setPackage(mService.getPackageName()), PendingIntent.FLAG_CANCEL_CURRENT);
		}
		notificationBuilder.addAction(new NotificationCompat.Action(icon, label, intent));

		return playPauseButtonPosition;
	}

	private void setNotificationPlaybackState(NotificationCompat.Builder builder) {
		Log.d(TAG, "updateNotificationPlaybackState. mPlaybackState=" + mPlaybackState);
		if (mPlaybackState == null || !mStarted) {
			Log.d(TAG, "updateNotificationPlaybackState. cancelling notification!");
			mController.unregisterCallback(mCb);
			try {
				mNotificationManager.cancel(NOTIFICATION_ID);
				mService.unregisterReceiver(this);
			} catch (IllegalArgumentException ex) {
				// ignore if the receiver is not registered.
			}
			mService.stopForeground(true);
			mStarted = false;
			return;
		}

		// Make sure that the notification can be dismissed by the user when we are not playing:
		builder.setOngoing(mPlaybackState.getState() == PlaybackStateCompat.STATE_PLAYING);
	}

	/**
	 * Creates Notification Channel. This is required in Android O+ to display notifications.
	 */
	@RequiresApi(Build.VERSION_CODES.O)
	private void createNotificationChannel() {
		if (mNotificationManager.getNotificationChannel(CHANNEL_ID) == null) {
			NotificationChannel notificationChannel =
					new NotificationChannel(CHANNEL_ID, "Radio_Channel_ID",
							NotificationManager.IMPORTANCE_LOW);

			notificationChannel.setDescription("Channel ID for Radio");

			mNotificationManager.createNotificationChannel(notificationChannel);
		}
	}
}
