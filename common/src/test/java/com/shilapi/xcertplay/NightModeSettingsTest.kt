package com.shilapi.xcertplay

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "en", manifest = Config.NONE)
class NightModeSettingsTest {
    private lateinit var controller: ActivityController<DiPlayActivity>
    private lateinit var activity: DiPlayActivity
    private lateinit var page: LinearLayout

    @Before fun setUp() {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        controller.setup().visible()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        CarPlayBackgroundSession.clear()
    }

    @Test fun savedModesShowAmbientSettingsOnlyForAmbientLight() {
        for (mode in CarPlayNightMode.entries) {
            AirPlayPersistence.saveCarPlayNightMode(activity, mode)
            renderSettings()
            assertAmbientVisible(mode == CarPlayNightMode.AMBIENT)
            assertEquals(mode == CarPlayNightMode.SCHEDULE, button(R.string.carplay_night_start).isShown)
            assertEquals(mode == CarPlayNightMode.SCHEDULE, button(R.string.carplay_night_end).isShown)
        }
    }

    @Test fun savingModeUpdatesVisibilityInPlaceAndKeepsEditedValues() {
        AirPlayPersistence.saveCarPlayNightMode(activity, CarPlayNightMode.AMBIENT)
        renderSettings()
        editNumber(R.string.ambient_light_threshold_title, 75)
        editNumber(R.string.ambient_delay_title, 7)
        val threshold = button(R.string.ambient_light_threshold_title)
        val delay = button(R.string.ambient_delay_title)

        for (mode in listOf(CarPlayNightMode.SYSTEM, CarPlayNightMode.DAY,
            CarPlayNightMode.NIGHT, CarPlayNightMode.SCHEDULE, CarPlayNightMode.AMBIENT)) {
            selectMode(mode).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(mode, AirPlayPersistence.loadCarPlayNightMode(activity))
            assertAmbientVisible(mode == CarPlayNightMode.AMBIENT)
            assertScheduleVisible(mode == CarPlayNightMode.SCHEDULE)
            assertSame(threshold, button(R.string.ambient_light_threshold_title))
            assertSame(delay, button(R.string.ambient_delay_title))
            assertEquals(75, AirPlayPersistence.loadAmbientLightThreshold(activity).lux)
            assertEquals(7, AirPlayPersistence.loadAmbientDelaySeconds(activity))
        }
        renderSettings()
        assertAmbientVisible(true)
        assertTrue(button(R.string.ambient_light_threshold_title).text.toString().endsWith("75 lux"))
        assertTrue(button(R.string.ambient_delay_title).text.toString().endsWith("7 s"))
    }

    @Test fun cancellingModeChoiceKeepsSavedModeAndVisibility() {
        for (mode in listOf(CarPlayNightMode.SYSTEM, CarPlayNightMode.AMBIENT, CarPlayNightMode.SCHEDULE)) {
            AirPlayPersistence.saveCarPlayNightMode(activity, mode)
            renderSettings()
            val target = if (mode == CarPlayNightMode.SYSTEM) CarPlayNightMode.AMBIENT else CarPlayNightMode.SYSTEM
            val dialog = selectMode(target)
            assertAmbientVisible(mode == CarPlayNightMode.AMBIENT)
            assertScheduleVisible(mode == CarPlayNightMode.SCHEDULE)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(mode, AirPlayPersistence.loadCarPlayNightMode(activity))
            assertAmbientVisible(mode == CarPlayNightMode.AMBIENT)
        }
    }

    @Test fun scheduleTimePickersSaveEachBoundaryAndCancelWithoutChangingTheSchedule() {
        AirPlayPersistence.saveCarPlayNightMode(activity, CarPlayNightMode.SCHEDULE)
        renderSettings()
        val start = button(R.string.carplay_night_start)
        val end = button(R.string.carplay_night_end)
        timeDialog(R.string.carplay_night_start, 19, 30).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        timeDialog(R.string.carplay_night_end, 5, 15).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val saved = CarPlayNightSchedule(19 * 60 + 30, 5 * 60 + 15)
        assertEquals(saved, AirPlayPersistence.loadCarPlayNightSchedule(activity))
        assertTrue(start.text.toString().endsWith("19:30"))
        assertTrue(end.text.toString().endsWith("05:15"))
        timeDialog(R.string.carplay_night_start, 21, 45).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(saved, AirPlayPersistence.loadCarPlayNightSchedule(activity))
        assertTrue(start.text.toString().endsWith("19:30"))
        assertSame(start, button(R.string.carplay_night_start))
        assertSame(end, button(R.string.carplay_night_end))
    }

    @Test fun equalScheduleTimesAreRejectedWithoutSavingOrChangingTheLabel() {
        AirPlayPersistence.saveCarPlayNightMode(activity, CarPlayNightMode.SCHEDULE)
        renderSettings()
        val before = button(R.string.carplay_night_start).text.toString()
        timeDialog(R.string.carplay_night_start, 6, 0).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(CarPlayNightSchedule(), AirPlayPersistence.loadCarPlayNightSchedule(activity))
        assertEquals(before, button(R.string.carplay_night_start).text.toString())
        assertEquals(activity.getString(R.string.carplay_night_schedule_same_time), ShadowToast.getTextOfLatestToast())
    }

    @Test fun overviewRendersEverySavedAppearanceWithItsMatchingChoice() {
        for (mode in CarPlayNightMode.entries) {
            AirPlayPersistence.saveCarPlayNightMode(activity, mode)
            renderOverview()

            assertEquals(appearanceSummary(mode), button(R.string.carplay_night_mode).text.toString())
            button(R.string.carplay_night_mode).performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(CarPlayNightMode.entries.size, dialog.listView.adapter.count)
            assertEquals(CarPlayNightMode.entries.indexOf(mode), dialog.listView.checkedItemPosition)
            assertEquals(activity.getString(modeLabel(mode)),
                dialog.listView.adapter.getItem(dialog.listView.checkedItemPosition))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertEquals(mode, AirPlayPersistence.loadCarPlayNightMode(activity))
        }
    }

    @Test fun choosingScheduledAppearanceFromOverviewKeepsTheDisplaySchedule() {
        val schedule = CarPlayNightSchedule(20 * 60 + 15, 5 * 60 + 45)
        AirPlayPersistence.saveCarPlayNightSchedule(activity, schedule)
        AirPlayPersistence.saveCarPlayNightMode(activity, CarPlayNightMode.SYSTEM)
        renderOverview()

        val dialog = selectMode(CarPlayNightMode.SCHEDULE)
        assertEquals(activity.getString(R.string.carplay_night_schedule),
            dialog.listView.adapter.getItem(CarPlayNightMode.entries.indexOf(CarPlayNightMode.SCHEDULE)))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(CarPlayNightMode.SCHEDULE, AirPlayPersistence.loadCarPlayNightMode(activity))
        assertEquals(schedule, AirPlayPersistence.loadCarPlayNightSchedule(activity))
        assertEquals(appearanceSummary(CarPlayNightMode.SCHEDULE), button(R.string.carplay_night_mode).text.toString())
        renderSettings()
        assertEquals(appearanceSummary(CarPlayNightMode.SCHEDULE), button(R.string.carplay_night_mode).text.toString())
        assertScheduleVisible(true)
        assertTrue(button(R.string.carplay_night_start).text.toString().endsWith("20:15"))
        assertTrue(button(R.string.carplay_night_end).text.toString().endsWith("05:45"))
    }

    @Test fun searchingSettingsWithScheduledAppearanceKeepsTheSavedMode() {
        AirPlayPersistence.saveCarPlayNightMode(activity, CarPlayNightMode.SCHEDULE)
        renderOverview()

        val index = ReflectionHelpers.callInstanceMethod<List<DiPlayActivity.SettingsSearchResult>>(
            activity, "buildSettingsSearchIndex")

        assertTrue(index.any {
            it.title == activity.getString(R.string.carplay_night_mode) && it.category == SettingsCategory.DISPLAY
        })
        assertEquals(CarPlayNightMode.SCHEDULE, AirPlayPersistence.loadCarPlayNightMode(activity))
        val current = ReflectionHelpers.getField<ScrollView>(activity, "rootScroll")
        val appearance = views(current).filterIsInstance<Button>().single {
            it.text.startsWith(activity.getString(R.string.carplay_night_mode) + " · ")
        }
        assertEquals(appearanceSummary(CarPlayNightMode.SCHEDULE), appearance.text.toString())
    }

    private fun modeLabel(mode: CarPlayNightMode): Int = when (mode) {
        CarPlayNightMode.SYSTEM -> R.string.carplay_night_system
        CarPlayNightMode.AMBIENT -> R.string.carplay_night_ambient
        CarPlayNightMode.DAY -> R.string.carplay_night_day
        CarPlayNightMode.NIGHT -> R.string.carplay_night_night
        CarPlayNightMode.SCHEDULE -> R.string.carplay_night_schedule
    }

    private fun appearanceSummary(mode: CarPlayNightMode) =
        activity.getString(R.string.carplay_night_mode) + " · " + activity.getString(modeLabel(mode))

    private fun renderOverview() {
        ReflectionHelpers.setField(activity, "page", "settings")
        ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.OVERVIEW)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        page = ReflectionHelpers.getField<ScrollView>(activity, "rootScroll").getChildAt(0) as LinearLayout
    }

    private fun renderSettings() {
        page = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("displaySettings", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, page)
        activity.setContentView(page)
    }

    private fun assertAmbientVisible(expected: Boolean) {
        for (id in listOf(R.string.ambient_light_threshold_title, R.string.ambient_delay_title)) {
            assertEquals(activity.getString(id), expected, button(id).isShown)
        }
        assertEquals(expected, label(R.string.carplay_night_ambient_hint).isShown)
        val nightHint = "${activity.getString(R.string.carplay_night_hint)} ${activity.getString(R.string.carplay_night_time_note)}"
        assertTrue(nightHint, views(page).filterIsInstance<TextView>().single { it.text == nightHint }.isShown)
        assertTrue(activity.getString(R.string.picture_adjustments), label(R.string.picture_adjustments).isShown)
    }

    private fun assertScheduleVisible(expected: Boolean) {
        for (id in listOf(R.string.carplay_night_start, R.string.carplay_night_end)) {
            assertEquals(activity.getString(id), expected, button(id).isShown)
        }
    }

    private fun timeDialog(title: Int, hour: Int, minute: Int): TimePickerDialog {
        button(title).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as TimePickerDialog
        shadowOf(Looper.getMainLooper()).idle()
        dialog.updateTime(hour, minute)
        return dialog
    }

    private fun selectMode(mode: CarPlayNightMode): AlertDialog {
        button(R.string.carplay_night_mode).performClick()
        return ShadowAlertDialog.getLatestAlertDialog().also {
            it.listView.performItemClick(null, CarPlayNightMode.entries.indexOf(mode), 0)
        }
    }

    private fun editNumber(title: Int, value: Int) {
        button(title).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        shadowOf(Looper.getMainLooper()).idle()
        views(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText(value.toString())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun button(title: Int) = views(page).filterIsInstance<Button>().single {
        it.text.startsWith(activity.getString(title) + " · ")
    }

    private fun label(text: Int) = views(page).filterIsInstance<TextView>().single {
        it.text == activity.getString(text)
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
