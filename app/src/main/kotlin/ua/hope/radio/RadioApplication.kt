package ua.hope.radio

import androidx.multidex.MultiDexApplication
import com.google.firebase.analytics.FirebaseAnalytics
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.dsl.module
import timber.log.Timber
import ua.hope.radio.player.PlayerViewModel
import ua.hope.radio.utils.CrashlyticsCrashReportingTree

class RadioApplication : MultiDexApplication() {
    private val koinModule = module {
        viewModel { PlayerViewModel() }
    }

    override fun onCreate() {
        super.onCreate()

        configureTimber()
        startKoin{
            modules(koinModule)
        }
        FirebaseAnalytics.getInstance(this).setAnalyticsCollectionEnabled(!BuildConfig.DEBUG)
    }

    private fun configureTimber() {
        if (BuildConfig.BUILD_TYPE == "debug") {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(CrashlyticsCrashReportingTree())
        }
    }
}
