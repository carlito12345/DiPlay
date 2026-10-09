package com.shilapi.xcertplay

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.hud.BydVehicleCapabilities
import com.shilapi.xcertplay.hud.BydVehicleFieldStore
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
class BydAdbSettingsUiTest {
    private lateinit var activity: DiPlayActivity
    private lateinit var controls: LinearLayout

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        for (name in listOf("diplay_byd_outputs", "diplay_byd_vehicle_fields", "diplay_car_hotspot", "xcertplay_airplay")) {
            app.getSharedPreferences(name, 0).edit().clear().commit()
        }
        BydVehicleFieldStore.clearMemoryForTests()
        shadowOf(app.packageManager).removePackage("com.byd.amapservice")
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.byd.carsettings"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.byd.carsettings"
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        })
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.MANUAL)
        controls = LinearLayout(activity)
        assertTrue(CarHotspotSetup.isBydHeadUnit(activity))
        assertFalse(BydOutputSettings.navigationAvailable(activity))
    }

    @Test fun hotspotAndVehicleSettingsHaveOneOwnerWithoutNavigationServices() {
        render(LocalAdb.Access.READY)
        val advanced = advancedVehicleData()
        val page = LinearLayout(activity).apply { addView(controls); addView(advanced) }
        assertEquals(View.VISIBLE, controls.visibility)
        assertEquals(1, switches(controls).size)
        assertFalse(CarHotspotSettings.enabled(activity))
        for (id in listOf(R.string.car_battery_for_the_iphone, R.string.wheel_speed_for_tunnels,
            R.string.video_while_parked, R.string.auto_car_hotspot_title)) {
            assertEquals(1, switches(page).count { it.contentDescription == activity.getString(id) })
        }
        assertEquals(1, labels(page).count { it == activity.getString(R.string.check_adb_access) })
        assertTrue(labels(advanced).any { it.contains(activity.getString(R.string.vehicle_data_mode_default)) })
    }

    @Test fun unapprovedHotspotStillShowsItsOptInAndVehicleSettingsRemainAvailable() {
        render(LocalAdb.Access.NOT_APPROVED)
        assertEquals(View.VISIBLE, controls.visibility)
        assertTrue(labels(controls).contains(activity.getString(R.string.adb_not_approved)))
        assertEquals(1, switches(controls).size)
        assertTrue(labels(advancedVehicleData()).contains(activity.getString(R.string.check_adb_access)))
    }

    @Test fun changingHotspotModeHidesOnlyHotspotControlsAndPreservesTheSavedChoice() {
        CarHotspotSettings.setEnabled(activity, true)
        AirPlayPersistence.saveAutoStartOnBoot(activity, true)
        for (mode in listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.MANUAL)) {
            AirPlayPersistence.saveWirelessHotspotMode(activity, mode)
            render(LocalAdb.Access.READY)
            assertEquals(if (mode == WirelessHotspotMode.MANUAL) View.VISIBLE else View.GONE, controls.visibility)
            assertEquals(mode == WirelessHotspotMode.MANUAL, labels(controls).contains(activity.getString(R.string.auto_car_hotspot_title)))
            val vehicleSwitches = switches(advancedVehicleData())
            assertEquals(setOf(
                R.string.car_battery_for_the_iphone, R.string.wheel_speed_for_tunnels,
                R.string.video_while_parked, R.string.bt_suspend_during_carplay,
            ).map { activity.getString(it) }.toSet(), vehicleSwitches.map { it.contentDescription.toString() }.toSet())
            assertFalse(vehicleSwitches.single {
                it.contentDescription == activity.getString(R.string.bt_suspend_during_carplay)
            }.isChecked)
            assertFalse(AirPlayPersistence.loadBtSuspendDuringCarplay(activity))
            assertTrue(CarHotspotSettings.enabled(activity))
            assertTrue(AirPlayPersistence.loadAutoStartOnBoot(activity))
        }
    }

    @Test fun bluetoothPauseRemainsAvailableWhenSavedProbeHasNoBatteryCapability() {
        BydVehicleFieldStore.save(activity, BydVehicleCapabilities(
            fields = emptyMap(), catalogAvailable = false, firmwareKey = BydVehicleFieldStore.firmwareKey(),
        ))
        BydOutputSettings.setLegacyVehicleProbe(activity, true)
        AirPlayPersistence.saveBtSuspendDuringCarplay(activity, true)
        AirPlayPersistence.saveBtSuspendDelaySeconds(activity, 15)

        val advanced = advancedVehicleData()
        val pause = switches(advanced).single {
            it.contentDescription == activity.getString(R.string.bt_suspend_during_carplay)
        }
        assertTrue(pause.isChecked)
        assertTrue(pause.isEnabled)
        assertFalse(switches(advanced).any {
            it.contentDescription == activity.getString(R.string.car_battery_for_the_iphone)
        })
        assertTrue(labels(advanced).any { it.contains(activity.getString(R.string.bt_suspend_delay)) })
        assertTrue(labels(advanced).any { it.contains(activity.getString(R.string.bt_suspend_delay_option, 15)) })
        assertTrue(AirPlayPersistence.loadBtSuspendDuringCarplay(activity))
        assertEquals(15, AirPlayPersistence.loadBtSuspendDelaySeconds(activity))

        ReflectionHelpers.setField(activity, "adbSwitchChangePending", true)
        assertFalse(switches(advancedVehicleData()).single {
            it.contentDescription == activity.getString(R.string.bt_suspend_during_carplay)
        }.isEnabled)
    }

    @Test fun bluetoothPauseChangesApplyAtTheNextConnectionWithoutDroppingTheSession() {
        AirPlayPersistence.saveBtSuspendDuringCarplay(activity, true)
        val session = mock(CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        CarPlayBackgroundSession.active = true
        try {
            PendingReconnect.clear()
            switches(advancedVehicleData()).single {
                it.contentDescription == activity.getString(R.string.bt_suspend_during_carplay)
            }.performClick()
            assertFalse(AirPlayPersistence.loadBtSuspendDuringCarplay(activity))
            assertTrue(PendingReconnect.isPending(session))
            assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
            assertEquals(0, stops)
        } finally {
            CarPlayBackgroundSession.clear()
            PendingReconnect.clear()
        }
    }

    @Test fun unavailableHotspotAdbDoesNotHideTheVehicleModeOrItsSavedSwitches() {
        BydOutputSettings.setBatteryToIphone(activity, true)
        for (access in listOf(LocalAdb.Access.UNREACHABLE, LocalAdb.Access.UNSUPPORTED)) {
            render(LocalAdb.Access.READY)
            render(access)
            assertEquals(View.GONE, controls.visibility)
            assertEquals(0, controls.childCount)
            assertNull(ReflectionHelpers.getField<TextView?>(activity, "adbStatus"))
            assertTrue(switches(advancedVehicleData()).single {
                it.contentDescription == activity.getString(R.string.car_battery_for_the_iphone)
            }.isChecked)
        }
    }

    @Test fun hotspotAuthorizationDisablesVehicleChoicesWithoutChangingPreferences() {
        ReflectionHelpers.setField(activity, "adbSwitchChangePending", true)
        val advanced = advancedVehicleData()
        assertTrue(switches(advanced).all { !it.isEnabled })
        assertFalse(descendants(advanced).filterIsInstance<TextView>().single {
            it.text.startsWith(activity.getString(R.string.vehicle_data_mode) + " · ")
        }.isEnabled)
        assertFalse(BydOutputSettings.legacyVehicleProbe(activity))
        assertFalse(BydOutputSettings.batteryToIphone(activity))
    }

    private fun advancedVehicleData() = LinearLayout(activity).also {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "advancedVehicleData",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, it))
    }
    private fun render(access: LocalAdb.Access) {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "renderBydAdbControls",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, controls),
            ReflectionHelpers.ClassParameter.from(LocalAdb.Access::class.java, access))
    }
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }
    private fun labels(view: View) = descendants(view).filterIsInstance<TextView>().map { it.text.toString() }
    private fun switches(view: View) = descendants(view).filterIsInstance<Switch>()
}
