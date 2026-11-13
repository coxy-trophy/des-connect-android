/*
 * SPDX-FileCopyrightText: 2026 Coxwell Wussah
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.phonekey

import android.content.Intent
import android.content.res.Configuration
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.view.updateLayoutParams
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.base.BaseActivity
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.plugins.phonekey.PhoneKeyProtocol.Purpose
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ActivityPhoneKeyBinding

/**
 * The full-screen prompt for one phone-key request: which desktop asks, for what
 * purpose, for which user, and the exact text. Approve leads to the system
 * biometric prompt, which authenticates the one signature this request may get.
 *
 * Every text view is filled from the [PhoneKeyPrompt], and the signed message is
 * built from that same object, so what is signed is what was shown.
 */
@RequiresApi(Build.VERSION_CODES.P)
class PhoneKeyActivity : BaseActivity<ActivityPhoneKeyBinding>(), PhoneKeyPrompts.Listener {

    override val binding: ActivityPhoneKeyBinding by lazy { ActivityPhoneKeyBinding.inflate(layoutInflater) }

    private var prompt: PhoneKeyPrompt? = null
    private var authentication: CancellationSignal? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val prompt = prompt ?: return
            val left = ((prompt.deadlineElapsedMs - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0).toInt()
            binding.phonekeyExpires.text = resources.getQuantityString(R.plurals.phonekey_expires_in, left, left)
            if (left > 0) handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Not in screenshots, the recents thumbnail or a screen share.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setSupportActionBar(binding.toolbarLayout.toolbar)
        binding.phonekeyDeny.setOnClickListener { decide(approved = false) }
        binding.phonekeyApprove.setOnClickListener { decide(approved = true) }
        limitContentWidth()
        show(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        limitContentWidth()
    }

    /** Content centers within 720dp on wide displays, like discovery and pairing. */
    private fun limitContentWidth() {
        val wide = resources.configuration.screenWidthDp > MAX_CONTENT_WIDTH_DP
        binding.phonekeyContent.updateLayoutParams<ViewGroup.LayoutParams> {
            width = if (wide) (MAX_CONTENT_WIDTH_DP * resources.displayMetrics.density).toInt() else ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    private fun plugin(prompt: PhoneKeyPrompt): PhoneKeyPlugin? =
        KdeConnect.getInstance().getDevicePlugin<PhoneKeyPlugin>(prompt.desktopId)

    private fun show(intent: Intent) {
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        val prompt = if (deviceId != null && requestId != null) PhoneKeyPrompts.slot.find(deviceId, requestId) else null
        if (prompt == null) {
            // Answered, cancelled or timed out before it was opened.
            finish()
            return
        }
        this.prompt = prompt
        val challenge = prompt.challenge
        binding.phonekeyHeadline.setText(headline(challenge.purpose))
        binding.phonekeyDesktop.text = prompt.desktopName
        binding.phonekeyPurpose.setText(purposeName(challenge.purpose))
        binding.phonekeyUser.text = challenge.user
        binding.phonekeyAction.text = challenge.action

        if (prompt.isEnrol) {
            // The fingerprint belongs on this screen, so the key is made before
            // Approve can be pressed.
            binding.phonekeyFingerprintLabel.visibility = View.VISIBLE
            binding.phonekeyFingerprint.visibility = View.VISIBLE
            binding.phonekeyFingerprint.setText(R.string.phonekey_creating_key)
            binding.phonekeyApprove.isEnabled = false
            ThreadHelper.runOnNewThread {
                val fingerprint = plugin(prompt)?.prepareEnrolment(prompt)
                runOnUiThread {
                    if (this.prompt !== prompt || isFinishing) return@runOnUiThread
                    if (fingerprint == null) {
                        finish()
                    } else {
                        binding.phonekeyFingerprint.text = fingerprint
                        binding.phonekeyApprove.isEnabled = true
                    }
                }
            }
        } else {
            binding.phonekeyFingerprintLabel.visibility = View.GONE
            binding.phonekeyFingerprint.visibility = View.GONE
            binding.phonekeyApprove.isEnabled = true
        }
    }

    override fun onStart() {
        super.onStart()
        PhoneKeyPrompts.addListener(this)
        val prompt = prompt
        if (prompt == null || PhoneKeyPrompts.slot.find(prompt.desktopId, prompt.requestIdHex) !== prompt) {
            finish()
            return
        }
        handler.post(tick)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tick)
        PhoneKeyPrompts.removeListener(this)
    }

    override fun onPromptGone(prompt: PhoneKeyPrompt) {
        runOnUiThread {
            if (prompt !== this.prompt) return@runOnUiThread
            // Take the system biometric sheet down with the request. Its error callback
            // finds the request already gone, so nothing more is sent.
            authentication?.cancel()
            authentication = null
            finish()
        }
    }

    private fun decide(approved: Boolean) {
        val prompt = prompt ?: return
        val plugin = plugin(prompt)
        if (plugin == null) {
            finish() // the desktop disconnected; its request went with it
            return
        }
        if (!approved) {
            plugin.deny(prompt)
            return
        }
        if (authentication != null) return
        // Null means the key cannot be used; the plugin has answered and this screen closes.
        val signature = plugin.signerFor(prompt) ?: return
        val challenge = prompt.challenge
        val cancel = CancellationSignal()
        val builder = BiometricPrompt.Builder(this)
            // The system sheet repeats what is being approved: the purpose and desktop
            // as its title, the user, and the exact text.
            .setTitle(getString(biometricTitle(challenge.purpose), prompt.desktopName))
            .setSubtitle(getString(R.string.phonekey_biometric_user, challenge.user))
            .setDescription(challenge.action)
            .setNegativeButton(getString(R.string.cancel), mainExecutor) { _, _ -> plugin.deny(prompt, "biometric_cancelled") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setConfirmationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        authentication = cancel
        binding.phonekeyApprove.isEnabled = false
        builder.build().authenticate(
            BiometricPrompt.CryptoObject(signature), cancel, mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authentication = null
                    // Only the operation the biometric just unlocked can sign.
                    val unlocked = result.cryptoObject?.signature
                    if (unlocked == null) {
                        plugin.deny(prompt, "biometric")
                    } else {
                        ThreadHelper.runOnNewThread { plugin.approve(prompt, unlocked) }
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Cancelled, locked out, no biometric left: the answer is no.
                    authentication = null
                    plugin.deny(prompt, "biometric")
                }

                // onAuthenticationFailed is one unrecognised attempt; the sheet stays up
                // and ends in success or in onAuthenticationError.
            },
        )
    }

    private fun headline(purpose: Purpose): Int = when (purpose) {
        Purpose.ENROL -> R.string.phonekey_headline_enrol
        Purpose.UNLOCK -> R.string.phonekey_headline_unlock
        Purpose.APPROVE -> R.string.phonekey_headline_approve
    }

    private fun purposeName(purpose: Purpose): Int = when (purpose) {
        Purpose.ENROL -> R.string.phonekey_purpose_enrol
        Purpose.UNLOCK -> R.string.phonekey_purpose_unlock
        Purpose.APPROVE -> R.string.phonekey_purpose_approve
    }

    private fun biometricTitle(purpose: Purpose): Int = when (purpose) {
        Purpose.ENROL -> R.string.phonekey_biometric_enrol
        Purpose.UNLOCK -> R.string.phonekey_biometric_unlock
        Purpose.APPROVE -> R.string.phonekey_biometric_approve
    }

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_REQUEST_ID = "requestId"
        private const val MAX_CONTENT_WIDTH_DP = 720
    }
}
