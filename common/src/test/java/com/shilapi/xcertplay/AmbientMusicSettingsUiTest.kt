package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AmbientMusicSettings
import java.util.concurrent.CompletableFuture
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29], qualifiers = "en", manifest = Config.NONE)
class AmbientMusicSettingsUiTest {
    private lateinit var activity: DiPlayActivity

    @Before fun setUp() {
        RuntimeEnvironment.getApplication().getSharedPreferences("settings_ambient_music", 0).edit().clear().commit()
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
    }

    @Test fun cancelDoesNotApplyAnEnabledDraftOrCheckAccess() {
        activity.ambientSupportCheck = { fail("Cancel must not start an access check"); CompletableFuture.completedFuture(false) }
        val dialog = open()
        enableSwitch(dialog).isChecked = true
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(AmbientMusicSettings.load(activity).enabled)
    }

    @Test fun failedReadOnlyCheckLeavesControlDisabledAndExplainsWhy() {
        var checks = 0
        activity.ambientSupportCheck = { checks++; CompletableFuture.completedFuture(false) }
        val dialog = open()
        enableSwitch(dialog).isChecked = true
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, checks)
        assertFalse(AmbientMusicSettings.load(activity).enabled)
        assertTrue(dialog.isShowing)
        assertEquals(activity.getString(R.string.settings_ambient_unavailable), ShadowToast.getTextOfLatestToast())
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
    }

    @Test fun canceledDialogIgnoresACompletedSupportCheck() {
        val pending = CompletableFuture<Boolean>()
        activity.ambientSupportCheck = { pending }
        val dialog = open()
        enableSwitch(dialog).isChecked = true
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        pending.complete(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(AmbientMusicSettings.load(activity).enabled)
    }

    private fun open(): AlertDialog {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "showAmbientConfiguration")
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowAlertDialog.getLatestAlertDialog()
    }

    private fun enableSwitch(dialog: AlertDialog): Switch = descendants(dialog.window!!.decorView)
        .filterIsInstance<Switch>().single { it.contentDescription == activity.getString(R.string.settings_ambient_enable) }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }
}
