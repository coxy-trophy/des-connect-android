/*
 * SPDX-FileCopyrightText: 2026 Coxwell Wussah
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.phonekey

import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect_tp.R

/**
 * Per-desktop phone-key settings: one switch per capability, all off until the
 * user turns them on, and the fingerprint of the key this desktop holds.
 *
 * There is deliberately no control here that starts an enrolment, and nothing
 * like "always allow": enrolment starts on the desktop, and every request needs
 * the biometric.
 */
class PhoneKeySettingsFragment : PluginSettingsFragment(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val phoneKey: PhoneKeyPlugin?
        get() = plugin as? PhoneKeyPlugin

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        findPreference<Preference>(getString(R.string.phonekey_preference_key_delete))?.setOnPreferenceClickListener {
            confirmDelete()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        showKey()
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    // An enrolment, a revocation or a deletion changes the stored key while this is open.
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        activity?.runOnUiThread { if (isAdded) showKey() }
    }

    private fun showKey() {
        val phoneKey = phoneKey ?: return
        val fingerprint = phoneKey.enrolledFingerprint
        findPreference<Preference>(getString(R.string.phonekey_preference_key_fingerprint))?.summary = when {
            fingerprint != null -> fingerprint
            phoneKey.canUseStrongBiometric -> getString(R.string.phonekey_preference_no_key)
            else -> getString(R.string.phonekey_preference_no_biometric)
        }
        findPreference<Preference>(getString(R.string.phonekey_preference_key_delete))?.isVisible = fingerprint != null
    }

    private fun confirmDelete() {
        val phoneKey = phoneKey ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.phonekey_delete_confirm_title, device.name))
            .setMessage(R.string.phonekey_delete_confirm_text)
            .setPositiveButton(R.string.phonekey_delete) { _, _ ->
                ThreadHelper.runOnNewThread { phoneKey.deleteKeyByUser() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        fun newInstance(pluginKey: String, deviceId: String): PhoneKeySettingsFragment =
            PhoneKeySettingsFragment().apply { setArguments(pluginKey, deviceId, R.xml.phonekeyplugin_preferences) }
    }
}
