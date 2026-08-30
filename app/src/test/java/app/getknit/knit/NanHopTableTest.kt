package app.getknit.knit

import app.getknit.knit.mesh.wifiaware.NanHopTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [NanHopTable] — the coordination plane's handle → neighbor table, i.e. the rule that a fast
 * frame is a sighting of the hop that delivered it and never of the author its envelope names (ADR 061).
 */
class NanHopTableTest {
    /** Stand-in for the transport's (session, handle) key; the table treats it as opaque. */
    private data class Key(
        val session: String,
        val handle: Int,
    )

    @Test
    fun aFrameIsASightingOfTheHopThatDeliveredIt() {
        val table = NanHopTable<Key>()
        table.learn(Key("subscribe", 7), "near-neighbor")
        // A custody frame authored by a peer miles away, re-fanned by the neighbor on handle 7: the table is
        // never given the author, so the only node it can name is the neighbor that actually sent the bytes.
        assertEquals("near-neighbor", table.hopFor(Key("subscribe", 7)))
    }

    @Test
    fun anUnnamedHandleIsNoSightingAtAll() {
        val table = NanHopTable<Key>()
        table.learn(Key("subscribe", 7), "near-neighbor")
        assertNull("same handle id on the other session", table.hopFor(Key("publish", 7)))
        assertNull("handle no cue has named", table.hopFor(Key("subscribe", 8)))
    }

    @Test
    fun aLaterCueOnTheSameHandleReLearnsIt() {
        val table = NanHopTable<Key>()
        table.learn(Key("publish", 3), "old-owner")
        table.learn(Key("publish", 3), "new-owner")
        assertEquals("new-owner", table.hopFor(Key("publish", 3)))
    }

    @Test
    fun forgettingAPeerDropsEveryHandleItOwnsAndNothingElse() {
        val table = NanHopTable<Key>()
        table.learn(Key("subscribe", 7), "gone")
        table.learn(Key("publish", 2), "gone")
        table.learn(Key("publish", 5), "still-here")
        table.forget("gone")
        assertNull(table.hopFor(Key("subscribe", 7)))
        assertNull(table.hopFor(Key("publish", 2)))
        assertEquals("still-here", table.hopFor(Key("publish", 5)))
    }

    @Test
    fun clearForgetsEveryHandle() {
        val table = NanHopTable<Key>()
        table.learn(Key("subscribe", 7), "a")
        table.learn(Key("publish", 9), "b")
        table.clear()
        assertNull(table.hopFor(Key("subscribe", 7)))
        assertNull(table.hopFor(Key("publish", 9)))
    }
}
