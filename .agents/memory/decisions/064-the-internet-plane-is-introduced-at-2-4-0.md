---
id: "064"
slug: the-internet-plane-is-introduced-at-2-4-0
title: "The Internet plane is introduced at 2.4.0: the switch that hid it becomes the user's own"
date: 2026-08-30
topics: [spool, release, ui]
---

# ADR 064 — The Internet plane is introduced at 2.4.0: the switch that hid it becomes the user's own

ADR 031 hid the finished spool plane behind `BuildConfig.INTERNET_PLANE`, false in release and staging,
so 2.3.0 and 2.3.1 shipped every class of the plane and no way in. 2.4.0 flips that default to true. It
is a one-line change in `app/build.gradle.kts` precisely because ADR 031 refused to make it a code
strip: the classes were always in the APK, R8 only pruned the `if (INTERNET_PLANE)` branches, and the
unit suite — which builds debug — has been exercising the real plane throughout. There is no wire, DB,
protocol or vector change here, and `docs/SPOOL_PROTOCOL.md` needs no amendment: what shipped dark and
what ships lit are the same client.

**Visible is not enabled, and that distinction is the whole reason this is safe to ship.** The flag
gates the ways *in* — the Profile row, the `relays` route, the one-shot default-spool seed, and
`SettingsStore.spoolEnabled`'s ability to mean what the user stored. The user-facing default did not
move: `spoolEnabled` still defaults false, behind the consent sheet, so a fresh 2.4.0 install seeds
`wss://lax.spool.getknit.app/spool/v1` into a list, renders the relay screen, and **opens no socket**.
Someone who never visits Settings has an app that behaves exactly like 2.3.1. That is what makes the
blast radius of a mistake here the set of users who read a disclosure and opted in, rather than
everyone.

**The precondition was partly met, and shipping anyway is the decision.** The roadmap conditioned the
flip on "the device trials below". What is done: the plane has run on lab hardware against a real spool
continuously enough to surface and close a convergence bug that only appears at that scale — ADR 062's
920 `RATCHET_DUPLICATE` on an idle Pixel, every scope stuck at `converged = false`, fixed by spec §9.6
and re-verified with local == spool across 18 minutes. That is stronger evidence than a scripted trial
would have been, because nobody was steering it. What is **not** done, and rides as known risk: the
group two-island trial (two islands, one spool, a departure rotating the scope), the attachment-deferral
trial (co-located send defers, separation uploads within one heal round), and the contact-card intro
trial (both import, out of range, one shared spool). All three are *group*- and *attachment*-shaped;
the DM path is the one with hours behind it. A failure in any of them degrades to "the radios carry it,
the relay does not" — the plane is additive to custody, never a replacement — which is the same outcome
as leaving the feature off, for the subset of users who turned it on.

**LoRa stays dark, and the two flags are deliberately no longer symmetric.** They were written as a
pair and read as one policy; from 2.4.0 they encode different amounts of evidence. `LORA_PLANE` still
resolves false in release/staging because its two device trials (the four-device two-pocket run and the
airtime-shaping three-phone run in `.agents/context/lora-bridge.md`) are still owed, and unlike a spool
failure a LoRa failure is *loud* — a saturated shared channel degrades the plane for everyone in radio
range, not just for the user who enabled it. The build file now says so at both sites, so the asymmetry
cannot be read as an oversight and "tidied" back into symmetry.

**The store copy was the real work, and it was wrong in a way only this flip exposes.** The Play /
F-Droid description asserted "Knit contacts no server, has no backend, and sends nothing off your
device" and explained the `INTERNET` permission as "not to reach the network". Both were true of every
shipped build up to 2.3.1 and false the moment a user can switch a relay on. The permissions paragraph
now gives both reasons, and a new OPTIONAL INTERNET RELAYS section states the opt-in and the
disclosure's contents. The description sits at Play's 4000-character cap — it was 3944 before, which is
not slack — so ~1000 characters of existing copy were tightened to make room; that budget, not the
writing, is what makes this a per-release cost. The same claim lived in README's permission FAQ and was
corrected there too.

`-PinternetPlane=false` still produces the dark build, and every consumer still reads the plane's
liveness through `spoolEnabled` alone (ADR 031, ADR 063), so re-hiding the feature remains one flag.
