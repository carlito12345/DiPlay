package com.shilapi.xcertplay

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29], qualifiers = "en", shadows = [VpnConsentTestShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayVpnConsentTest {
    private lateinit var activity: CarPlayHostActivity
    private var launches = 0

    @Before fun setUp() {
        VpnConsentTestShadow.failure = null
        VpnConsentTestShadow.consent = Intent().setClassName(
            "com.android.vpndialogs", "com.android.vpndialogs.ConfirmDialog",
        )
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(activity, "stageStatusView", TextView(activity))
        invoke("initializeSessionLog")
        replaceLauncher { launches += 1 }
    }

    @After fun tearDown() {
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        ReflectionHelpers.getField<ExecutorService>(activity, "teardownExecutor").shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(activity, "airPlayCommandExecutor").shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    @Test fun missingConsentDialogDoesNotCrashOrLeaveAuthorizationPending() {
        ReflectionHelpers.setField(activity, "vpnReady", true)
        replaceLauncher { throw ActivityNotFoundException("Missing OEM VPN dialog") }

        invoke("requestVpnConsent")

        assertUnavailable()
        assertTrue(log().contains("failureClass=ActivityNotFoundException"))
        assertTrue(log().contains("component=com.android.vpndialogs/com.android.vpndialogs.ConfirmDialog"))
    }

    @Test fun deniedConsentLaunchDoesNotCrashOrUnlockUsb() {
        replaceLauncher { throw SecurityException("OEM blocked VPN activity") }
        invoke("requestVpnConsent")
        assertUnavailable()
        assertTrue(log().contains("operation=launch"))
        assertTrue(log().contains("failureClass=SecurityException"))
    }

    @Test fun deniedPrepareDoesNotCrashOrLeaveStaleAuthorization() {
        ReflectionHelpers.setField(activity, "vpnReady", true)
        VpnConsentTestShadow.failure = SecurityException("OEM blocked VPN preparation")
        invoke("requestVpnConsent")
        assertUnavailable()
        assertEquals(0, launches)
        assertTrue(log().contains("operation=prepare"))
    }

    @Test fun grantedAuthorizationDoesNotOpenAConsentActivity() {
        VpnConsentTestShadow.consent = null
        invoke("requestVpnConsent")
        assertTrue(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
        assertEquals(0, launches)
    }

    @Test fun pendingAuthorizationDoesNotLaunchTwice() {
        repeat(2) { invoke("requestVpnConsent") }
        assertFalse(field("vpnReady"))
        assertTrue(field("awaitingVpnConsent"))
        assertEquals(1, launches)
    }

    @Test fun failedLaunchCanBeRetried() {
        replaceLauncher { throw ActivityNotFoundException("Missing OEM VPN dialog") }
        invoke("requestVpnConsent")
        replaceLauncher { launches += 1 }
        invoke("requestVpnConsent")
        assertTrue(field("awaitingVpnConsent"))
        assertFalse(field("vpnReady"))
        assertEquals(1, launches)
    }

    @Test fun lateSuccessfulResultAfterFailedLaunchDoesNotUnlockUsb() {
        replaceLauncher { throw ActivityNotFoundException("Missing OEM VPN dialog") }
        invoke("requestVpnConsent")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onVpnConsentResult",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_OK))
        assertFalse(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
        assertNull(ReflectionHelpers.getField<Any?>(activity, "controller"))
    }

    @Test fun resultOfPendingConsentCompletesOrDeniesAuthorization() {
        invoke("requestVpnConsent")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onVpnConsentResult",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_OK))
        assertTrue(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
        invoke("requestVpnConsent")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onVpnConsentResult",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_CANCELED))
        assertFalse(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
    }

    private fun replaceLauncher(launch: (Intent) -> Unit) {
        ReflectionHelpers.setField(activity, "vpnConsent", object : ActivityResultLauncher<Intent>() {
            override fun launch(input: Intent, options: ActivityOptionsCompat?) = launch(input)
            override fun unregister() = Unit
            override fun getContract() = ActivityResultContracts.StartActivityForResult()
        })
    }

    private fun assertUnavailable() {
        assertFalse(field("vpnReady"))
        assertFalse(field("awaitingVpnConsent"))
        assertNull(ReflectionHelpers.getField<Any?>(activity, "controller"))
        val message = ReflectionHelpers.getField<TextView>(activity, "stageStatusView").text.toString()
        assertTrue(message.contains("VPN authorization"))
        assertTrue(message.contains("Return to DiPlay"))
        assertFalse(message.contains("Exception"))
        assertFalse(message.contains("adb shell"))
    }

    private fun field(name: String): Boolean = ReflectionHelpers.getField(activity, name)
    private fun log() = File(activity.filesDir, "logs/diplay.log").readText()
    private fun invoke(name: String) {
        try { activity.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity) }
        catch (error: InvocationTargetException) { throw error.targetException }
    }
}

@Implements(VpnService::class)
class VpnConsentTestShadow {
    companion object {
        var consent: Intent? = null
        var failure: RuntimeException? = null
        @JvmStatic @Implementation fun prepare(context: Context): Intent? {
            failure?.let { throw it }
            return consent
        }
    }
}
