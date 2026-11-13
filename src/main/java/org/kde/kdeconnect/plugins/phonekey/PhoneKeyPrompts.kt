/*
 * SPDX-FileCopyrightText: 2026 Coxwell Wussah
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.phonekey

import java.util.concurrent.CopyOnWriteArrayList

/**
 * One request waiting for the person: exactly the fields that are displayed, and
 * therefore exactly the fields that may be signed.
 */
class PhoneKeyPrompt(
    override val desktopId: String,
    /** The desktop's name as the pairing knows it. */
    val desktopName: String,
    val challenge: PhoneKeyProtocol.Challenge,
    /** `SystemClock.elapsedRealtime()` at which the request is dismissed unanswered. */
    val deadlineElapsedMs: Long,
) : PhoneKeyProtocol.RequestSlot.Entry {
    override val requestIdHex: String = challenge.requestIdHex

    val isEnrol: Boolean get() = challenge.purpose == PhoneKeyProtocol.Purpose.ENROL

    /** Enrolment only: the key created for this request once the person opened it. Guarded by the plugin. */
    @Volatile
    internal var enrolKey: ByteArray? = null

    @Volatile
    internal var enrolAttestation: List<ByteArray> = emptyList()

    /** Enrolment only: creating the new key deleted one the desktop still holds. */
    @Volatile
    internal var replacedConfirmedKey: Boolean = false

    /** Enrolment only: the unfinished key was already cleaned up. */
    @Volatile
    internal var discarded: Boolean = false
}

/**
 * The single request that may be on screen, shared by every paired desktop, and
 * the screens that need to close when it goes away.
 */
object PhoneKeyPrompts {
    val slot = PhoneKeyProtocol.RequestSlot<PhoneKeyPrompt>()

    fun interface Listener {
        /** The request was answered, cancelled, revoked or timed out. Called on any thread. */
        fun onPromptGone(prompt: PhoneKeyPrompt)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(listener: Listener) = listeners.addIfAbsent(listener)

    fun removeListener(listener: Listener) = listeners.remove(listener)

    internal fun notifyGone(prompt: PhoneKeyPrompt) = listeners.forEach { it.onPromptGone(prompt) }
}
