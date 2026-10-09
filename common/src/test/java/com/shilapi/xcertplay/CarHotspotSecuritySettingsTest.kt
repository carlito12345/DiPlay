package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
class CarHotspotSecuritySettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var activity: DiPlayActivity

    @Before fun setup() {
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(app, "Car hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "12345678")
    }

    @Test fun editingCredentialsPreservesSavedWpa3Modes() {
        for (mode in listOf(ManualHotspotSecurity.WPA3, ManualHotspotSecurity.WPA3_TRANSITION)) {
            AirPlayPersistence.saveManualHotspotSecurity(app, mode)
            val dialog = editCredentials()
            inputs(dialog)[1].setText("87654321")
            save(dialog)
            assertEquals(mode, AirPlayPersistence.loadManualHotspotSecurity(app))
            assertEquals("87654321", AirPlayPersistence.loadManualHotspotPassphrase(app))
        }
    }

    @Test fun selectingWpa3SavesOnlyWhenTheCredentialsAreSaved() {
        AirPlayPersistence.saveManualHotspotSecurity(app, ManualHotspotSecurity.WPA2)
        val dialog = editCredentials()
        chooseSecurity(dialog, "WPA3")
        assertEquals(ManualHotspotSecurity.WPA2, AirPlayPersistence.loadManualHotspotSecurity(app))
        save(dialog)
        assertEquals(ManualHotspotSecurity.WPA3, AirPlayPersistence.loadManualHotspotSecurity(app))
    }

    @Test fun cancellingCredentialsDoesNotSaveTheSecurityChoice() {
        AirPlayPersistence.saveManualHotspotSecurity(app, ManualHotspotSecurity.WPA2)
        val dialog = editCredentials()
        chooseSecurity(dialog, "WPA3")
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(ManualHotspotSecurity.WPA2, AirPlayPersistence.loadManualHotspotSecurity(app))
    }

    @Test fun emptyPasswordUsesOpenAndRetypingPreservesTheProtectedMode() {
        AirPlayPersistence.saveManualHotspotSecurity(app, ManualHotspotSecurity.WPA3)
        val dialog = editCredentials()
        inputs(dialog)[1].setText("")
        val security = securityButton(dialog)
        assertFalse(security.isEnabled)
        assertTrue(security.text.toString().contains(activity.getString(com.shilapi.xcertplay.host.R.string.open)))
        inputs(dialog)[1].setText("87654321")
        assertTrue(security.isEnabled)
        save(dialog)
        assertEquals(ManualHotspotSecurity.WPA3, AirPlayPersistence.loadManualHotspotSecurity(app))
        val reopened = editCredentials()
        inputs(reopened)[1].setText("")
        save(reopened)
        assertEquals(ManualHotspotSecurity.OPEN, AirPlayPersistence.loadManualHotspotSecurity(app))
    }

    private fun editCredentials(): AlertDialog {
        val parent = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("wirelessLinkControls", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, parent)
        activity.setContentView(parent)
        descendants(parent).filterIsInstance<Button>().first { it.text.startsWith("Edit saved hotspot") }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }

    private fun securityButton(dialog: AlertDialog): Button = descendants(dialog.window!!.decorView)
        .filterIsInstance<Button>().first { it.text.startsWith("Security ·") }

    private fun chooseSecurity(dialog: AlertDialog, name: String) {
        securityButton(dialog).performClick()
        val choices = ShadowAlertDialog.getLatestAlertDialog()
        val index = (0 until choices.listView.adapter.count).first { choices.listView.adapter.getItem(it).toString() == name }
        choices.listView.performItemClick(null, index, index.toLong())
        choices.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Security · $name", securityButton(dialog).text.toString())
    }

    private fun inputs(dialog: AlertDialog) = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().toList()
    private fun save(dialog: AlertDialog) = dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
