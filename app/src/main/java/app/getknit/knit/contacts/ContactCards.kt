package app.getknit.knit.contacts

import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.crypto.ContactCard
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.spool.SpoolUrl
import kotlinx.coroutines.flow.first

/**
 * Mints this device's **contact card** (docs/CONTACT_CARD.md): the identity bundle, the display name,
 * and — only while the Internet plane is on — the relays this device uses, so a far-away contact can see
 * where to meet. Relay hints never carry a bearer token (`?k=`): a card is forwardable, and a private
 * spool's token would ride with it. Android-free (the signer is injected) so it drives under plain JVM.
 */
class ContactCards(
    private val identity: Identity,
    private val settings: SettingsStore,
    private val signRaw: (ByteArray) -> ByteArray,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** A freshly minted card in its two link forms. */
    class Minted(
        val compact: String,
        val url: String,
        val schemeUrl: String,
    )

    suspend fun mint(): Minted {
        val bundle = checkNotNull(PublicKeyBundle.decode(identity.publicKeyBundle())) { "own bundle must decode" }
        val spools =
            if (settings.spoolEnabled.first()) {
                settings.spoolUrls
                    .first()
                    .filterNot { SpoolUrl.redact(it) != it }
                    .sorted()
                    .take(ContactCard.MAX_SPOOLS)
            } else {
                emptyList()
            }
        val compact =
            ContactCard.encode(
                bundle = bundle,
                name = settings.displayName.first(),
                spools = spools,
                issuedAt = clock(),
                sign = signRaw,
            )
        return Minted(compact = compact, url = ContactCard.url(compact), schemeUrl = ContactCard.schemeUrl(compact))
    }
}
