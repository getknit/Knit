package app.getknit.knit.crash

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.identity.DeviceTag
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.SafetyNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The positive cases build their inputs from the **real** producers (`NodeId`, `Conversations`,
 * `SafetyNumber`, `DeviceTag`), so changing an id format breaks this test rather than silently
 * un-redacting a crash report.
 */
class CrashRedactorTest {
    private fun message(text: String) = CrashRedactor.redact("java.lang.IllegalStateException: $text")

    @Test
    fun `redacts a node id`() {
        val node = NodeId.derive("seed")
        val out = message("hello reply $node != expected $node")
        assertFalse(out.contains(node))
        assertEquals("java.lang.IllegalStateException: hello reply [node] != expected [node]", out)
    }

    @Test
    fun `redacts a group id`() {
        val group = Conversations.groupIdFor(listOf(NodeId.derive("a"), NodeId.derive("b")))
        assertTrue(group.startsWith(Conversations.GROUP_ID_PREFIX))
        assertFalse(message("no such group $group").contains(group))
    }

    @Test
    fun `redacts a safety number`() {
        val safety = SafetyNumber.compute(NodeId.derive("a"), "bundleA", NodeId.derive("b"), "bundleB")
        val out = message("mismatch $safety")
        assertFalse(out.contains(safety))
        assertTrue(out.contains("[safety-number]"))
    }

    @Test
    fun `redacts hex identifiers`() {
        val tag = requireNotNull(DeviceTag.derive("android-id"))
        assertFalse(message("device tag $tag").contains(tag))
        val blobHash = "a".repeat(64)
        assertFalse(message("blob $blobHash missing").contains(blobHash))
    }

    @Test
    fun `redacts a base64 key bundle`() {
        val bundle = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE" + "x".repeat(20)
        assertFalse(message("bad bundle $bundle").contains(bundle))
    }

    @Test
    fun `keeps a spool host but drops its path and bearer token`() {
        val out = message("dial failed wss://relay.example.net/spool?k=SUPERSECRETTOKEN")
        assertTrue(out.contains("wss://relay.example.net"))
        assertFalse(out.contains("SUPERSECRETTOKEN"))
        assertFalse(out.contains("/spool"))
    }

    @Test
    fun `redacts content uris and on-device paths`() {
        assertFalse(message("null output stream for content://media/external/images/media/1234").contains("1234"))
        val out = message("cannot read /data/user/0/app.getknit.knit/files/identity.key")
        assertFalse(out.contains("identity.key"))
        assertTrue(out.contains("[path]"))
    }

    @Test
    fun `redacts non-ascii message text whole`() {
        val out = message("could not send 안녕하세요 or café")
        assertFalse(out.contains("안녕하세요"))
        assertTrue(out.contains("[text]"))
    }

    @Test
    fun `redacts an eng build username in a fingerprint`() {
        val out = message("lineage_shiba-userdebug eng.walter.20260820 boot")
        assertFalse(out.contains("walter"))
        assertTrue(out.contains("eng.[user].[ts]"))
    }

    @Test
    fun `keeps diagnostic messages that carry no identifiers`() {
        assertEquals(
            "java.lang.IllegalArgumentException: maxChunk must be positive, was -1",
            CrashRedactor.redact("java.lang.IllegalArgumentException: maxChunk must be positive, was -1"),
        )
        assertEquals(
            "java.lang.IllegalStateException: unexpected passphrase length 17",
            CrashRedactor.redact("java.lang.IllegalStateException: unexpected passphrase length 17"),
        )
    }

    @Test
    fun `leaves stack frames byte-identical`() {
        val frames =
            listOf(
                "\tat app.getknit.knit.mesh.bluetooth.BluetoothMeshTransport.hello(BluetoothMeshTransport.kt:552)",
                "\tat a.b.c.d(SourceFile:1)",
                "\t... 12 more",
            )
        assertEquals(frames.joinToString("\n"), CrashRedactor.redact(frames.joinToString("\n")))
    }

    @Test
    fun `never scrubs the exception class name`() {
        val out = CrashRedactor.redact("Caused by: java.io.IOException: /data/local/thing")
        assertTrue(out.startsWith("Caused by: java.io.IOException: "))
        assertFalse(out.contains("/data/local/thing"))
    }

    @Test
    fun `is idempotent with and without secrets`() {
        val secrets = KnownSecrets(setOf("Ada Lovelace"))
        val raw = "java.lang.IllegalStateException: Ada Lovelace at /data/x with ${NodeId.derive("z")}"
        val once = CrashRedactor.redact(raw)
        assertEquals(once, CrashRedactor.redact(once))
        val twice = CrashRedactor.redact(raw, secrets)
        assertEquals(twice, CrashRedactor.redact(twice, secrets))
    }

    @Test
    fun `redacts known names but respects word boundaries`() {
        val secrets = KnownSecrets(setOf("Bob", "Ann", "Anna", "Al"))
        val out = CrashRedactor.redact("java.lang.IllegalStateException: bob and Bobcat and Anna and Al", secrets)
        assertTrue(out.contains("[name] and Bobcat"))
        assertTrue(out.contains("[name] and Al"))
        assertFalse(out.contains("Anna"))
    }

    @Test
    fun `never applies the name pass to stack frames`() {
        val frame = "\tat app.getknit.knit.ui.chat.ChatViewModel.send(ChatViewModel.kt:12)"
        assertEquals(frame, CrashRedactor.redact(frame, KnownSecrets(setOf("Chat"))))
    }

    @Test
    fun `caps a long message but keeps the frames after it`() {
        val frame = "\tat app.getknit.knit.mesh.MeshRouter.route(MeshRouter.kt:91)"
        // Spaced words, not one long run: a 5,000-char unbroken token is base64-shaped and the [b64]
        // rule would collapse it long before the length cap ever applied.
        val chatter = "hello world ".repeat(500)
        val out = CrashRedactor.redact("java.lang.IllegalStateException: $chatter\n$frame")
        assertTrue(out.lines().first().length < CrashRedactor.MAX_MESSAGE_CHARS * 2)
        assertTrue(out.contains("...(+"))
        assertEquals(frame, out.lines().last())
    }

    @Test
    fun `framesOnly drops every message and keeps every frame`() {
        val trace =
            """
            java.lang.IllegalStateException: something secret
            ${"\t"}at app.getknit.knit.mesh.MeshRouter.route(MeshRouter.kt:91)
            Caused by: java.io.IOException: also secret
            ${"\t"}... 3 more
            """.trimIndent()
        val out = CrashRedactor.framesOnly(trace)
        assertFalse(out.contains("secret"))
        assertTrue(out.contains("java.lang.IllegalStateException"))
        assertTrue(out.contains("Caused by: java.io.IOException"))
        assertTrue(out.contains("at app.getknit.knit.mesh.MeshRouter.route(MeshRouter.kt:91)"))
    }
}
