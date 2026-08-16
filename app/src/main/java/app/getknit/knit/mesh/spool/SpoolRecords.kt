package app.getknit.knit.mesh.spool

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * The client↔spool record layer (docs/SPOOL_PROTOCOL.md §7): one CBOR record per WebSocket binary
 * message, multiplexed over a single WSS connection per spool. This is deliberately *not* mesh wire —
 * no `WireEnvelope`, no frame signature; everything a spool stores or serves is an opaque sealed blob
 * (`ScopeCrypto`), and these records only move blobs, digests, and bounds around. Evolution follows
 * the house wire discipline restated in the spec: additive-only fields (nullable/defaulted), a plain
 * string discriminator [SpoolRecordHead.t] (unknown `t` is skipped, unknown fields are ignored), and
 * an append-only error-code registry. API-only until the reference spool (M2) and `ScopeSync` (M3).
 *
 * Ids ride raw: [SpoolCodec] sets `alwaysUseByteString`, so every `ByteArray` — including list
 * elements, which the per-property `@ByteString` annotation cannot reach — encodes as a CBOR byte
 * string. Scope ids and blob ids are 32 bytes, digests 8 bytes big-endian (`ScopeCrypto.digestBytes`).
 */
object SpoolRecordType {
    const val HELLO = "hello"
    const val SUB = "sub"
    const val DIGEST = "digest"
    const val LIST = "list"
    const val PULL = "pull"
    const val BLOB = "blob"
    const val PUSH = "push"
    const val EVENT = "event"
    const val OK = "ok"
    const val ERR = "err"
}

/** Error codes a spool may return; the registry is append-only (unknown codes are terminal-generic). */
object SpoolErrCode {
    const val VERSION = "version"
    const val POW = "pow"
    const val TOMBSTONED = "tombstoned"
    const val QUOTA = "quota"
    const val TOO_LARGE = "too_large"
    const val BAD_ID = "bad_id"
    const val RATE = "rate"
    const val NOT_SUBSCRIBED = "not_subscribed"
    const val MALFORMED = "malformed"
    const val INTERNAL = "internal"
}

/** WebSocket close codes for failures that happen before or outside the record layer. */
object SpoolCloseCode {
    const val MALFORMED = 4000
    const val AUTH = 4001
    const val VERSION = 4002
    const val ABUSE = 4003
}

/** Record-layer protocol version negotiated in HELLO. */
const val SPOOL_RECORD_VERSION = 1

/** Decode-first view of any record: just the discriminator (unknown keys are ignored). */
@Serializable
class SpoolRecordHead(
    val t: String,
)

/** Hard limits a spool advertises in its HELLO. */
@Serializable
class SpoolLimits(
    val maxBlob: Int,
    val maxRecord: Int,
    val maxScopes: Int,
    val maxPull: Int,
    val maxFramesCap: Int,
    val maxTtlMs: Long,
)

/** A mined `SpoolPow` stamp: counter [n] for UTC day [d]. */
@Serializable
class PowStamp(
    val n: Long,
    val d: Long,
)

/** Per-scope retention bounds — declared by the client at SUB, echoed (as applied) in DIGEST. */
@Serializable
class ScopeBounds(
    val maxFrames: Int,
    val ttlMs: Long,
    val maxBlob: Int,
)

/** One scope subscription: the scope id, the bounds the members converged on, an optional PoW stamp. */
@Serializable
class ScopeSub(
    val scope: ByteArray,
    val bounds: ScopeBounds,
    val pow: PowStamp? = null,
)

/**
 * First record in each direction. Spool→client: [v] is the highest supported version, with [min],
 * [limits], and [powBits] (0 = PoW off). Client→spool: [v] is the chosen version, everything else
 * omitted — a client identifies itself no further than that.
 */
@Serializable
class SpoolHello(
    val t: String,
    val v: Int,
    val min: Int? = null,
    val limits: SpoolLimits? = null,
    val powBits: Int? = null,
)

/** Client→spool: subscribe to scopes (responded to with one DIGEST or scoped ERR each). */
@Serializable
class SpoolSub(
    val t: String,
    val q: Long,
    val subs: List<ScopeSub>,
)

/** Spool→client: the anti-entropy cue — current digest, live count, applied bounds, fullness. */
@Serializable
class SpoolDigest(
    val t: String,
    val scope: ByteArray,
    val digest: ByteArray,
    val count: Int,
    val full: Boolean,
    val bounds: ScopeBounds,
)

/**
 * The id exchange behind a digest mismatch. Client→spool: [q] and [scope] only. Spool→client: the
 * live [blobIds] plus current [tombstones], so the client can diff and skip dead pushes.
 */
@Serializable
class SpoolList(
    val t: String,
    val q: Long,
    val scope: ByteArray,
    val blobIds: List<ByteArray>? = null,
    val tombstones: List<ByteArray>? = null,
)

/** Client→spool: fetch blobs by id (bounded by the HELLO `maxPull`); answered by BLOBs then OK. */
@Serializable
class SpoolPull(
    val t: String,
    val q: Long,
    val scope: ByteArray,
    val blobIds: List<ByteArray>,
)

/** Spool→client: one pulled blob. */
@Serializable
class SpoolBlob(
    val t: String,
    val scope: ByteArray,
    val blobId: ByteArray,
    val data: ByteArray,
)

/** Client→spool: store a sealed blob (the spool re-verifies `blobId = SHA-256(data)`). */
@Serializable
class SpoolPush(
    val t: String,
    val q: Long,
    val scope: ByteArray,
    val blobId: ByteArray,
    val data: ByteArray,
    val pow: PowStamp? = null,
)

/** Spool→subscribers: live delivery of a newly stored blob (uploader excluded; best-effort). */
@Serializable
class SpoolEvent(
    val t: String,
    val scope: ByteArray,
    val blobId: ByteArray,
    val data: ByteArray,
)

/** Spool→client: terminal ack for [q]; a PULL's ids the spool no longer holds land in [missing]. */
@Serializable
class SpoolOk(
    val t: String,
    val q: Long,
    val missing: List<ByteArray>? = null,
)

/** Spool→client: terminal error for [q] (or connection-scoped when [q] is absent). */
@Serializable
class SpoolErr(
    val t: String,
    val code: String,
    val q: Long? = null,
    val scope: ByteArray? = null,
    val msg: String? = null,
    val retryMs: Long? = null,
)

/**
 * Codec for the record layer. Same explicit profile as `WireCodec`/`cryptoCbor` — definite-length,
 * unknown-tolerant, defaults omitted — plus `alwaysUseByteString` so `List<ByteArray>` ids encode as
 * byte strings (the annotation route only covers direct properties). Decoders return null on any
 * malformed input; a spool answers that with close code 4000, a client just drops the record.
 */
@OptIn(ExperimentalSerializationApi::class)
object SpoolCodec {
    val cbor: Cbor =
        Cbor {
            ignoreUnknownKeys = true
            encodeDefaults = false
            useDefiniteLengthEncoding = true
            alwaysUseByteString = true
        }

    inline fun <reified T> encode(record: T): ByteArray = cbor.encodeToByteArray(record)

    inline fun <reified T> decode(bytes: ByteArray): T? = runCatching { cbor.decodeFromByteArray<T>(bytes) }.getOrNull()

    /** The discriminator of an arbitrary record, or null when it isn't even a record. */
    fun peekType(bytes: ByteArray): String? = decode<SpoolRecordHead>(bytes)?.t
}
