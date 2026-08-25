package app.getknit.knit.ui.addcontact

import app.getknit.knit.mesh.crypto.ContactCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mirrors `RouteInboxTest`: the single-shot handoff of a contact link from an intent to the Add-contact screen. */
class ContactCardInboxTest {
    private val link = ContactCard.url("A".repeat(260))

    @Test
    fun consumeReturnsOfferedLinkThenEmpties() {
        val inbox = ContactCardInbox()
        inbox.offer(link)
        assertEquals(link, inbox.pending.value)
        assertEquals(link, inbox.consume())
        assertNull(inbox.pending.value)
        assertNull(inbox.consume())
    }

    @Test
    fun textThatIsNotACardIsIgnored() {
        val inbox = ContactCardInbox()
        inbox.offer("hello there")
        inbox.offer("")
        assertNull(inbox.pending.value)
    }

    @Test
    fun onlyAViewOfACardLinkOrASendCarryingOneIsAContactLink() {
        assertEquals(link, contactLinkFrom("android.intent.action.VIEW", link, null))
        assertEquals("knit://c/${"B".repeat(260)}", contactLinkFrom("android.intent.action.VIEW", "knit://c/${"B".repeat(260)}", null))
        assertNull(contactLinkFrom("android.intent.action.VIEW", "https://getknit.app/", null))
        assertEquals("Add me: $link", contactLinkFrom("android.intent.action.SEND", null, "Add me: $link"))
        assertNull(contactLinkFrom("android.intent.action.SEND", null, "just a message"))
        // A short bare token shared as text is a message, never a card — it must not hijack the share flow.
        assertNull(contactLinkFrom("android.intent.action.SEND", null, "aaaaabbbbbcccccdddddeeeeef"))
        assertNull(contactLinkFrom("android.intent.action.MAIN", link, link))
    }
}
