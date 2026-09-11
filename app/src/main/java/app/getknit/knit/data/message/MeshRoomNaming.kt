package app.getknit.knit.data.message

/**
 * The Meshtastic room's channel name: the connected board's primary channel where there is one, else the
 * channel the newest heard post was tagged with, else null — the caller supplies the generic title. Pure, and
 * shared by the thread header and the chat-list row so the two cannot drift apart. [liveChannel] is what the
 * board calls slot 0 now (`LoraFacts.primaryChannel`), which is what a post typed here would go out on;
 * [newestChannel] is what the room *was* when the board last spoke, and stands in while it is away — the
 * thread header reads it off its window with [newestOriginChannel], the chat list asks the database
 * (`MessageDao.observeNewestOriginChannel`).
 */
fun meshRoomChannel(
    liveChannel: String?,
    newestChannel: String?,
): String? = liveChannel?.takeIf { it.isNotBlank() } ?: newestChannel?.takeIf { it.isNotBlank() }

/**
 * The channel the newest heard post in [messages] (oldest-first) was tagged with, or null when none was —
 * a window's stand-in for the DAO scalar. Our own typed posts carry no channel and are stepped over.
 */
fun newestOriginChannel(messages: List<MessageEntity>): String? = messages.lastOrNull { !it.originChannel.isNullOrBlank() }?.originChannel
