package ua.hope.radio.golosnadii;

import com.crashlytics.android.Crashlytics;

import io.fabric.sdk.android.Fabric;
import ua.hope.radio.core.RadioApplication;

/**
 * Created by vitalii on 1/11/18.
 */

public class GolosNadiiApplication extends RadioApplication {
	@Override
	public void onCreate() {
		super.onCreate();
		if (BuildConfig.BUILD_TYPE.equals("release")) {
			Fabric.with(this, new Crashlytics());
		}
	}
}
