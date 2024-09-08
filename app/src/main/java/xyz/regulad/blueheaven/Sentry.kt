package xyz.regulad.blueheaven

import android.content.Context
import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import xyz.regulad.blueheaven.util.isDebuggable

fun initializeSentry(context: Context) {
    SentryAndroid.init(context) { options ->
        options.dsn = "https://ab53145edff17a0391d4b247fd48df45@o4506961510596608.ingest.us.sentry.io/4507914731192320"
        // Add a callback that will be used before the event is sent to Sentry.
        // With this callback, you can modify the event or, when returning null, also discard the event.
        options.beforeSend =
            SentryOptions.BeforeSendCallback { event: SentryEvent, hint: Hint ->
                if (SentryLevel.DEBUG == event.level || context.applicationInfo.isDebuggable()) { // don't send events if the app is in debug mode
                    null
                } else {
                    event
                }
            }
    }
}
