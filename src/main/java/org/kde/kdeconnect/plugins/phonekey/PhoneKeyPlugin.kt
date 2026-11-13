/*
 * SPDX-FileCopyrightText: 2026 Coxwell Wussah
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.phonekey

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.DeviceHelper
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.helpers.security.SslHelper
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect.plugins.phonekey.PhoneKeyProtocol.Incoming
import org.kde.kdeconnect.plugins.phonekey.PhoneKeyProtocol.Purpose
import org.kde.kdeconnect.plugins.phonekey.PhoneKeyProtocol.Refusal
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect_tp.R
import java.security.Signature

/**
 * The phone as a key for one paired DES desktop (`des.phonekey.v1`).
 *
 * Off by default, and the desktop is only told this phone supports it once the
 * user enabled the plugin for that desktop. Inside it, answering presence checks,
 * unlock requests and administrator approvals are three more switches, all off.
 *
 * What this class must never do: sign without the person approving on the
 * biometric prompt (the keystore enforces that too), sign anything but the fields
 * the prompt displayed, remember an approval, or start an enrolment itself.
 */
@LoadablePlugin
class PhoneKeyPlugin : Plugin() {

    override val displayName: String
        get() = context.getString(R.string.pref_plugin_phonekey)

    override val description: String
        get() = context.getString(R.string.pref_plugin_phonekey_desc)

    override val isEnabledByDefault: Boolean = false

    override val advertiseOnlyWhenEnabled: Boolean = true

    // setUnlockedDeviceRequired, StrongBox and BiometricPrompt all start with Android 9
    override val minSdk: Int = Build.VERSION_CODES.P

    override val supportedPacketTypes: Array<String> = arrayOf(PhoneKeyProtocol.PACKET_TYPE)

    override val outgoingPacketTypes: Array<String> = arrayOf(PhoneKeyProtocol.PACKET_TYPE)

    override val hasSettings: Boolean = true

    override val settingsScope = SettingsScope.PER_DEVICE

    override fun getSettingsFragment(activity: Activity): PluginSettingsFragment =
        PhoneKeySettingsFragment.newInstance(pluginKey, device.deviceId)

    // A request is a notification first: without them nothing could ever be shown.
    override val requiredPermissions: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray()
    }

    override val permissionExplanation: Int = R.string.phonekey_notifications_explanation

    private var presence = PhoneKeyProtocol.PresenceTracker()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val notificationId by lazy { SystemClock.elapsedRealtime().toInt() }
    private val notificationManager: NotificationManager?
        get() = ContextCompat.getSystemService(context, NotificationManager::class.java)

    override fun onCreate() {
        // The desktop counts presence questions from 1 again on every session.
        presence = PhoneKeyProtocol.PresenceTracker()
        ThreadHelper.runOnNewThread { reconcileKey() }
    }

    override fun onDestroy() {
        // The session that asked is gone, or the plugin was switched off: nothing may
        // stay on screen, and there is nobody to answer.
        PhoneKeyPrompts.slot.takeFor(device.deviceId)?.let { prompt ->
            dismiss(prompt)
            ThreadHelper.runOnNewThread { discardEnrolment(prompt) }
        }
    }

    override fun onDeviceUnpaired(context: Context, deviceId: String) {
        PhoneKeyPrompts.slot.takeFor(deviceId)?.let { dismiss(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            synchronized(keyLock) { PhoneKeyStore.delete(deviceId) }
        }
        context.getSharedPreferences("${deviceId}_${pluginKey}_preferences", Context.MODE_PRIVATE).edit { clear() }
    }

    // ---- what the settings screen shows -------------------------------------------------------

    /** The fingerprint of the key the desktop holds for this phone, or null when none is enrolled. */
    val enrolledFingerprint: String?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            heldKeyIdHex()?.let { PhoneKeyProtocol.unhex(it, PhoneKeyProtocol.DIGEST_BYTES) }?.let(PhoneKeyProtocol::fingerprint)
        } else {
            null
        }

    val canUseStrongBiometric: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && PhoneKeyStore.strongBiometricEnrolled(context)

    /** The user deleted the key on this phone: delete it and tell the desktop to forget its copy. */
    @WorkerThread
    fun deleteKeyByUser() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        PhoneKeyPrompts.slot.takeFor(device.deviceId)?.let { prompt ->
            dismiss(prompt)
            send(PhoneKeyProtocol.denied(prompt.isEnrol, prompt.requestIdHex, "key_deleted"))
        }
        forgetKey(tellDesktop = true)
    }

    // ---- packets ------------------------------------------------------------------------------

    private class PacketFields(private val np: NetworkPacket) : PhoneKeyProtocol.Fields {
        override fun string(name: String): String? = np.getStrictStringOrNull(name)
        override fun integer(name: String): Long? = np.getStrictLongOrNull(name)
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        if (np.type != PhoneKeyProtocol.PACKET_TYPE || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        // Device only calls this for a paired device, with the plugin enabled by the user.
        val hasPayload = np.hasPayload() || np.hasPayloadTransferInfo()
        np.payload?.close()
        when (val incoming = PhoneKeyProtocol.parse(PacketFields(np), hasPayload)) {
            is Incoming.Ignored -> return false
            is Incoming.Presence -> {
                // No UI and no notification: this may only ever postpone a lock on the desktop.
                if (switchedOn(R.string.phonekey_preference_key_presence) && presence.shouldAnswer(incoming.seq)) {
                    send(PhoneKeyProtocol.present(incoming.seq))
                }
            }
            is Incoming.Cancel -> PhoneKeyPrompts.slot.take(device.deviceId, incoming.requestIdHex)?.let { prompt ->
                dismiss(prompt)
                ThreadHelper.runOnNewThread { discardEnrolment(prompt) }
            }
            is Incoming.Revoke -> {
                PhoneKeyPrompts.slot.takeFor(device.deviceId)?.let { dismiss(it) }
                forgetKey(tellDesktop = false)
            }
            is Incoming.Malformed -> send(PhoneKeyProtocol.denied(incoming.enrol, incoming.requestIdHex, "malformed"))
            is Incoming.Ask -> ask(incoming)
        }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun ask(ask: Incoming.Ask) {
        val challenge = ask.challenge
        val enrol = challenge.purpose == Purpose.ENROL
        val enabled = when (challenge.purpose) {
            Purpose.ENROL -> true
            Purpose.UNLOCK -> switchedOn(R.string.phonekey_preference_key_unlock)
            Purpose.APPROVE -> switchedOn(R.string.phonekey_preference_key_approve)
        }
        // One request at a time, from any desktop. Checked first so that a second
        // request cannot disturb the key of an enrolment that is on screen.
        var refusal = if (PhoneKeyPrompts.slot.peek() != null) {
            Refusal.BUSY
        } else {
            PhoneKeyProtocol.refusal(ask, System.currentTimeMillis() / 1000, heldKeyIdHex(), enabled)
        }
        if (refusal == null && enrol && !PhoneKeyStore.strongBiometricEnrolled(context)) {
            refusal = Refusal.NO_BIOMETRIC
            explain(R.string.phonekey_no_biometric_title, context.getString(R.string.phonekey_no_biometric_text, device.name))
        }
        val prompt = PhoneKeyPrompt(
            device.deviceId, device.name, challenge, SystemClock.elapsedRealtime() + challenge.ttlSeconds * 1000,
        )
        if (refusal == null && !PhoneKeyPrompts.slot.offer(prompt)) refusal = Refusal.BUSY
        if (refusal != null) {
            Log.i(TAG, "Refusing ${challenge.purpose.wire} ${challenge.requestIdHex}: ${refusal.reason}")
            send(PhoneKeyProtocol.denied(enrol, challenge.requestIdHex, refusal.reason))
            if (refusal.alsoRevoke) forgetKey(tellDesktop = true)
            return
        }
        showNotification(prompt)
        // The request lives for its stated time from receipt and not a moment longer.
        mainHandler.postDelayed({ expire(prompt) }, challenge.ttlSeconds * 1000)
    }

    private fun expire(prompt: PhoneKeyPrompt) {
        // Dismiss everything and send nothing: the desktop has its own deadline.
        val expired = PhoneKeyPrompts.slot.take(prompt.desktopId, prompt.requestIdHex) ?: return
        dismiss(expired)
        ThreadHelper.runOnNewThread { discardEnrolment(expired) }
    }

    private fun send(body: Map<String, Any>) {
        val np = NetworkPacket(PhoneKeyProtocol.PACKET_TYPE)
        for ((name, value) in body) {
            when (value) {
                is String -> np[name] = value
                is Long -> np[name] = value
                is List<*> -> np[name] = value.map { it as String }
                else -> throw IllegalArgumentException("unexpected value for $name")
            }
        }
        device.sendPacket(np)
    }

    private fun switchedOn(keyRes: Int): Boolean = preferences.getBoolean(context.getString(keyRes), false)

    // ---- what the prompt screen does ----------------------------------------------------------

    /** Everything a signature is bound to besides the request. Never taken from a packet. */
    private fun binding() = PhoneKeyProtocol.Binding(
        device.deviceId,
        PhoneKeyProtocol.sha256(device.certificate.encoded),
        DeviceHelper.getDeviceId(context),
        PhoneKeyProtocol.sha256(SslHelper.certificate.encoded),
    )

    /**
     * Enrolment: create the key for this request, replacing any earlier one, and
     * return its fingerprint to display. Null when the request is gone or no key
     * could be made; the request has then been answered `denied`.
     */
    @WorkerThread
    @RequiresApi(Build.VERSION_CODES.P)
    fun prepareEnrolment(prompt: PhoneKeyPrompt): String? = synchronized(keyLock) {
        if (PhoneKeyPrompts.slot.find(prompt.desktopId, prompt.requestIdHex) !== prompt || !prompt.isEnrol) return null
        prompt.enrolKey?.let { return PhoneKeyProtocol.fingerprint(PhoneKeyProtocol.keyId(it)) }
        try {
            // From here the earlier key is gone, whatever the person decides.
            prompt.replacedConfirmedKey = preferences.contains(KEY_KEY_ID)
            preferences.edit(commit = true) { remove(KEY_KEY_ID).remove(KEY_ENROLLED_AT) }
            val generated = PhoneKeyStore.generate(device.deviceId, prompt.challenge.nonce)
            prompt.enrolKey = generated.spki
            prompt.enrolAttestation = generated.attestation
            Log.i(TAG, "Created a key for ${device.name}, StrongBox: ${generated.strongBox}")
        } catch (e: Exception) {
            Log.e(TAG, "Could not create a key", e)
            deny(prompt, Refusal.KEYSTORE.reason)
            // By far the likeliest reason is that no strong biometric is enrolled.
            explain(R.string.phonekey_no_biometric_title, context.getString(R.string.phonekey_no_biometric_text, device.name))
            return null
        }
        if (PhoneKeyPrompts.slot.find(prompt.desktopId, prompt.requestIdHex) !== prompt) {
            // Cancelled or timed out while the keystore was working.
            discardEnrolment(prompt)
            return null
        }
        PhoneKeyProtocol.fingerprint(PhoneKeyProtocol.keyId(prompt.enrolKey!!))
    }

    /**
     * A signature operation for this request, to hand to the biometric prompt.
     * Null when the key cannot be used; the request has then been answered `denied`.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun signerFor(prompt: PhoneKeyPrompt): Signature? {
        if (PhoneKeyPrompts.slot.find(prompt.desktopId, prompt.requestIdHex) !== prompt) return null
        return try {
            synchronized(keyLock) { PhoneKeyStore.signer(device.deviceId) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.w(TAG, "The key was invalidated by a biometric or lock screen change")
            deny(prompt, "key_invalidated")
            forgetKey(tellDesktop = true)
            explain(R.string.phonekey_invalidated_title, context.getString(R.string.phonekey_invalidated_text, device.name))
            null
        } catch (e: Exception) {
            Log.e(TAG, "Could not use the key", e)
            deny(prompt, Refusal.KEYSTORE.reason)
            null
        }
    }

    /**
     * The person approved and the biometric prompt authenticated [signature]. Sign
     * the message built from the fields [prompt] displayed and send the answer.
     */
    @WorkerThread
    @RequiresApi(Build.VERSION_CODES.P)
    fun approve(prompt: PhoneKeyPrompt, signature: Signature) {
        // Taking the request out makes this its one answer. If it is already gone
        // (timed out, cancelled) nothing is signed and nothing is sent.
        val taken = PhoneKeyPrompts.slot.take(prompt.desktopId, prompt.requestIdHex) ?: return
        dismiss(taken)
        val challenge = taken.challenge
        try {
            synchronized(keyLock) {
                val spki = (if (taken.isEnrol) taken.enrolKey else PhoneKeyStore.publicKey(device.deviceId))
                    ?: throw IllegalStateException("no key")
                val keyId = PhoneKeyProtocol.keyId(spki)
                signature.update(challenge.message(binding(), keyId))
                val signed = signature.sign()
                if (taken.isEnrol) {
                    preferences.edit(commit = true) {
                        putString(KEY_KEY_ID, PhoneKeyProtocol.hex(keyId))
                        putLong(KEY_ENROLLED_AT, System.currentTimeMillis() / 1000)
                    }
                    send(PhoneKeyProtocol.enrolled(challenge, spki, signed, taken.enrolAttestation))
                } else {
                    send(PhoneKeyProtocol.approved(challenge, signed))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not sign", e)
            send(PhoneKeyProtocol.denied(taken.isEnrol, taken.requestIdHex, Refusal.KEYSTORE.reason))
            discardEnrolment(taken)
        }
    }

    /** The person said no, or the biometric prompt failed or was cancelled. */
    fun deny(prompt: PhoneKeyPrompt, reason: String? = null) {
        val taken = PhoneKeyPrompts.slot.take(prompt.desktopId, prompt.requestIdHex) ?: return
        dismiss(taken)
        send(PhoneKeyProtocol.denied(taken.isEnrol, taken.requestIdHex, reason))
        ThreadHelper.runOnNewThread { discardEnrolment(taken) }
    }

    // ---- key state ----------------------------------------------------------------------------

    /** The ID of the key held for this desktop, only if it is the one the desktop was given. */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun heldKeyIdHex(): String? = synchronized(keyLock) {
        val confirmed = preferences.getString(KEY_KEY_ID, null) ?: return null
        val spki = PhoneKeyStore.publicKey(device.deviceId) ?: return null
        confirmed.takeIf { it == PhoneKeyProtocol.hex(PhoneKeyProtocol.keyId(spki)) }
    }

    /** An enrolment that did not complete leaves no key behind. */
    private fun discardEnrolment(prompt: PhoneKeyPrompt) {
        if (!prompt.isEnrol || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val mine = prompt.enrolKey
        if (mine == null && !prompt.replacedConfirmedKey) return
        synchronized(keyLock) {
            if (prompt.discarded) return
            prompt.discarded = true
            if (preferences.contains(KEY_KEY_ID)) return // a later enrolment completed: leave its key alone
            // Only this request's own key: a newer request may have made another since.
            if (mine != null && PhoneKeyStore.publicKey(prompt.desktopId)?.contentEquals(mine) == true) {
                PhoneKeyStore.delete(prompt.desktopId)
            }
        }
        // The key the desktop still holds was deleted to make room for this one.
        if (prompt.replacedConfirmedKey) send(PhoneKeyProtocol.revoke())
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun forgetKey(tellDesktop: Boolean) {
        synchronized(keyLock) {
            PhoneKeyStore.delete(device.deviceId)
            preferences.edit(commit = true) { remove(KEY_KEY_ID).remove(KEY_ENROLLED_AT) }
        }
        if (tellDesktop) send(PhoneKeyProtocol.revoke())
    }

    /**
     * On every connection: a key the keystore invalidated or lost is deleted and the
     * desktop told, and a key from an enrolment that never completed is removed.
     */
    private fun reconcileKey() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val revoke = synchronized(keyLock) {
            if (PhoneKeyPrompts.slot.peek()?.let { it.desktopId == device.deviceId && it.isEnrol } == true) return
            val confirmed = preferences.getString(KEY_KEY_ID, null)
            val actual = PhoneKeyStore.publicKey(device.deviceId)?.let { PhoneKeyProtocol.hex(PhoneKeyProtocol.keyId(it)) }
            val usable = confirmed != null && confirmed == actual && try {
                PhoneKeyStore.signer(device.deviceId)
                true
            } catch (e: KeyPermanentlyInvalidatedException) {
                false
            } catch (e: Exception) {
                true // locked phone, busy keystore: not a statement about the key
            }
            if (usable) return
            if (actual != null) PhoneKeyStore.delete(device.deviceId)
            preferences.edit(commit = true) { remove(KEY_KEY_ID).remove(KEY_ENROLLED_AT) }
            confirmed != null
        }
        if (revoke) {
            Log.w(TAG, "The key for ${device.name} is gone or invalidated, telling the desktop")
            send(PhoneKeyProtocol.revoke())
        }
    }

    // ---- notifications ------------------------------------------------------------------------

    private fun showNotification(prompt: PhoneKeyPrompt) {
        val intent = Intent(context, PhoneKeyActivity::class.java).apply {
            // PendingIntent matching ignores extras, so scope the action to this request.
            action = "io.github.coxytrophy.desconnect.PHONEKEY.${prompt.desktopId}.${prompt.requestIdHex}"
            putExtra(PhoneKeyActivity.EXTRA_DEVICE_ID, prompt.desktopId)
            putExtra(PhoneKeyActivity.EXTRA_REQUEST_ID, prompt.requestIdHex)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = context.getString(
            when (prompt.challenge.purpose) {
                Purpose.ENROL -> R.string.phonekey_notification_enrol
                Purpose.UNLOCK -> R.string.phonekey_notification_unlock
                Purpose.APPROVE -> R.string.phonekey_notification_approve
            },
            prompt.desktopName,
        )
        // No actions on purpose: approving needs the full prompt and the biometric.
        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(prompt.challenge.action)
            .setStyle(NotificationCompat.BigTextStyle().bigText(prompt.challenge.action))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, NotificationHelper.Channels.HIGHPRIORITY)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .build()
            )
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setTimeoutAfter(prompt.challenge.ttlSeconds * 1000)
            .setLocalOnly(true) // never bridged to a watch
            .build()
        notificationManager?.notify(notificationId, notification)
    }

    private fun explain(titleRes: Int, text: String) {
        val notification = NotificationCompat.Builder(context, NotificationHelper.Channels.DEFAULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(titleRes))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setLocalOnly(true)
            .build()
        notificationManager?.notify(notificationId + 1, notification)
    }

    private fun dismiss(prompt: PhoneKeyPrompt) {
        notificationManager?.cancel(notificationId)
        PhoneKeyPrompts.notifyGone(prompt)
    }

    companion object {
        private const val TAG = "PhoneKeyPlugin"

        /** Hex ID of the key the desktop was given. A keystore key with another ID does not count. */
        private const val KEY_KEY_ID = "phonekey_key_id"
        private const val KEY_ENROLLED_AT = "phonekey_enrolled_at"

        /**
         * Guards the keystore entries and the preferences that describe them. Shared by
         * all instances: a plugin object is replaced on every reconnection while work
         * started by the previous one may still be running.
         */
        private val keyLock = Any()
    }
}
