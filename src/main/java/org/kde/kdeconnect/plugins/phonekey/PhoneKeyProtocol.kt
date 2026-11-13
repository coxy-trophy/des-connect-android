/*
 * SPDX-FileCopyrightText: 2026 Coxwell Wussah
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.phonekey

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * The phone as a key, protocol `des.phonekey.v1`: the signed message, the packet
 * bodies and every rule a request must pass before any of its text is shown.
 *
 * The specification is `desos/phonekey/README.md` (sections 3 and 4) in the DesOS
 * tree; the desktop's implementation is `des-ui/connect/src/phonekey.rs`.
 *
 * Nothing in this file may depend on Android. It is tested on the JVM against the
 * vectors the desktop is tested against (`src/test/resources/phonekey_vectors.json`).
 */
object PhoneKeyProtocol {
    /** The packet type and the identity capability, in both directions. */
    const val PACKET_TYPE = "des.phonekey.v1"
    const val VERSION = 1L

    const val MAX_TTL_SECONDS = 120L
    const val MAX_CLOCK_SKEW_SECONDS = 300L
    const val MAX_USER_BYTES = 64
    const val MAX_ACTION_BYTES = 512
    const val REQUEST_ID_BYTES = 16
    const val NONCE_BYTES = 32
    const val DIGEST_BYTES = 32
    const val SPKI_BYTES = 91

    /** "DES-PHONEKEY-v1" and a zero byte: 16 bytes of domain separation. */
    private val DOMAIN: ByteArray = "DES-PHONEKEY-v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    /**
     * DER header of a P-256 SubjectPublicKeyInfo up to and including the 0x04 that
     * starts an uncompressed point. The desktop accepts nothing else.
     */
    private val P256_SPKI_PREFIX: ByteArray = intArrayOf(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a,
        0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00, 0x04,
    ).map { it.toByte() }.toByteArray()

    enum class Purpose(val wire: String) {
        ENROL("enrol"), UNLOCK("unlock"), APPROVE("approve");

        companion object {
            fun parse(text: String?): Purpose? = entries.firstOrNull { it.wire == text }
        }
    }

    /**
     * The pairing a signature belongs to. The phone fills this in from its own
     * identity and the certificate it pinned for the desktop, never from a packet.
     */
    class Binding(
        val desktopId: String,
        desktopCertSha256: ByteArray,
        val phoneId: String,
        phoneCertSha256: ByteArray,
    ) {
        val desktopCertSha256: ByteArray = desktopCertSha256.copyOf()
        val phoneCertSha256: ByteArray = phoneCertSha256.copyOf()

        init {
            require(this.desktopCertSha256.size == DIGEST_BYTES && this.phoneCertSha256.size == DIGEST_BYTES) {
                "certificate digests are SHA-256"
            }
        }
    }

    /** A request exactly as it will be shown and signed. Instances are always valid. */
    class Challenge internal constructor(
        val purpose: Purpose,
        requestId: ByteArray,
        nonce: ByteArray,
        val user: String,
        val action: String,
        val issuedAt: Long,
        val expiresAt: Long,
    ) {
        private val requestIdBytes: ByteArray = requestId.copyOf()
        private val nonceBytes: ByteArray = nonce.copyOf()

        val requestId: ByteArray get() = requestIdBytes.copyOf()
        val nonce: ByteArray get() = nonceBytes.copyOf()
        val requestIdHex: String = hex(requestIdBytes)
        val ttlSeconds: Long get() = expiresAt - issuedAt

        /**
         * The exact bytes to sign. Every variable field carries a 32-bit big-endian
         * length, so no two different requests share an encoding.
         */
        fun message(binding: Binding, keyId: ByteArray): ByteArray {
            require(keyId.size == DIGEST_BYTES) { "the key ID is a SHA-256" }
            val out = ByteArrayOutputStream(512)
            out.write(DOMAIN)
            val fields = arrayOf(
                purpose.wire.toByteArray(Charsets.US_ASCII),
                binding.desktopId.toByteArray(Charsets.UTF_8),
                binding.desktopCertSha256,
                binding.phoneId.toByteArray(Charsets.UTF_8),
                binding.phoneCertSha256,
                keyId,
                requestIdBytes,
                nonceBytes,
                user.toByteArray(Charsets.UTF_8),
                action.toByteArray(Charsets.UTF_8),
            )
            for (field in fields) {
                out.write(u32(field.size))
                out.write(field)
            }
            out.write(u64(issuedAt))
            out.write(u64(expiresAt))
            return out.toByteArray()
        }
    }

    /**
     * Build a challenge, or null when any part breaks the rules of section 3.
     * This is the only way to get a [Challenge].
     */
    fun challenge(
        purpose: Purpose,
        requestId: ByteArray,
        nonce: ByteArray,
        user: String,
        action: String,
        issuedAt: Long,
        expiresAt: Long,
    ): Challenge? {
        if (requestId.size != REQUEST_ID_BYTES || nonce.size != NONCE_BYTES) return null
        if (!validUser(user) || !validAction(action)) return null
        if (issuedAt < 0 || expiresAt < 0) return null
        // Both are non-negative, so the subtraction cannot overflow.
        val ttl = expiresAt - issuedAt
        if (ttl <= 0 || ttl > MAX_TTL_SECONDS) return null
        return Challenge(purpose, requestId, nonce, user, action, issuedAt, expiresAt)
    }

    /** A login name: 1-64 bytes of `[A-Za-z0-9._-]`, not starting with `-`. */
    fun validUser(user: String): Boolean =
        user.isNotEmpty() && user.length <= MAX_USER_BYTES && user[0] != '-' &&
            user.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }

    /**
     * The text the person is shown and the key signs: 1-512 bytes of UTF-8, one
     * line, no leading or trailing whitespace, no control characters and nothing
     * that can hide or reorder what is displayed.
     */
    fun validAction(action: String): Boolean {
        if (action.isEmpty()) return false
        var bytes = 0
        var index = 0
        var first = -1
        var last = -1
        while (index < action.length) {
            val unit = action[index]
            val codePoint: Int
            if (Character.isHighSurrogate(unit)) {
                // A lone surrogate has no UTF-8 encoding: what would be signed is
                // not what would be shown.
                if (index + 1 >= action.length || !Character.isLowSurrogate(action[index + 1])) return false
                codePoint = Character.toCodePoint(unit, action[index + 1])
                index += 2
            } else {
                if (Character.isLowSurrogate(unit)) return false
                codePoint = unit.code
                index += 1
            }
            if (isControl(codePoint) || isForbiddenFormat(codePoint)) return false
            bytes += when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (bytes > MAX_ACTION_BYTES) return false
            if (first < 0) first = codePoint
            last = codePoint
        }
        return !isWhitespace(first) && !isWhitespace(last)
    }

    /** Unicode general category Cc: C0, DEL and C1. */
    private fun isControl(codePoint: Int): Boolean = codePoint < 0x20 || codePoint in 0x7f..0x9f

    private fun isForbiddenFormat(codePoint: Int): Boolean =
        codePoint == 0x061c || codePoint == 0x200e || codePoint == 0x200f || codePoint == 0x2028 || codePoint == 0x2029 ||
            codePoint in 0x202a..0x202e || codePoint in 0x2066..0x2069 || codePoint == 0xfeff

    /** The Unicode White_Space property, which is what the desktop trims by. */
    private fun isWhitespace(codePoint: Int): Boolean =
        codePoint in 0x09..0x0d || codePoint == 0x20 || codePoint == 0x85 || codePoint == 0xa0 || codePoint == 0x1680 ||
            codePoint in 0x2000..0x200a || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202f ||
            codePoint == 0x205f || codePoint == 0x3000

    // ---- keys ---------------------------------------------------------------------------------

    /** True for a 91-byte DER SubjectPublicKeyInfo of an uncompressed P-256 point. */
    fun isP256Spki(spki: ByteArray): Boolean =
        spki.size == SPKI_BYTES && P256_SPKI_PREFIX.indices.all { spki[it] == P256_SPKI_PREFIX[it] }

    /** SHA-256 of the SubjectPublicKeyInfo: how both sides name the key. */
    fun keyId(spki: ByteArray): ByteArray = sha256(spki)

    /** The key's name as a person compares it: `A7AE-9D8E-EBDE-E76C`. */
    fun fingerprint(keyId: ByteArray): String {
        require(keyId.size == DIGEST_BYTES) { "the key ID is a SHA-256" }
        return hex(keyId.copyOf(8)).uppercase().chunked(4).joinToString("-")
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    // ---- packets ------------------------------------------------------------------------------

    /**
     * A packet body, read strictly: [string] is non-null only for a JSON string and
     * [integer] only for a JSON integer that fits a signed 64-bit value.
     */
    interface Fields {
        fun string(name: String): String?
        fun integer(name: String): Long?
    }

    /** What the desktop said, after everything that can be checked without the phone's state. */
    sealed class Incoming {
        /** Not for us: wrong version, unknown operation, a payload, or nothing we could answer. */
        object Ignored : Incoming()
        class Presence(val seq: Long) : Incoming()
        class Cancel(val requestIdHex: String) : Incoming()
        object Revoke : Incoming()
        /** A well-formed `enrol` or `request`. [keyIdHex] is null for `enrol`. */
        class Ask(val challenge: Challenge, val keyIdHex: String?) : Incoming()
        /** A malformed `enrol` or `request` that named a request: answer `denied`, show nothing. */
        class Malformed(val enrol: Boolean, val requestIdHex: String) : Incoming()
    }

    fun parse(body: Fields, hasPayload: Boolean): Incoming {
        if (hasPayload || body.integer("v") != VERSION) return Incoming.Ignored
        return when (val op = body.string("op")) {
            "presence" -> body.integer("seq")?.takeIf { it >= 0 }?.let { Incoming.Presence(it) } ?: Incoming.Ignored
            "cancel" -> body.string("request_id")?.takeIf { unhex(it, REQUEST_ID_BYTES) != null }
                ?.let { Incoming.Cancel(it) } ?: Incoming.Ignored
            "revoke" -> Incoming.Revoke
            "enrol", "request" -> parseAsk(body, enrol = op == "enrol")
            else -> Incoming.Ignored
        }
    }

    private fun parseAsk(body: Fields, enrol: Boolean): Incoming {
        // Without a request identifier there is nothing an answer could name.
        val idText = body.string("request_id") ?: return Incoming.Ignored
        val requestId = unhex(idText, REQUEST_ID_BYTES) ?: return Incoming.Ignored
        val malformed = Incoming.Malformed(enrol, idText)

        val purpose = Purpose.parse(body.string("purpose")) ?: return malformed
        if ((purpose == Purpose.ENROL) != enrol) return malformed
        val nonce = body.string("nonce")?.let { unbase64(it, NONCE_BYTES) } ?: return malformed
        if (nonce.size != NONCE_BYTES) return malformed
        val user = body.string("user") ?: return malformed
        val action = body.string("action") ?: return malformed
        val issuedAt = body.integer("issued_at") ?: return malformed
        val expiresAt = body.integer("expires_at") ?: return malformed
        val challenge = challenge(purpose, requestId, nonce, user, action, issuedAt, expiresAt) ?: return malformed

        val keyIdHex = if (enrol) {
            null
        } else {
            body.string("key_id")?.takeIf { unhex(it, DIGEST_BYTES) != null } ?: return malformed
        }
        return Incoming.Ask(challenge, keyIdHex)
    }

    /** Why a well-formed request is answered `denied` without being shown. */
    enum class Refusal(val reason: String, val alsoRevoke: Boolean = false) {
        /** The phone's clock and `issued_at` are more than five minutes apart. */
        CLOCK("clock"),
        /** The person has not switched this capability on for this desktop. */
        DISABLED("disabled"),
        /** No key is held for this desktop: the desktop must forget its copy. */
        NO_KEY("no_key", alsoRevoke = true),
        /** The desktop named a key that is not the one held for it. */
        WRONG_KEY("wrong_key"),
        /** Another request is on screen. */
        BUSY("busy"),
        /** No Class 3 biometric is enrolled on this phone. */
        NO_BIOMETRIC("no_biometric"),
        /** This phone could not create or use the key. */
        KEYSTORE("keystore"),
    }

    /**
     * The checks of section 4.4 step 2 that need the phone's own state. Returns
     * null when the request may be shown.
     *
     * @param nowSeconds the phone's clock, Unix seconds
     * @param heldKeyIdHex the ID of the confirmed key held for this desktop, or null
     * @param capabilityEnabled whether the person switched this purpose on for this desktop
     */
    fun refusal(ask: Incoming.Ask, nowSeconds: Long, heldKeyIdHex: String?, capabilityEnabled: Boolean): Refusal? {
        val issuedAt = ask.challenge.issuedAt
        val skew = if (nowSeconds >= issuedAt) nowSeconds - issuedAt else issuedAt - nowSeconds
        // A difference too large for a Long wraps negative; that is not "within five minutes".
        if (skew < 0 || skew > MAX_CLOCK_SKEW_SECONDS) return Refusal.CLOCK
        if (ask.challenge.purpose == Purpose.ENROL) return null
        if (heldKeyIdHex == null) return Refusal.NO_KEY
        if (heldKeyIdHex != ask.keyIdHex) return Refusal.WRONG_KEY
        if (!capabilityEnabled) return Refusal.DISABLED
        return null
    }

    /** Answer to `presence`. */
    fun present(seq: Long): Map<String, Any> = mapOf("v" to VERSION, "op" to "present", "seq" to seq)

    /** The phone deleted its key. */
    fun revoke(): Map<String, Any> = mapOf("v" to VERSION, "op" to "revoke")

    /** Answer `denied` to an `enrol` ([enrol] true) or a `request`. The reason is logged only. */
    fun denied(enrol: Boolean, requestIdHex: String, reason: String? = null): Map<String, Any> {
        val body = linkedMapOf<String, Any>(
            "v" to VERSION, "op" to if (enrol) "enrolled" else "response", "request_id" to requestIdHex, "decision" to "denied",
        )
        if (!enrol && reason != null) body["reason"] = reason
        return body
    }

    /** Answer to an approved `request`. */
    fun approved(challenge: Challenge, signature: ByteArray): Map<String, Any> {
        require(challenge.purpose != Purpose.ENROL) { "an enrolment is answered with enrolled()" }
        return linkedMapOf(
            "v" to VERSION, "op" to "response", "request_id" to challenge.requestIdHex, "decision" to "approved",
            "signature" to base64(signature),
        )
    }

    /** Answer to an approved `enrol`: the new public key, proof of possession, and the attestation chain. */
    fun enrolled(challenge: Challenge, spki: ByteArray, signature: ByteArray, attestation: List<ByteArray>): Map<String, Any> {
        require(challenge.purpose == Purpose.ENROL) { "only an enrolment is answered with enrolled()" }
        require(isP256Spki(spki)) { "the enrolled key must be a P-256 SubjectPublicKeyInfo" }
        val body = linkedMapOf<String, Any>(
            "v" to VERSION, "op" to "enrolled", "request_id" to challenge.requestIdHex, "decision" to "approved",
            "public_key" to base64(spki), "signature" to base64(signature),
        )
        if (attestation.isNotEmpty()) body["attestation"] = attestation.map { base64(it) }
        return body
    }

    /** Only the newest presence question is answered, and only once. */
    class PresenceTracker {
        private var answered = -1L

        @Synchronized
        fun shouldAnswer(seq: Long): Boolean {
            if (seq <= answered) return false
            answered = seq
            return true
        }
    }

    /**
     * At most one request is on screen at a time, whichever desktop it came from.
     * Whoever takes it out owns its single answer; nobody else can answer it.
     */
    class RequestSlot<T : RequestSlot.Entry> {
        interface Entry {
            val desktopId: String
            val requestIdHex: String
        }

        private var current: T? = null

        /** False when another request already holds the slot. */
        @Synchronized
        fun offer(entry: T): Boolean {
            if (current != null) return false
            current = entry
            return true
        }

        @Synchronized
        fun peek(): T? = current

        /** The request, if it is this desktop's and has this identifier. It stays in the slot. */
        @Synchronized
        fun find(desktopId: String, requestIdHex: String): T? =
            current?.takeIf { it.desktopId == desktopId && it.requestIdHex == requestIdHex }

        /** Remove and return the request, if it is this desktop's and has this identifier. */
        @Synchronized
        fun take(desktopId: String, requestIdHex: String): T? = find(desktopId, requestIdHex)?.also { current = null }

        /** Remove and return whatever this desktop has in the slot. */
        @Synchronized
        fun takeFor(desktopId: String): T? = current?.takeIf { it.desktopId == desktopId }?.also { current = null }
    }

    // ---- encodings ----------------------------------------------------------------------------

    private const val HEX = "0123456789abcdef"

    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            out.append(HEX[(byte.toInt() shr 4) and 0x0f]).append(HEX[byte.toInt() and 0x0f])
        }
        return out.toString()
    }

    /** Exactly [size] bytes of lowercase hex, or null. */
    fun unhex(text: String, size: Int): ByteArray? {
        if (text.length != size * 2) return null
        val out = ByteArray(size)
        for (i in 0 until size) {
            val high = HEX.indexOf(text[2 * i])
            val low = HEX.indexOf(text[2 * i + 1])
            if (high < 0 || low < 0) return null
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Standard base64 with padding. */
    fun base64(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
            out.append(B64[b0 shr 2])
            out.append(B64[((b0 and 0x03) shl 4) or (b1 shr 4)])
            out.append(if (i + 1 < bytes.size) B64[((b1 and 0x0f) shl 2) or (b2 shr 6)] else '=')
            out.append(if (i + 2 < bytes.size) B64[b2 and 0x3f] else '=')
            i += 3
        }
        return out.toString()
    }

    /**
     * Strict standard base64: padding required, no whitespace, no stray bits, and
     * no more than [most] bytes of output. Null on anything else.
     */
    fun unbase64(text: String, most: Int): ByteArray? {
        if (text.length % 4 != 0 || text.length > (most + 2) / 3 * 4) return null
        if (text.isEmpty()) return ByteArray(0)
        val padding = when {
            text.endsWith("==") -> 2
            text.endsWith("=") -> 1
            else -> 0
        }
        val out = ByteArrayOutputStream(text.length / 4 * 3)
        var i = 0
        while (i < text.length) {
            val lastQuad = i + 4 == text.length
            val values = IntArray(4)
            for (j in 0 until 4) {
                val c = text[i + j]
                val isPad = lastQuad && j >= 4 - padding
                values[j] = if (isPad) 0 else B64.indexOf(c)
                if (values[j] < 0 || (c == '=' && !isPad)) return null
            }
            val word = (values[0] shl 18) or (values[1] shl 12) or (values[2] shl 6) or values[3]
            out.write(word shr 16)
            if (!lastQuad || padding < 2) out.write(word shr 8)
            if (!lastQuad || padding < 1) out.write(word)
            // Bits that fall off the end of the data must be zero.
            if (lastQuad && padding == 2 && (values[1] and 0x0f) != 0) return null
            if (lastQuad && padding == 1 && (values[2] and 0x03) != 0) return null
            i += 4
        }
        return out.toByteArray().takeIf { it.size <= most }
    }

    private fun u32(value: Int): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun u64(value: Long): ByteArray = ByteArray(8) { (value ushr (56 - 8 * it)).toByte() }
}
