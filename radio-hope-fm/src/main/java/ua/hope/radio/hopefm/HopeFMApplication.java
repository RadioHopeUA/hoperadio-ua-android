package ua.hope.radio.hopefm;

import com.google.firebase.crashlytics.FirebaseCrashlytics;

import ua.hope.radio.core.RadioApplication;

/**
 * Created by Vitalii Cherniak on 12.01.16.
 * Copyright © 2016-2017 Hope Media Group Ukraine. All rights reserved.
 */
public class HopeFMApplication extends RadioApplication {
	@Override
	public void onCreate() {
		super.onCreate();

		FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG);
	}
}
