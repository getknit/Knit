package app.getknit.knit.ui.relay

import app.getknit.knit.mesh.crypto.ContactCard
import app.getknit.knit.mesh.spool.RelayInvite
import app.getknit.knit.ui.addcontact.contactLinkFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mirrors `ContactCardInboxTest`: the single-shot handoff of a relay invite from an intent to the relays screen. */
class RelayInviteInboxTest {
    private val link = RelayInvite.url(RelayInvite.mint("wss://home.example.org/spool/v1?k=t0ken"))

    @Test
    fun consumeReturnsOfferedLinkThenEmpties() {
        val inbox = RelayInviteInbox()
        inbox.offer(link)
        assertEquals(link, inbox.pending.value)
        assertEquals(link, inbox.consume())
        assertNull(inbox.pending.value)
        assertNull(inbox.consume())
    }

    @Test
    fun textThatIsNotAnInviteIsIgnored() {
        val inbox = RelayInviteInbox()
        inbox.offer("hello there")
        inbox.offer("")
        inbox.offer(ContactCard.url("A".repeat(260)))
        assertNull(inbox.pending.value)
    }

    @Test
    fun onlyAViewOfAnInviteLinkOrASendCarryingOneIsARelayInvite() {
        val scheme = RelayInvite.schemeUrl(RelayInvite.mint("wss://home.example.org/spool/v1"))
        assertEquals(link, relayInviteFrom("android.intent.action.VIEW", link, null))
        assertEquals(scheme, relayInviteFrom("android.intent.action.VIEW", scheme, null))
        assertNull(relayInviteFrom("android.intent.action.VIEW", "https://getknit.app/", null))
        assertEquals("Join my relay: $link", relayInviteFrom("android.intent.action.SEND", null, "Join my relay: $link"))
        assertNull(relayInviteFrom("android.intent.action.SEND", null, "just a message"))
        assertNull(relayInviteFrom("android.intent.action.MAIN", link, link))
    }

    @Test
    fun aCardLinkIsNotAnInviteAndAnInviteLinkIsNotACard() {
        val card = ContactCard.url("A".repeat(260))
        assertNull(relayInviteFrom("android.intent.action.VIEW", card, null))
        assertNull(contactLinkFrom("android.intent.action.VIEW", link, null))
        assertNull(contactLinkFrom("android.intent.action.SEND", null, "Join my relay: $link"))
    }
}
