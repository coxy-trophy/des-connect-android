/*
 * DES Connect modification, 2026-11-04: independent system-volume entrypoint regressions.
 * SPDX-FileCopyrightText: 2026 Coxwell Wussah
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.Fragment
import androidx.collection.LongSparseArray
import androidx.viewpager2.adapter.FragmentStateAdapter
import org.kde.kdeconnect.plugins.mpris.VolumeKeyListener
import org.kde.kdeconnect.plugins.systemvolume.SystemVolumeFragment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.kde.kdeconnect.backends.BaseLink
import org.kde.kdeconnect.plugins.PluginFactory
import org.kde.kdeconnect.plugins.mpris.MprisActivity
import org.kde.kdeconnect.plugins.mpris.MprisPlugin
import org.kde.kdeconnect.plugins.mpris.MprisNowPlayingFragment
import org.kde.kdeconnect.plugins.systemvolume.SystemVolumePlugin
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.security.cert.Certificate

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [28])
class SystemVolumeUiTest {
    class VolumeProbeFragment : Fragment(), VolumeKeyListener {
        var up = 0
        var down = 0
        override fun onVolumeUp() { up++ }
        override fun onVolumeDown() { down++ }
    }
    private fun device(): Device {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val labels = mockk<Resources>(relaxed = true) {
            every { getString(any()) } answers { "Label ${firstArg<Int>()}" }
        }
        val context = object : ContextWrapper(app) {
            override fun getResources(): Resources = labels
        }
        PluginFactory.initPluginInfo(context)
        val info = DeviceInfo(
            id = "volume-ui-test-peer", certificate = mockk<Certificate>(),
            name = "Volume-only test peer", type = DeviceType.DESKTOP, protocolVersion = 8,
            incomingCapabilities = setOf("kdeconnect.systemvolume.request"),
            outgoingCapabilities = setOf("kdeconnect.systemvolume")
        )
        val link = mockk<BaseLink>(relaxed = true) { every { deviceInfo } returns info }
        return Device(context, link).also {
            it.pairingHandler.state = PairingHandler.PairState.Paired
            it.reloadPluginsFromSettings()
        }
    }
    @Test
    fun volumeOnlyPeerHasReachableControlsOnDevicesTab() {
        val peer = device()
        assertNull(peer.getPlugin("MprisPlugin"))
        val plugin = peer.getPlugin("SystemVolumePlugin") as SystemVolumePlugin
        val buttons = plugin.getUiButtons()
        assertEquals(1, buttons.size)
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val activity = controller.get()
            buttons.single().onClick(activity)
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(MprisActivity::class.java.name, intent.component!!.className)
            assertEquals(peer.deviceId, intent.getStringExtra(MprisActivity.EXTRA_DEVICE_ID))
            assertTrue(intent.getBooleanExtra("showVolume", false))
        } finally { controller.destroy() }
    }
    @Test
    fun mediaPeerKeepsSingleCombinedControlEntry() {
        val peer = device()
        peer.loadedPlugins["MprisPlugin"] = mockk<MprisPlugin>(relaxed = true)
        val volume = peer.getPlugin("SystemVolumePlugin") as SystemVolumePlugin
        assertTrue(volume.getUiButtons().isEmpty())
    }
    @Test
    fun volumeOnlyPagerNeverCreatesAnUnavailableMediaFragment() {
        val peer = device()
        val app = mockk<KdeConnect>(relaxed = true)
        every { app.getDevice(peer.deviceId) } returns peer
        mockkObject(KdeConnect.Companion)
        every { KdeConnect.getInstance() } returns app
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).create()
        try {
            val adapter = MprisActivity.MprisPagerAdapter(controller.get(), peer.deviceId)
            assertEquals(1, adapter.itemCount)
            assertTrue(adapter.createFragment(0) is SystemVolumeFragment)
            assertEquals(org.kde.kdeconnect_tp.R.string.devices, adapter.getTitle(0))
            assertEquals(0, adapter.volumePageIndex)
            val volumeId = adapter.getItemId(0)
            assertEquals(1L, volumeId)
            assertFalse(adapter.containsItem(0L))
            assertTrue(adapter.containsItem(volumeId))
            val field = FragmentStateAdapter::class.java.getDeclaredField("mFragments")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val fragments = field.get(adapter) as LongSparseArray<Fragment>
            val probe = VolumeProbeFragment()
            fragments.put(volumeId, probe)
            try {
                adapter.onVolumeUp(0)
                adapter.onVolumeDown(0)
                adapter.onVolumeUp(-1)
                adapter.onVolumeDown(1)
                assertEquals(1, probe.up)
                assertEquals(1, probe.down)
            } finally { fragments.clear() }
            peer.loadedPlugins["MprisPlugin"] = mockk<MprisPlugin>(relaxed = true)
            val combined = MprisActivity.MprisPagerAdapter(controller.get(), peer.deviceId)
            assertEquals(2, combined.itemCount)
            assertTrue(combined.createFragment(0) is MprisNowPlayingFragment)
            assertTrue(combined.createFragment(1) is SystemVolumeFragment)
            assertEquals(1, combined.volumePageIndex)
            assertEquals(volumeId, combined.getItemId(1))
            assertEquals(0L, combined.getItemId(0))
            peer.loadedPlugins.remove("SystemVolumePlugin")
            val mediaOnly = MprisActivity.MprisPagerAdapter(controller.get(), peer.deviceId)
            assertEquals(1, mediaOnly.itemCount)
            assertTrue(mediaOnly.createFragment(0) is MprisNowPlayingFragment)
            assertEquals(-1, mediaOnly.volumePageIndex)
            assertFalse(mediaOnly.containsItem(volumeId))
            peer.loadedPlugins.remove("MprisPlugin")
            assertEquals(0, MprisActivity.MprisPagerAdapter(controller.get(), peer.deviceId).itemCount)
        } finally {
            controller.destroy()
            unmockkObject(KdeConnect.Companion)
        }
    }

}
