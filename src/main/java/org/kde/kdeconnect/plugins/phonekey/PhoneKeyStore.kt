/*
 * SPDX-FileCopyrightText: 2026 Coxwell Wussah
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.phonekey

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import androidx.annotation.RequiresApi
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The per-desktop signing keys, held by the Android Keystore.
 *
 * A key never leaves the keystore, and every single use of it needs a Class 3
 * ("strong") biometric: a [Signature] from [signer] cannot sign until it went
 * through a BiometricPrompt as its CryptoObject. There is no device-PIN fallback
 * and nothing in this app can sign on its own. Enrolling a new biometric
 * invalidates the key for good.
 *
 * The parameters are section 4.7 of the phone-key specification.
 */
@RequiresApi(Build.VERSION_CODES.P)
object PhoneKeyStore {
    private const val TAG = "PhoneKeyStore"
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS_PREFIX = "des.phonekey."

    /** An attestation chain larger than this is not sent: the desktop reads packets of at most 64 KiB. */
    private const val MAX_ATTESTATION_BYTES = 24 * 1024

    class Generated(val spki: ByteArray, val attestation: List<ByteArray>, val strongBox: Boolean)

    fun alias(desktopId: String): String = ALIAS_PREFIX + desktopId

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /**
     * Create the key for this desktop, replacing any earlier one. StrongBox when the
     * device has it, the TEE otherwise.
     *
     * @param nonce the enrol request's nonce, used as the attestation challenge
     * @throws GeneralSecurityException or a RuntimeException from the keystore when no key could be made,
     * for example because no strong biometric is enrolled
     */
    @Throws(GeneralSecurityException::class)
    fun generate(desktopId: String, nonce: ByteArray): Generated {
        delete(desktopId)
        val strongBox = try {
            generate(desktopId, nonce, strongBox = true)
            true
        } catch (e: StrongBoxUnavailableException) {
            Log.i(TAG, "No StrongBox on this device, keeping the key in the TEE")
            delete(desktopId)
            generate(desktopId, nonce, strongBox = false)
            false
        }
        val store = keyStore()
        val alias = alias(desktopId)
        val spki = store.getCertificate(alias)?.publicKey?.encoded
        if (spki == null || !PhoneKeyProtocol.isP256Spki(spki)) {
            delete(desktopId)
            throw GeneralSecurityException("the keystore did not return a P-256 public key")
        }
        val chain = store.getCertificateChain(alias)?.map { it.encoded }.orEmpty()
        val attestation = if (chain.sumOf { it.size } <= MAX_ATTESTATION_BYTES) chain else emptyList()
        return Generated(spki, attestation, strongBox)
    }

    @Throws(GeneralSecurityException::class)
    private fun generate(desktopId: String, nonce: ByteArray, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias(desktopId), KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .setInvalidatedByBiometricEnrollment(true)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .setAttestationChallenge(nonce)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(spec) }.generateKeyPair()
    }

    /** The SubjectPublicKeyInfo of the key held for this desktop, or null when there is none. */
    fun publicKey(desktopId: String): ByteArray? = try {
        keyStore().getCertificate(alias(desktopId))?.publicKey?.encoded?.takeIf { PhoneKeyProtocol.isP256Spki(it) }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the key for $desktopId", e)
        null
    }

    /**
     * A signature operation on this desktop's key. It can only sign after a
     * BiometricPrompt authenticated it as a CryptoObject.
     *
     * @throws android.security.keystore.KeyPermanentlyInvalidatedException when a
     * biometric was enrolled or the lock screen removed since the key was made
     */
    @Throws(GeneralSecurityException::class)
    fun signer(desktopId: String): Signature {
        val key = keyStore().getKey(alias(desktopId), null) as? PrivateKey
            ?: throw GeneralSecurityException("no key for this desktop")
        return Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
    }

    fun delete(desktopId: String) {
        try {
            keyStore().deleteEntry(alias(desktopId))
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete the key for $desktopId", e)
        }
    }

    /** Delete every phone key: used when all pairings are dropped at once. */
    fun deleteAll() {
        try {
            val store = keyStore()
            store.aliases().toList().filter { it.startsWith(ALIAS_PREFIX) }.forEach(store::deleteEntry)
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete the phone keys", e)
        }
    }

    /** Whether a key could be created and used right now: a secure lock screen and a Class 3 biometric. */
    @Suppress("DEPRECATION")
    fun strongBiometricEnrolled(context: Context): Boolean = try {
        strongBiometricStatus(context)
    } catch (e: Exception) {
        // Could not ask. The keystore still refuses to create or use the key without one.
        Log.w(TAG, "Could not query biometrics", e)
        true
    }

    @Suppress("DEPRECATION")
    private fun strongBiometricStatus(context: Context): Boolean {
        if (context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) return false
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                context.getSystemService(BiometricManager::class.java)
                    ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
                context.getSystemService(BiometricManager::class.java)?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
            else ->
                // Android 9 cannot be asked what is enrolled without an API that current
                // SDKs no longer expose. Its keystore refuses to create the key without an
                // enrolled fingerprint; the plugin tells the user when that happens.
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        }
    }
}
