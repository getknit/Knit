package app.getknit.knit.crash

import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashHandlerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var original: Thread.UncaughtExceptionHandler? = null

    /** Saved and restored so a test never leaves a handler installed for the rest of the suite. */
    @Before
    fun setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original)
    }

    private class Recorder(
        private val onCall: () -> Unit = {},
    ) : Thread.UncaughtExceptionHandler {
        var calls = 0
        var thread: Thread? = null
        var throwable: Throwable? = null

        override fun uncaughtException(
            thread: Thread,
            throwable: Throwable,
        ) {
            calls++
            this.thread = thread
            this.throwable = throwable
            onCall()
        }
    }

    private fun store(dir: File = temp.root.resolve("crashes")) = CrashStore(dir) { 1_700_000_000_000L }

    @Test
    fun `captures then hands the crash to the previous handler exactly once`() {
        val store = store()
        val previous = Recorder()
        val handler = CrashHandler(store, testEnvironment(), previous)
        val boom = throwableWith("boom")

        handler.uncaughtException(Thread.currentThread(), boom)

        assertEquals(1, previous.calls)
        assertSame(Thread.currentThread(), previous.thread)
        assertSame(boom, previous.throwable)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `the report is on disk before the platform handler runs`() {
        val store = store()
        var onDiskWhenChained = false
        val previous = Recorder { onDiskWhenChained = store.list().isNotEmpty() }

        CrashHandler(store, testEnvironment(), previous).uncaughtException(Thread.currentThread(), throwableWith("boom"))

        assertTrue(onDiskWhenChained)
    }

    @Test
    fun `still chains when capture throws`() {
        val exploding = mockk<CrashStore>()
        every { exploding.record(any(), any(), any()) } throws IllegalStateException("disk on fire")
        val previous = Recorder()

        CrashHandler(exploding, testEnvironment(), previous).uncaughtException(Thread.currentThread(), throwableWith("boom"))

        assertEquals(1, previous.calls)
    }

    @Test
    fun `does not throw when there was no previous handler`() {
        CrashHandler(store(), testEnvironment(), null).uncaughtException(Thread.currentThread(), throwableWith("boom"))
    }

    @Test
    fun `a re-entrant capture is attempted once and chains once`() {
        val previous = Recorder()
        lateinit var handler: CrashHandler
        var reentered = 0
        val reentrant = mockk<CrashStore>()
        every { reentrant.record(any(), any(), any()) } answers {
            reentered++
            handler.uncaughtException(Thread.currentThread(), thirdArg())
            null
        }
        handler = CrashHandler(reentrant, testEnvironment(), previous)

        handler.uncaughtException(Thread.currentThread(), throwableWith("boom"))

        assertEquals(1, reentered)
        assertEquals(2, previous.calls)
    }

    @Test
    fun `install chains to the handler already in place and refuses to double-wrap`() {
        val previous = Recorder()
        Thread.setDefaultUncaughtExceptionHandler(previous)

        CrashHandler.install(store(), testEnvironment())
        val installed = Thread.getDefaultUncaughtExceptionHandler()
        CrashHandler.install(store(), testEnvironment())

        assertTrue(installed is CrashHandler)
        assertSame(installed, Thread.getDefaultUncaughtExceptionHandler())

        installed?.uncaughtException(Thread.currentThread(), throwableWith("boom"))
        assertEquals(1, previous.calls)
        assertFalse(store().list().isEmpty())
    }
}
