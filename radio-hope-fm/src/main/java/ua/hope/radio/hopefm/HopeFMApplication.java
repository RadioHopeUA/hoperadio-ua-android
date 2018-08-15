package ua.hope.radio.hopefm;

import com.crashlytics.android.Crashlytics;
import com.crashlytics.android.core.CrashlyticsCore;

import io.fabric.sdk.android.Fabric;
import ua.hope.radio.core.RadioApplication;

/**
 * Created by Vitalii Cherniak on 12.01.16.
 * Copyright © 2016-2017 Hope Media Group Ukraine. All rights reserved.
 */
public class HopeFMApplication extends RadioApplication {
	@Override
	public void onCreate() {
		super.onCreate();

		Crashlytics crashlyticsKit = new Crashlytics.Builder()
				.core(new CrashlyticsCore.Builder().disabled(BuildConfig.DEBUG).build())
				.build();
		Fabric.with(this, crashlyticsKit);
	}
}
