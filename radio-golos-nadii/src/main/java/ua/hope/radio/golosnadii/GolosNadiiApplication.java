package ua.hope.radio.golosnadii;

import com.google.firebase.crashlytics.FirebaseCrashlytics;

import ua.hope.radio.core.RadioApplication;

/**
 * Created by vitalii on 1/11/18.
 */

public class GolosNadiiApplication extends RadioApplication {
	@Override
	public void onCreate() {
		super.onCreate();
		FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG);
	}
}
