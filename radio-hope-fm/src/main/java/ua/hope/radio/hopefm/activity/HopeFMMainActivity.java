package ua.hope.radio.hopefm.activity;

import android.os.Bundle;
import android.text.Html;
import android.widget.TextView;

import ua.hope.radio.core.activity.RadioActivity;
import ua.hope.radio.hopefm.R;

/**
 * Created by Vitalii Cherniak on 12.01.16.
 * Copyright © 2016-2017 Hope Media Group Ukraine. All rights reserved.
 */
public class HopeFMMainActivity extends RadioActivity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		mPlayButtonDrawableResId = R.drawable.ic_play_circle_outline;
		mPauseButtonDrawableResId = R.drawable.ic_pause_circle_outline;
		audioButton = findViewById(R.id.audio_controls);
		wwwButton = findViewById(R.id.www);
		playButton = findViewById(R.id.buttonPlayPause);
		statusText = findViewById(R.id.textStatus);
		songNameText = findViewById(R.id.textSongName);
		artistNameText = findViewById(R.id.textArtistName);
		TextView hopeFMText = findViewById(R.id.radioLogo);
		hopeFMText.setText(Html.fromHtml(getString(R.string.hopefm)));
		onCreated();
	}
}
