package app.getknit.knit.data.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * The `KIND_*` registry is append-only and every value is stored in a message row, so two constants
 * sharing a value make their rows indistinguishable — and Kotlin will not say so: a duplicate
 * `const val` compiles clean and the `when` branch it shadows is silently dead (issue #41, where a
 * key-pin-refused notice rendered as a group rejoin). This reads the constants off the class by
 * reflection so a new kind is covered without anyone remembering to list it here.
 */
class MessageKindTest {
    @Test
    fun everyKindHasItsOwnValue() {
        val kinds = kindConstants()
        assertTrue("expected a KIND_ registry on MessageEntity, found $kinds", kinds.size >= 2)
        val clashes = kinds.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
        assertEquals("KIND_ constants sharing a value: $clashes", emptyMap<Int, List<String>>(), clashes)
    }

    /** A companion `const val` compiles to a static field on the outer class. */
    private fun kindConstants(): Map<String, Int> =
        MessageEntity::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.name.startsWith("KIND_") && it.type == Int::class.javaPrimitiveType }
            .associate { it.name to it.getInt(null) }
}
