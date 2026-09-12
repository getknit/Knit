---
id: "2026-09.zapp"
slug: a-photo-less-group-avatar-is-its-members-faces
title: "A photo-less group avatar is its members' faces, drawn the same in the shade"
date: 2026-09-12
topics: [ui, identity, notifications]
---

# ADR 2026-09.zapp — A photo-less group avatar is its members' faces, drawn the same in the shade

Status: Accepted (2026-09-12) — extends ADR 2026-09.j8c7 from people to groups.

**What was observed.** ADR 2026-09.j8c7 gave every photo-less *person* a node-id-keyed tint and made the
notification shade draw the same face, but groups were left where they were: `GroupAvatar` was a grey
`Icons.Filled.Group` on `secondaryContainer`, the chat list did not even use it (an inline `CircleGlyph`
copy), and the shade drew a photo-less group as a *tinted initial of its title*
(`fallbackAvatar` → `letterAvatar`). So a chat list with three photo-less groups was three identical grey
discs, and the same group was a grey people-glyph in the list and, say, a coral "T" in the shade — the exact
list-versus-shade split j8c7 had just closed for people, plus a "no identity" look for the one kind of
conversation that has the most identity behind it.

**What changed.** A photo-less group's avatar is a **segmented disc of its other members' faces**. The pick
is one pure function, `groupFaceIds` in `data/message/GroupFaces.kt` (a sibling of `groupTitle`, the
existing exclude-self rule): roster minus self, de-duplicated, **sorted by node id**, the first four. Roster
order was rejected because it is whatever the creator typed and differs by phone; node-id order puts the
same face in the same cell everywhere. Fewer than two others — a two-member group, or a roster we do not yet
hold — yields no cluster at all, and the disc falls back to the people glyph on a tint **keyed on the group
id** (`knitColors.avatarTint(groupId)`), the j8c7 treatment, never the grey. A one-face cluster was rejected
because it would be indistinguishable from that person's DM row; the glyph says "group", and the DM already
shows the face. Past four the rest are dropped silently: a "+N" cell was rejected as noise, and the member
count already sits under the group name in the chat header.

The cells come from one pure function too, `clusterCells(count, gap)` in `ui/util/ClusterGeometry.kt`,
in unit-square fractions: 2 → left/right halves, 3 → left half plus a right column split top/bottom,
4 → quadrants, each renderer placing `faces[i]` in `cells[i]` and never re-sorting. The Compose `GroupAvatar`
lays its `FaceCell`s out with `Modifier.offset(size * cell.left, …).size(size * cell.width, …)` — the
JVM-tested geometry *is* the layout, with no measure policy of its own to drift from it, and Coil's
`AsyncImage(ContentScale.Crop)` centre-crops a photo inside its cell for free. A `Canvas` would have lost
Coil; nested `Row`/`Column`s would have matched the geometry only by coincidence. A cell with no photo draws
the member's initial on the member's own `AvatarTint` — the colour they wear alone — through the same
`AvatarInitial`, now `internal`, scaled on the cell's short side. The seam is `1.5.dp` in-app and **transparent**,
so whatever sits behind the disc shows through rather than a guessed surface colour; in the shade it is
painted `SurfaceLight`, because the emulator showed that an adaptive icon's transparency flattens to black
there — the same fixed-to-light pinning `roomAvatar` uses. The geometry takes the seam as a fraction
(`CLUSTER_GAP / size`) because a fixed fraction would draw 2.9dp at 96dp and 1.1dp at 36dp, and a dp has no
meaning in a bitmap the shade scales itself (there it is `CLUSTER_GAP_PX / AVATAR_PX`).

**The shade draws the same picture.** `NotifConversation` gains `faces: List<NotifFace>` — the same pick,
resolved to bytes by `InboundPipeline.resolveConversation`, which already held the roster, the collision-aware
labels, the peer rows and the blob store; the reads (at most four rows, four blobs) are skipped when the group
has a photo, since the photo covers them. `MessageNotifier` keeps them in `ConvState` beside `avatarBytes`
so an inline reply's re-render — which rebuilds from `ConvState` alone — still draws the cluster, and the
painting moved to `NotificationAvatars` (`clusterAvatar`, `groupGlyphAvatar`) so `MessageNotifier` stays
under detekt's `LargeClass` line. The people glyph became an XML vector (`ic_group_glyph.xml`, Material's
own, Apache-2.0) surfaced as `KnitIcons.Group`, for the reason `KnitIcons` already documents: one resource
both Compose and a `Canvas` can draw. The notifier's private copy of `avatarInitial` is gone; it imports the
one in `ui/components/Avatar.kt`.

Every surface derives the faces in the ViewModel from state it already had — `ConversationTitle.faces`
feeds the chat list, the share picker and search from one line in `conversationTitle`; the chat header,
group details and the requests inbox each call `groupFaces(members, me, directory)` inside their existing
combines — so no flow was added (the chat list's two combines are at the five-arity cap on purpose) and no
query was. Group details in particular derives from the *raw* roster, not its self-first/online-first rows:
that order is the list's, not the disc's.

**What it costs, and what it does not cover.** Up to four Coil requests per photo-less group row instead
of none, each cell-sized; a cell request may miss the 52dp DM-row cache entry for the same peer, one small
extra decode. Up to four peer-row and blob reads per photo-less group notification. The decoded faces share
`NotificationAvatars`' 2 MB LRU with the sender icons (keyed on the same bytes, so not doubled); the composed
256² cluster is not cached — one draw of four cached bitmaps per post. If a busy four-face group plus four
distinct DM senders ever thrash it, `AVATAR_CACHE_BYTES` is a one-liner. A cell in the 36dp chat header is
about 17dp, so its initial is about 8.5sp; `AvatarInitial.initialFraction` is the knob if that reads too
small on a device, and it must not move `Avatar`'s own 0.5. No wire, DB or DataStore change.

The trap for the next person: **the renderers trust `faces` order.** The sort lives only in `groupFaceIds`;
a renderer that re-sorts, or a caller that builds the list by hand, breaks the list-versus-shade agreement
silently. `GroupFacesTest` pins the pick (self out, node-id order, cap, floor, duplicates, unknown ids);
`ClusterGeometryTest` pins the cells (inside the square, disjoint, seam-apart, tiling at zero gap, mirror
symmetry, out-of-range counts refused); `InboundPipelineTest` proves a photo-less group's notification
carries its faces in node-id order with bytes where a blob exists, and a group with a photo carries none;
one ViewModel test per surface proves the row carries the same list; `ChatScreenContentTest` proves the
cluster branch leaves the tappable `chat_group_avatar` root in place, which five device tests wait on.
