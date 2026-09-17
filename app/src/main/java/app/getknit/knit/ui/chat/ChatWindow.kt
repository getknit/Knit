package app.getknit.knit.ui.chat

/**
 * How much of a thread the chat screen reads at once.
 *
 * A room runs to its 2,000-row retention cap and an accepted thread has no cap at all, and the screen used to
 * select every row, fold each into a bubble, and redo both on every write to the messages table. It now reads
 * the newest [INITIAL] and grows by [PAGE] as the reader scrolls back into history.
 */
object ChatWindow {
    /** Enough to fill a tall phone several times over, so the reader can fling before a page is fetched. */
    const val INITIAL = 60

    /** Added per [ChatViewModel.loadOlder]. */
    const val PAGE = 100

    /**
     * Ceiling on the window, so following a reply quote deep into history can't quietly restore the
     * unbounded read. This is the screen's own policy, not a mirror of any retention constant: an accepted
     * thread is never trimmed, so it is the one bound on the largest read the screen can ever issue, however
     * long the thread has lived. A quote deeper than this is not followed (`ChatViewModel` clamps both the
     * page-in and the reveal to it).
     */
    const val MAX = 5_000

    /**
     * Fire [ChatViewModel.loadOlder] once the reader is this close to the oldest loaded row. It is a
     * prefetch, but it is also what hides the window's one visible artifact: a full window drops its oldest
     * row when a new message lands, so a reader parked exactly at the top would watch a bubble disappear.
     * Growing before they get there means there is never a row at the top to lose.
     */
    const val LOAD_AHEAD = 10

    /**
     * Offer the way back to the newest message once the reader's bottom row is this far above it. Large
     * enough that a message or typing peer landing beneath a reader one row up (the `<= 1` gate in
     * `ChatScreenContent`) cannot flash it; counted in list items, so a typing indicator or a prepend
     * shifts it by one, which is right: either is something new at the bottom.
     */
    const val AWAY_FROM_NEWEST = 8
}
