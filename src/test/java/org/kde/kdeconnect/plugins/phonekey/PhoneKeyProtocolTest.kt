/*
 * SPDX-FileCopyrightText: 2026 Coxwell Wussah
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.phonekey

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kde.kdeconnect.plugins.phonekey.PhoneKeyProtocol.Incoming
import org.kde.kdeconnect.plugins.phonekey.PhoneKeyProtocol.Purpose
import org.kde.kdeconnect.plugins.phonekey.PhoneKeyProtocol.Refusal
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * Plain JVM tests: no Android, no Robolectric. The vectors are the file the desktop
 * (`des-ui/connect/tests/phonekey_vectors.json`) and the root helper
 * (`desos/phonekey/testvectors.json`) are tested against, copied byte for byte.
 */
class PhoneKeyProtocolTest {
    private val vectors: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResourceAsStream("phonekey_vectors.json")) { "phonekey_vectors.json is not on the test classpath" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
    ).jsonObject

    private fun text(obj: JsonObject, name: String): String = obj.getValue(name).jsonPrimitive.content
    private fun number(obj: JsonObject, name: String): Long = obj.getValue(name).jsonPrimitive.content.toLong()
    private fun fromHex(text: String): ByteArray = checkNotNull(PhoneKeyProtocol.unhex(text, text.length / 2))

    private val spki: ByteArray = checkNotNull(PhoneKeyProtocol.unbase64(text(vectors, "public_key"), 91))
    private val keyId: ByteArray = PhoneKeyProtocol.keyId(spki)
    private val binding: PhoneKeyProtocol.Binding = vectors.getValue("binding").jsonObject.let {
        PhoneKeyProtocol.Binding(text(it, "desktop_id"), fromHex(text(it, "desktop_cert")), text(it, "phone_id"), fromHex(text(it, "phone_cert")))
    }

    /** A packet body as the desktop sends it for a vector. */
    private fun body(vector: JsonObject, vararg changes: Pair<String, Any?>): MutableMap<String, Any?> {
        val purpose = text(vector, "purpose")
        val body = mutableMapOf<String, Any?>(
            "v" to 1L, "op" to if (purpose == "enrol") "enrol" else "request", "purpose" to purpose,
            "request_id" to text(vector, "request_id"), "nonce" to text(vector, "nonce"), "user" to text(vector, "user"),
            "action" to text(vector, "action"), "issued_at" to number(vector, "issued_at"), "expires_at" to number(vector, "expires_at"),
        )
        if (purpose != "enrol") body["key_id"] = text(vectors, "key_id")
        for ((name, value) in changes) {
            if (value == null) body.remove(name) else body[name] = value
        }
        return body
    }

    private fun fields(body: Map<String, Any?>) = object : PhoneKeyProtocol.Fields {
        override fun string(name: String): String? = body[name] as? String
        override fun integer(name: String): Long? = body[name] as? Long
    }

    private fun parse(body: Map<String, Any?>, hasPayload: Boolean = false) = PhoneKeyProtocol.parse(fields(body), hasPayload)

    private val allVectors: List<JsonObject> get() = vectors.getValue("vectors").jsonArray.map { it.jsonObject }
    private val approveVector: JsonObject get() = allVectors.first { text(it, "purpose") == "approve" }
    private val enrolVector: JsonObject get() = allVectors.first { text(it, "purpose") == "enrol" }

    @Test
    fun keyIdAndFingerprintMatchTheVectors() {
        assertTrue(PhoneKeyProtocol.isP256Spki(spki))
        assertEquals(text(vectors, "key_id"), PhoneKeyProtocol.hex(keyId))
        assertEquals(text(vectors, "fingerprint"), PhoneKeyProtocol.fingerprint(keyId))
    }

    @Test
    fun everyVectorMessageIsReproducedByteForByte() {
        assertEquals(4, allVectors.size)
        for (vector in allVectors) {
            val ask = parse(body(vector))
            assertTrue("vector ${text(vector, "request_id")} must parse", ask is Incoming.Ask)
            val challenge = (ask as Incoming.Ask).challenge
            assertEquals(text(vector, "message"), PhoneKeyProtocol.hex(challenge.message(binding, keyId)))
        }
    }

    @Test
    fun everyVectorSignatureVerifiesOverTheMessageWeBuild() {
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        for (vector in allVectors) {
            val challenge = (parse(body(vector)) as Incoming.Ask).challenge
            val message = challenge.message(binding, keyId)
            val signature = checkNotNull(PhoneKeyProtocol.unbase64(text(vector, "signature"), 72))
            val verifier = Signature.getInstance("SHA256withECDSA").apply { initVerify(publicKey); update(message) }
            assertTrue("signature of ${text(vector, "request_id")}", verifier.verify(signature))

            // The same signature must not verify once any signed field differs.
            val other = checkNotNull(
                PhoneKeyProtocol.challenge(challenge.purpose, challenge.requestId, challenge.nonce, challenge.user, challenge.action + "!", challenge.issuedAt, challenge.expiresAt)
            )
            val tampered = Signature.getInstance("SHA256withECDSA").apply { initVerify(publicKey); update(other.message(binding, keyId)) }
            assertFalse(tampered.verify(signature))
        }
    }

    @Test
    fun everySignedFieldChangesTheMessage() {
        val challenge = (parse(body(approveVector)) as Incoming.Ask).challenge
        val reference = PhoneKeyProtocol.hex(challenge.message(binding, keyId))
        fun rebuilt(
            purpose: Purpose = challenge.purpose, requestId: ByteArray = challenge.requestId, nonce: ByteArray = challenge.nonce,
            user: String = challenge.user, action: String = challenge.action, issuedAt: Long = challenge.issuedAt, expiresAt: Long = challenge.expiresAt,
            with: PhoneKeyProtocol.Binding = binding, key: ByteArray = keyId,
        ) = PhoneKeyProtocol.hex(checkNotNull(PhoneKeyProtocol.challenge(purpose, requestId, nonce, user, action, issuedAt, expiresAt)).message(with, key))
        fun flipped(bytes: ByteArray) = bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }

        assertEquals(reference, rebuilt())
        val changed = listOf(
            rebuilt(purpose = Purpose.UNLOCK), rebuilt(requestId = flipped(challenge.requestId)), rebuilt(nonce = flipped(challenge.nonce)),
            rebuilt(user = "root"), rebuilt(action = "sudo rm -rf /"), rebuilt(issuedAt = challenge.issuedAt + 1),
            rebuilt(expiresAt = challenge.expiresAt + 1), rebuilt(key = flipped(keyId)),
            rebuilt(with = PhoneKeyProtocol.Binding(binding.desktopId + "0", binding.desktopCertSha256, binding.phoneId, binding.phoneCertSha256)),
            rebuilt(with = PhoneKeyProtocol.Binding(binding.desktopId, flipped(binding.desktopCertSha256), binding.phoneId, binding.phoneCertSha256)),
            rebuilt(with = PhoneKeyProtocol.Binding(binding.desktopId, binding.desktopCertSha256, binding.phoneId + "0", binding.phoneCertSha256)),
            rebuilt(with = PhoneKeyProtocol.Binding(binding.desktopId, binding.desktopCertSha256, binding.phoneId, flipped(binding.phoneCertSha256))),
        )
        for (message in changed) assertFalse(message == reference)
        assertEquals(changed.size, changed.toSet().size)
        // Length prefixes: moving a byte between adjacent fields is a different message.
        assertFalse(rebuilt(user = "coxyx", action = "y") == rebuilt(user = "coxy", action = "xy"))
    }

    @Test
    fun aKeyMadeHereSignsAMessageTheVectorLayoutVerifies() {
        // What the plugin does with the keystore key, done with a software key:
        // sign the message built from the displayed request, DER output.
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val madeSpki = pair.public.encoded
        assertTrue(PhoneKeyProtocol.isP256Spki(madeSpki))
        val challenge = (parse(body(enrolVector)) as Incoming.Ask).challenge
        val message = challenge.message(binding, PhoneKeyProtocol.keyId(madeSpki))
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(message) }.sign()
        val answer = PhoneKeyProtocol.enrolled(challenge, madeSpki, signature, listOf(byteArrayOf(1, 2, 3)))
        assertEquals("enrolled", answer["op"])
        assertEquals("approved", answer["decision"])
        assertEquals(challenge.requestIdHex, answer["request_id"])
        assertEquals(listOf("AQID"), answer["attestation"])
        assertArrayEquals(madeSpki, PhoneKeyProtocol.unbase64(answer["public_key"] as String, 91))
        val carried = checkNotNull(PhoneKeyProtocol.unbase64(answer["signature"] as String, 72))
        assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(pair.public); update(message) }.verify(carried))
    }

    @Test
    fun userNames() {
        for (good in listOf("coxy", "a.b-c_d", "A", "x".repeat(64), "_", ".")) assertTrue(good, PhoneKeyProtocol.validUser(good))
        for (bad in listOf("", "-coxy", "x".repeat(65), "co xy", "coxy\n", "cöxy", "a/b", "a:b", "a\u0000")) assertFalse(bad, PhoneKeyProtocol.validUser(bad))
    }

    @Test
    fun actionText() {
        val good = listOf("x", "sudo apt install 'café tools' --yes", "a b", "é".repeat(256), "x".repeat(512), "日本語", "🔑 key", "a​b")
        for (text in good) assertTrue(text, PhoneKeyProtocol.validAction(text))
        val bad = listOf(
            "", " x", "x ", "\tx", "x ", "　x", "x ", "a\nb", "a\rb", "a\tb", "a\u0000b", "a\u007Fb", "a\u0085b", "a\u009Fb",
            "a؜b", "a‎b", "a‏b", "a b", "a b", "a‪b", "a‫b", "a‬b", "a‭b", "a‮b",
            "a⁦b", "a⁧b", "a⁨b", "a⁩b", "a﻿b", "x".repeat(513), "é".repeat(257), "a\uD800b", "a\uDC00b", "\uDC00\uD800",
        )
        for (text in bad) assertFalse(PhoneKeyProtocol.hex(text.toByteArray()), PhoneKeyProtocol.validAction(text))
    }

    @Test
    fun malformedRequestsAreAnsweredDeniedAndNeverShown() {
        val vector = approveVector
        val id = text(vector, "request_id")
        val broken = listOf<Pair<String, Any?>>(
            "purpose" to "enrol", "purpose" to "sudo", "purpose" to null, "purpose" to 1L,
            "nonce" to null, "nonce" to "AAAA", "nonce" to text(vector, "nonce").dropLast(1) + " ", "nonce" to text(vector, "nonce") + "AAAA",
            "user" to "-rf", "user" to "", "user" to null, "user" to 7L,
            "action" to "", "action" to "two\nlines", "action" to " padded", "action" to "rtl‮override", "action" to null,
            "issued_at" to null, "issued_at" to "1794480002", "issued_at" to -1L,
            "expires_at" to number(vector, "issued_at"), "expires_at" to number(vector, "issued_at") - 1,
            "expires_at" to number(vector, "issued_at") + 121, "expires_at" to null,
            "key_id" to null, "key_id" to "A7AE", "key_id" to text(vectors, "key_id").uppercase(), "key_id" to text(vectors, "key_id") + "00",
        )
        for (change in broken) {
            val result = parse(body(vector, change))
            assertTrue("$change must be malformed, was $result", result is Incoming.Malformed)
            result as Incoming.Malformed
            assertEquals(id, result.requestIdHex)
            assertFalse(result.enrol)
            assertEquals(mapOf("v" to 1L, "op" to "response", "request_id" to id, "decision" to "denied"), PhoneKeyProtocol.denied(result.enrol, result.requestIdHex))
        }
        // The longest allowed life is exactly 120 seconds.
        assertTrue(parse(body(vector, "expires_at" to number(vector, "issued_at") + 120)) is Incoming.Ask)
        // An enrol op must carry the enrol purpose, and its answer is "enrolled".
        val wrongOp = parse(body(enrolVector, "purpose" to "approve"))
        assertTrue(wrongOp is Incoming.Malformed && wrongOp.enrol)
        assertEquals("enrolled", PhoneKeyProtocol.denied(true, id, "ignored for enrol")["op"])
        assertFalse(PhoneKeyProtocol.denied(true, id, "ignored for enrol").containsKey("reason"))
        assertEquals("busy", PhoneKeyProtocol.denied(false, id, "busy")["reason"])
    }

    @Test
    fun whatCannotBeAnsweredIsIgnored() {
        val vector = approveVector
        assertSame(Incoming.Ignored, parse(body(vector), hasPayload = true))
        assertSame(Incoming.Ignored, parse(body(vector, "v" to 2L)))
        assertSame(Incoming.Ignored, parse(body(vector, "v" to "1")))
        assertSame(Incoming.Ignored, parse(body(vector, "v" to null)))
        assertSame(Incoming.Ignored, parse(body(vector, "op" to "approve")))
        assertSame(Incoming.Ignored, parse(body(vector, "op" to null)))
        assertSame(Incoming.Ignored, parse(body(vector, "request_id" to null)))
        assertSame(Incoming.Ignored, parse(body(vector, "request_id" to "0303")))
        assertSame(Incoming.Ignored, parse(body(vector, "request_id" to text(vector, "request_id").uppercase().replace('0', 'A'))))
        // Unknown fields are ignored, not refused.
        assertTrue(parse(body(vector, "future" to "field")) is Incoming.Ask)
    }

    @Test
    fun presenceCancelAndRevoke() {
        val presence = parse(mapOf("v" to 1L, "op" to "presence", "seq" to 7L))
        assertEquals(7L, (presence as Incoming.Presence).seq)
        assertSame(Incoming.Ignored, parse(mapOf("v" to 1L, "op" to "presence")))
        assertSame(Incoming.Ignored, parse(mapOf("v" to 1L, "op" to "presence", "seq" to "7")))
        assertSame(Incoming.Ignored, parse(mapOf("v" to 1L, "op" to "presence", "seq" to -1L)))
        assertSame(Incoming.Ignored, parse(mapOf("v" to 1L, "op" to "presence", "seq" to 7L), hasPayload = true))
        assertEquals(mapOf("v" to 1L, "op" to "present", "seq" to 7L), PhoneKeyProtocol.present(7))

        val tracker = PhoneKeyProtocol.PresenceTracker()
        assertTrue(tracker.shouldAnswer(1))
        assertFalse(tracker.shouldAnswer(1))
        assertTrue(tracker.shouldAnswer(3))
        assertFalse(tracker.shouldAnswer(2))
        assertTrue(tracker.shouldAnswer(4))

        val id = "03030303030303030303030303030303"
        assertEquals(id, (parse(mapOf("v" to 1L, "op" to "cancel", "request_id" to id, "reason" to "timeout")) as Incoming.Cancel).requestIdHex)
        assertSame(Incoming.Ignored, parse(mapOf("v" to 1L, "op" to "cancel", "request_id" to "zz")))
        assertSame(Incoming.Revoke, parse(mapOf("v" to 1L, "op" to "revoke", "reason" to "desktop")))
        assertSame(Incoming.Revoke, parse(mapOf("v" to 1L, "op" to "revoke")))
        assertEquals(mapOf("v" to 1L, "op" to "revoke"), PhoneKeyProtocol.revoke())
    }

    @Test
    fun refusalsThatNeedThePhonesState() {
        val held = text(vectors, "key_id")
        val ask = parse(body(approveVector)) as Incoming.Ask
        val issued = ask.challenge.issuedAt
        assertNull(PhoneKeyProtocol.refusal(ask, issued, held, capabilityEnabled = true))
        assertNull(PhoneKeyProtocol.refusal(ask, issued + 300, held, true))
        assertNull(PhoneKeyProtocol.refusal(ask, issued - 300, held, true))
        assertEquals(Refusal.CLOCK, PhoneKeyProtocol.refusal(ask, issued + 301, held, true))
        assertEquals(Refusal.CLOCK, PhoneKeyProtocol.refusal(ask, issued - 301, held, true))
        assertEquals(Refusal.CLOCK, PhoneKeyProtocol.refusal(ask, Long.MIN_VALUE, held, true))
        assertEquals(Refusal.NO_KEY, PhoneKeyProtocol.refusal(ask, issued, null, true))
        assertTrue(Refusal.NO_KEY.alsoRevoke)
        assertEquals(Refusal.WRONG_KEY, PhoneKeyProtocol.refusal(ask, issued, "00".repeat(32), true))
        assertFalse(Refusal.WRONG_KEY.alsoRevoke)
        assertEquals(Refusal.DISABLED, PhoneKeyProtocol.refusal(ask, issued, held, capabilityEnabled = false))

        // Enrolment needs no key and no per-purpose switch, only a sane clock.
        val enrol = parse(body(enrolVector)) as Incoming.Ask
        assertNull(enrol.keyIdHex)
        assertNull(PhoneKeyProtocol.refusal(enrol, enrol.challenge.issuedAt, null, capabilityEnabled = false))
        assertEquals(Refusal.CLOCK, PhoneKeyProtocol.refusal(enrol, enrol.challenge.issuedAt + 301, null, false))
    }

    @Test
    fun approvedAnswerCarriesOnlyTheSignature() {
        val challenge = (parse(body(approveVector)) as Incoming.Ask).challenge
        val signature = checkNotNull(PhoneKeyProtocol.unbase64(text(approveVector, "signature"), 72))
        assertEquals(
            mapOf("v" to 1L, "op" to "response", "request_id" to challenge.requestIdHex, "decision" to "approved", "signature" to text(approveVector, "signature")),
            PhoneKeyProtocol.approved(challenge, signature),
        )
    }

    @Test
    fun oneRequestAtATimeAndOneAnswerPerRequest() {
        class Entry(override val desktopId: String, override val requestIdHex: String) : PhoneKeyProtocol.RequestSlot.Entry
        val slot = PhoneKeyProtocol.RequestSlot<Entry>()
        val first = Entry("desktop-a", "01")
        assertTrue(slot.offer(first))
        assertFalse(slot.offer(Entry("desktop-a", "02")))
        assertFalse(slot.offer(Entry("desktop-b", "03")))
        assertNull(slot.take("desktop-b", "01"))
        assertNull(slot.take("desktop-a", "02"))
        assertNull(slot.takeFor("desktop-b"))
        assertSame(first, slot.find("desktop-a", "01"))
        assertSame(first, slot.take("desktop-a", "01"))
        // Taken once: a late biometric result, a cancel or the timer find nothing.
        assertNull(slot.take("desktop-a", "01"))
        assertNull(slot.peek())
        val second = Entry("desktop-b", "03")
        assertTrue(slot.offer(second))
        assertSame(second, slot.takeFor("desktop-b"))
        assertNull(slot.peek())
    }

    @Test
    fun encodings() {
        assertEquals("00ff10", PhoneKeyProtocol.hex(byteArrayOf(0, -1, 16)))
        assertArrayEquals(byteArrayOf(0, -1, 16), PhoneKeyProtocol.unhex("00ff10", 3))
        assertNull(PhoneKeyProtocol.unhex("00FF10", 3))
        assertNull(PhoneKeyProtocol.unhex("00ff1", 3))
        assertNull(PhoneKeyProtocol.unhex("00ff10", 2))
        for (size in 0..40) {
            val bytes = ByteArray(size) { (it * 37 + size).toByte() }
            val encoded = PhoneKeyProtocol.base64(bytes)
            assertEquals(java.util.Base64.getEncoder().encodeToString(bytes), encoded)
            assertArrayEquals(bytes, PhoneKeyProtocol.unbase64(encoded, size))
        }
        assertNotNull(PhoneKeyProtocol.unbase64("AQ==", 1))
        for (bad in listOf("AQ", "AQ=", "AR==", "A===", "AQ==AQ==", "AQ= =", "A Q=", "AQI=\n", "AQJ=", "-_==", "=AQ="))
            assertNull(bad, PhoneKeyProtocol.unbase64(bad, 16))
        assertNull(PhoneKeyProtocol.unbase64("AQID", 2))
        assertFalse(PhoneKeyProtocol.isP256Spki(spki.copyOf(90)))
        assertFalse(PhoneKeyProtocol.isP256Spki(spki.copyOf().also { it[26] = 2 }))
    }
}
