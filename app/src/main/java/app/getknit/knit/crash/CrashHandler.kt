package app.getknit.knit.crash

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures an uncaught exception to [CrashStore] and then hands it straight on to whatever handler was
 * already installed.
 *
 * **Delegating to [previous] is the hard requirement.** The platform's own handler is what shows the
 * "Knit keeps stopping" dialog and kills the process; skipping it would leave a hung, unkilled app. So
 * capture is wrapped in [runCatching] and the delegation lives in a `finally`, with the failure log
 * itself guarded — there is no path through [uncaughtException] that does not reach [previous].
 *
 * Installed from `KnitApplication.onCreate` *before* Koin starts, so a crash inside startup — a
 * keystore fault, an `.so` that will not load, a Koin graph that throws while building the database —
 * is still captured. That is also why it takes an already-built store rather than resolving one.
 *
 * Does not see native crashes (Tink, SQLCipher, the tflite moderator), ANRs, or a deliberate
 * `Process.killProcess` such as `WifiAwareTransport`'s NAN-wedge self-restart. The UI says so.
 */
class CrashHandler(
    private val store: CrashStore,
    private val environment: CrashEnvironment,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    /**
     * Latched, never reset. If capture itself throws and re-enters, or a second thread crashes while
     * we are writing, the later entry falls straight through to [previous] — the process is dying and
     * there is no state worth salvaging.
     */
    private val capturing = AtomicBoolean(false)

    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        try {
            if (capturing.compareAndSet(false, true)) {
                runCatching { store.record(environment, thread.name, throwable) }
                    .onFailure { runCatching { Log.w(TAG, "crash capture failed", it) } }
            }
        } finally {
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val TAG = "CrashHandler"

        /**
         * Installs the handler, chaining to the current default. A second call is a no-op: without that
         * guard a re-install would wrap us in ourselves and write the same report twice.
         */
        fun install(
            store: CrashStore,
            environment: CrashEnvironment,
        ) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            if (previous is CrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(store, environment, previous))
        }
    }
}
