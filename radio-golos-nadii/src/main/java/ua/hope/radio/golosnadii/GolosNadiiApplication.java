package ua.hope.radio.golosnadii;

import com.crashlytics.android.Crashlytics;
import com.crashlytics.android.core.CrashlyticsCore;

import io.fabric.sdk.android.Fabric;
import ua.hope.radio.core.RadioApplication;

/**
 * Created by vitalii on 1/11/18.
 */

public class GolosNadiiApplication extends RadioApplication {
	@Override
	public void onCreate() {
		super.onCreate();

		Crashlytics crashlyticsKit = new Crashlytics.Builder()
				.core(new CrashlyticsCore.Builder().disabled(BuildConfig.DEBUG).build())
				.build();
		Fabric.with(this, crashlyticsKit);
	}
}
