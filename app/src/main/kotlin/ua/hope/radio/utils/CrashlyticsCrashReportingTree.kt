package ua.hope.radio.utils

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import timber.log.Timber

class CrashlyticsCrashReportingTree : Timber.Tree() {
    private val crashlytics = FirebaseCrashlytics.getInstance()

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority <= Log.INFO) {
            return
        }

        val exception = t ?: Exception(message)

        crashlytics.setCustomKey(CRASHLYTICS_KEY_PRIORITY, priority)
        if (tag != null) crashlytics.setCustomKey(CRASHLYTICS_KEY_TAG, tag)
        crashlytics.setCustomKey(CRASHLYTICS_KEY_MESSAGE, message)
        crashlytics.recordException(exception)
    }

    companion object {
        private const val CRASHLYTICS_KEY_PRIORITY = "priority"
        private const val CRASHLYTICS_KEY_TAG = "tag"
        private const val CRASHLYTICS_KEY_MESSAGE = "message"
    }
}
