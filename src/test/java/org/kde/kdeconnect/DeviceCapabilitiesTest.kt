/*
 * DES Connect modification, 2026-11-03: regressions for peer capability filtering.
 * SPDX-FileCopyrightText: 2026 Coxwell Wussah
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kde.kdeconnect.backends.BaseLink
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory
import org.kde.kdeconnect.plugins.ping.PingPlugin
import org.robolectric.annotation.Config
import java.security.cert.Certificate

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [28])
class DeviceCapabilitiesTest {
    private lateinit var context: Context
    private val connections = mutableListOf<Pair<Device, BaseLink>>()
    private val desktopIncoming = setOf("kdeconnect.ping", "kdeconnect.battery")
    private val desktopOutgoing = setOf("kdeconnect.ping", "kdeconnect.battery.request")
    private val desktopPlugins = setOf("PingPlugin", "BatteryPlugin")

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        // This project does not package app resources into JVM tests. Only translated
        // labels are replaced; the actual plugin classes and capability metadata stay real.
        val labels = mockk<Resources>(relaxed = true) {
            every { getString(any()) } answers { "Label ${firstArg<Int>()}" }
        }
        context = object : ContextWrapper(application) {
            override fun getResources(): Resources = labels
        }
        // Use the real registered metadata, not a mocked matching result.
        PluginFactory.initPluginInfo(context)
        assertTrue(PluginFactory.availablePlugins.contains("SharePlugin"))
    }

    @After
    fun tearDown() {
        connections.forEach { (device, link) -> device.removeLink(link) }
    }

    private fun discover(incoming: Set<String>?, outgoing: Set<String>?): Device {
        val info = DeviceInfo(
            id = "capabilities-test-device",
            certificate = mockk<Certificate>(),
            name = "Capability test peer",
            type = DeviceType.DESKTOP,
            protocolVersion = 8,
            incomingCapabilities = incoming,
            outgoingCapabilities = outgoing
        )
        val link = mockk<BaseLink>(relaxed = true) {
            every { deviceInfo } returns info
        }
        return Device(context, link).also {
            connections.add(it to link)
        }
    }

    @Test
    fun initialAliasedIdentityLoadsOnlyAdvertisedPlugins() {
        val device = discover(desktopIncoming, desktopOutgoing)
        assertEquals(desktopPlugins, device.supportedPlugins)
    }

    @Test
    fun repeatedIdenticalCapabilitiesKeepTheFilteredPlugins() {
        val device = discover(desktopIncoming, desktopOutgoing)
        assertFalse(device.updateDeviceInfo(device.deviceInfo.copy()))
        assertEquals(desktopPlugins, device.supportedPlugins)
    }

    @Test
    fun explicitEmptyIdentitySupportsNoPlugins() {
        assertTrue(discover(emptySet(), emptySet()).supportedPlugins.isEmpty())
    }

    @Test
    fun explicitEmptyUpdateRemovesPreviouslySupportedPlugins() {
        val device = discover(PluginFactory.outgoingCapabilities, PluginFactory.incomingCapabilities)
        assertTrue(device.updateDeviceInfo(device.deviceInfo.copy(
            incomingCapabilities = emptySet(), outgoingCapabilities = emptySet()
        )))
        assertTrue(device.supportedPlugins.isEmpty())
    }

    @Test
    fun aliasedCapabilityRemovalStillRefreshesPluginSelection() {
        val device = discover(desktopIncoming, desktopOutgoing)
        device.deviceInfo.incomingCapabilities = emptySet()
        device.deviceInfo.outgoingCapabilities = emptySet()
        assertTrue(device.updateDeviceInfo(device.deviceInfo))
        assertTrue(device.supportedPlugins.isEmpty())
    }

    @Test
    fun nullLegacyCapabilitiesKeepAllPluginsAvailable() {
        assertEquals(PluginFactory.availablePlugins, discover(null, null).supportedPlugins)
    }

    @Test
    fun partiallyUnknownLegacyCapabilitiesKeepTheFallback() {
        assertEquals(PluginFactory.availablePlugins, discover(desktopIncoming, null).supportedPlugins)
        assertEquals(PluginFactory.availablePlugins, discover(null, desktopOutgoing).supportedPlugins)
    }

    @Test
    fun missingCapabilityUpdateDoesNotEraseKnownCapabilities() {
        val device = discover(desktopIncoming, desktopOutgoing)
        assertFalse(device.updateDeviceInfo(device.deviceInfo.copy(
            incomingCapabilities = null, outgoingCapabilities = null
        )))
        assertEquals(desktopPlugins, device.supportedPlugins)
        assertEquals(desktopIncoming, device.deviceInfo.incomingCapabilities)
    }

    @Test
    fun fullCapabilityPeerKeepsEveryPlugin() {
        assertEquals(PluginFactory.availablePlugins,
            discover(PluginFactory.outgoingCapabilities, PluginFactory.incomingCapabilities).supportedPlugins)
    }

    @Test
    fun factoryMatchesTheActualDesktopIdentity() {
        assertEquals(desktopPlugins, PluginFactory.pluginsForCapabilities(desktopIncoming, desktopOutgoing))
    }

    @Test
    fun factoryPreservesReceiveOnlyAndSendOnlyMatching() {
        assertTrue(PluginFactory.pluginsForCapabilities(emptySet(), setOf("kdeconnect.clipboard"))
            .contains("ClipboardPlugin"))
        assertTrue(PluginFactory.pluginsForCapabilities(setOf("kdeconnect.clipboard"), emptySet())
            .contains("ClipboardPlugin"))
        assertTrue(PluginFactory.pluginsForCapabilities(emptySet(), emptySet()).isEmpty())
        assertEquals(PluginFactory.availablePlugins,
            PluginFactory.pluginsForCapabilities(PluginFactory.outgoingCapabilities, PluginFactory.incomingCapabilities))
    }

    @Test
    fun capabilityRemovalPrunesActionsAndBothPermissionMaps() {
        val device = discover(PluginFactory.outgoingCapabilities, PluginFactory.incomingCapabilities)
        // Stand in for a previously instantiated module without granting Android permissions.
        val clipboard = mockk<Plugin>(relaxed = true)
        device.loadedPlugins["ClipboardPlugin"] = clipboard
        device.pluginsWithoutPermissions["ClipboardPlugin"] = clipboard
        device.pluginsWithoutOptionalPermissions["ClipboardPlugin"] = clipboard
        assertTrue(device.updateDeviceInfo(device.deviceInfo.copy(
            incomingCapabilities = desktopIncoming, outgoingCapabilities = desktopOutgoing
        )))
        device.reloadPluginsFromSettings()
        assertFalse(device.loadedPlugins.containsKey("ClipboardPlugin"))
        assertFalse(device.pluginsWithoutPermissions.containsKey("ClipboardPlugin"))
        assertFalse(device.pluginsWithoutOptionalPermissions.containsKey("ClipboardPlugin"))
    }

    @Test
    fun reusedLinkCallbackLoadsNewlySupportedPluginBeforeNotifyingObservers() {
        val device = discover(emptySet(), emptySet())
        device.pairingHandler.state = PairingHandler.PairState.Paired
        val application = KdeConnect()
        application.devices[device.deviceId] = device
        val observedPlugins = mutableListOf<Set<String>>()
        application.addDeviceListChangedCallback("test") {
            observedPlugins.add(device.loadedPlugins.keys.toSet())
        }

        application.connectionListener.onDeviceInfoUpdated(device.deviceInfo.copy(
            incomingCapabilities = setOf("kdeconnect.ping"),
            outgoingCapabilities = setOf("kdeconnect.ping")
        ))

        assertEquals(setOf("PingPlugin"), device.supportedPlugins)
        assertTrue(device.getPlugin("PingPlugin") is PingPlugin)
        assertEquals(1, device.getPlugin("PingPlugin")!!.getUiMenuEntries().size)
        assertEquals(listOf(setOf("PingPlugin")), observedPlugins)
    }

    @Test
    fun reusedLinkCallbackUnloadsRemovedPluginBeforeNotifyingObservers() {
        val device = discover(setOf("kdeconnect.ping"), setOf("kdeconnect.ping"))
        device.pairingHandler.state = PairingHandler.PairState.Paired
        device.reloadPluginsFromSettings()
        assertTrue(device.getPlugin("PingPlugin") is PingPlugin)
        val application = KdeConnect()
        application.devices[device.deviceId] = device
        val observedPlugins = mutableListOf<Set<String>>()
        application.addDeviceListChangedCallback("test") {
            observedPlugins.add(device.loadedPlugins.keys.toSet())
        }

        application.connectionListener.onDeviceInfoUpdated(device.deviceInfo.copy(
            incomingCapabilities = emptySet(), outgoingCapabilities = emptySet()
        ))

        assertTrue(device.supportedPlugins.isEmpty())
        assertTrue(device.loadedPlugins.isEmpty())
        assertEquals(listOf(emptySet<String>()), observedPlugins)
    }

    @Test
    fun reusedLinkCallbackDoesNotReloadOrNotifyForIdenticalCapabilities() {
        val device = discover(setOf("kdeconnect.ping"), setOf("kdeconnect.ping"))
        device.pairingHandler.state = PairingHandler.PairState.Paired
        device.reloadPluginsFromSettings()
        val plugin = device.getPlugin("PingPlugin")
        assertTrue(plugin is PingPlugin)
        val application = KdeConnect()
        application.devices[device.deviceId] = device
        var pluginChanges = 0
        var deviceChanges = 0
        device.addPluginsChangedListener { pluginChanges++ }
        application.addDeviceListChangedCallback("test") { deviceChanges++ }

        application.connectionListener.onDeviceInfoUpdated(device.deviceInfo.copy())

        assertSame(plugin, device.getPlugin("PingPlugin"))
        assertEquals(0, pluginChanges)
        assertEquals(0, deviceChanges)
    }
}
