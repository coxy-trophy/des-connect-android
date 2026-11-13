/*
 * SPDX-FileCopyrightText: 2026 Coxwell Wussah
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.phonekey

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kde.kdeconnect.helpers.DeviceHelper
import org.kde.kdeconnect.helpers.TrustedDevices
import org.kde.kdeconnect.helpers.security.SslHelper
import org.kde.kdeconnect.plugins.PluginFactory
import org.robolectric.annotation.Config
import java.security.cert.Certificate

/**
 * The desktop may only learn that this phone can be a key from the encrypted
 * identity sent to it after the user enabled the plugin for it.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [28])
class PhoneKeyAdvertisementTest {
    private lateinit var context: Context
    private val type = PhoneKeyProtocol.PACKET_TYPE
    private val pluginKey = "PhoneKeyPlugin"

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        // App resources are not packaged into JVM tests; only labels are replaced.
        val labels = mockk<Resources>(relaxed = true) {
            every { getString(any()) } answers { "Label ${firstArg<Int>()}" }
        }
        context = object : ContextWrapper(application) {
            override fun getResources(): Resources = labels
        }
        PluginFactory.initPluginInfo(context)
        SslHelper.certificate = mockk<Certificate>()
        DeviceHelper.initializeDeviceId(context)
        DeviceHelper.setDeviceName(context, "Test phone") // a stored name: no lookup on the network
    }

    private fun advertisedTo(peer: String?): Boolean {
        val info = DeviceHelper.getDeviceInfo(context, peer)
        val incoming = type in info.incomingCapabilities!!
        assertEquals("both directions or neither", incoming, type in info.outgoingCapabilities!!)
        return incoming
    }

    private fun enable(peer: String, enabled: Boolean) =
        TrustedDevices.getDeviceSettings(context, peer).edit { putBoolean(pluginKey, enabled) }

    @Test
    fun pluginIsRegisteredOffByDefaultAndMatchesADesktopThatListsIt() {
        assertTrue(pluginKey in PluginFactory.availablePlugins)
        val info = PluginFactory.getPluginInfo(pluginKey)
        assertFalse(info.isEnabledByDefault)
        assertTrue(info.advertiseOnlyWhenEnabled)
        assertTrue(info.hasSettings)
        assertEquals(setOf(type), info.supportedPacketTypes)
        assertEquals(setOf(type), info.outgoingPacketTypes)
        assertTrue(pluginKey in PluginFactory.pluginsForCapabilities(setOf(type), setOf(type)))
        assertFalse(pluginKey in PluginFactory.pluginsForCapabilities(setOf("kdeconnect.ping"), setOf("kdeconnect.ping")))
    }

    @Test
    fun capabilityIsNeverInTheIdentityEverybodyGets() {
        assertFalse(type in PluginFactory.incomingCapabilities)
        assertFalse(type in PluginFactory.outgoingCapabilities)
        // Everything else is still advertised.
        assertTrue("kdeconnect.ping" in PluginFactory.incomingCapabilities)
        assertFalse(advertisedTo(null))
    }

    @Test
    fun capabilityIsListedOnlyForAPairedDeviceTheUserEnabledItFor() {
        val desktop = "desktop_0123456789abcdef0123456789ab"
        val other = "other___0123456789abcdef0123456789ab"

        // Enabled in settings but not paired: nothing.
        enable(desktop, true)
        assertFalse(advertisedTo(desktop))

        TrustedDevices.addTrustedDevice(context, desktop)
        TrustedDevices.addTrustedDevice(context, other)
        assertTrue(advertisedTo(desktop))
        // Paired, but the user never enabled it there: off by default.
        assertFalse(advertisedTo(other))
        assertFalse(advertisedTo(null))

        enable(desktop, false)
        assertFalse(advertisedTo(desktop))
    }

    @Test
    @Config(application = Application::class, sdk = [23])
    fun capabilityIsNotListedWhereTheKeystoreCannotHoldSuchAKey() {
        val desktop = "desktop_0123456789abcdef0123456789ab"
        TrustedDevices.addTrustedDevice(context, desktop)
        enable(desktop, true)
        assertFalse(advertisedTo(desktop))
    }
}
