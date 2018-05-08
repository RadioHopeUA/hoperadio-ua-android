package ua.hope.radio.hopefm;

import android.app.Application;

import com.crashlytics.android.Crashlytics;

import io.fabric.sdk.android.Fabric;

/**
 * Created by Vitalii Cherniak on 12.01.16.
 * Copyright © 2016-2017 Hope Media Group Ukraine. All rights reserved.
 */
public class HopeFMApplication extends Application {
	@Override
	public void onCreate() {
		super.onCreate();
		if (BuildConfig.BUILD_TYPE.equals("release")) {
			Fabric.with(this, new Crashlytics());
		}
	}
}
