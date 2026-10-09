package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The four tyres in the order front left, front right, rear left, rear right; null where the car gave nothing usable. */
data class BydTyreReading(val pressureKpa: List<Int?>, val temperatureC: List<Int?>)

/**
 * Tyre pressure and temperature through the adb shell (autoservice binder), read only. Pressure comes from the
 * tyre device (1016), whose addresses are the same in the BYD SDK catalogs seen so far; temperature from the
 * instrument device (1007) on CAN-FD firmware. On my Tang (DiLink 5) both match what the cluster's TPMS page shows.
 */
internal object BydTyres {
    private const val TYRE_DEVICE = 1016
    private val PRESSURE_IDS = listOf(-1728052956, -1728052952, -1728052948, -1728052944)
    private const val INSTRUMENT_DEVICE = 1007
    private val TEMPERATURE_IDS = listOf(1246797848, 1246797860, 1246797872, 1246797884)

    /** True for a reply from a shell without BYD's autoservice (such as "Service autoservice does not exist."). */
    fun noService(reply: String?): Boolean = reply != null && "Parcel(" !in reply

    fun read(shell: (String) -> String?): BydTyreReading? {
        val pressure = PRESSURE_IDS.map { id ->
            BydParcel.value(shell("service call autoservice 5 i32 $TYRE_DEVICE i32 $id"))?.let(::plausiblePressure)
        }
        if (pressure.all { it == null }) return null
        val temperature = TEMPERATURE_IDS.map { id ->
            BydParcel.value(shell("service call autoservice 5 i32 $INSTRUMENT_DEVICE i32 $id"))?.let(::plausibleTemperature)
        }
        return BydTyreReading(pressure, temperature)
    }

    fun plausiblePressure(kpa: Int): Int? = kpa.takeIf { it in 50..600 }

    fun plausibleTemperature(celsius: Int): Int? = celsius.takeIf { it in -50..150 }
}

/**
 * Reads the tyres every few seconds while something shows them (the side panel), and keeps the latest reading.
 * On a head unit without BYD's autoservice it stops after the first reply and does not start again.
 */
object BydTyreStatus {
    private const val TAG = "DiPlay-BYD-Tyres"
    private const val READ_MILLIS = 10_000L
    private const val STALE_MILLIS = 60_000L

    private val shell = BydAdbShell(TAG)
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "diplay-tyres").apply { isDaemon = true }
    }
    private var task: ScheduledFuture<*>? = null
    @Volatile private var latest: BydTyreReading? = null
    @Volatile private var latestMillis = 0L
    @Volatile private var noService = false

    @Synchronized
    fun start(context: Context) {
        if (task != null || noService) return
        val app = context.applicationContext
        task = executor.scheduleWithFixedDelay({ poll(app) }, 0, READ_MILLIS, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun stop() {
        task?.cancel(false)
        task = null
        executor.execute(shell::close)
    }

    /** The latest reading, or null when there is none or it is older than a minute. */
    fun latest(): BydTyreReading? = latest?.takeIf { SystemClock.elapsedRealtime() - latestMillis <= STALE_MILLIS }

    private fun poll(context: Context) {
        var missing = false
        val reading = runCatching {
            BydTyres.read { command -> shell.run(context, command).also { if (BydTyres.noService(it)) missing = true } }
        }.getOrNull()
        if (reading == null) {
            if (missing) {
                noService = true
                Log.i(TAG, "no BYD autoservice; tyres are not read")
                stop()
            }
            return
        }
        if (reading != latest) Log.i(TAG, "tyres kPa ${reading.pressureKpa} °C ${reading.temperatureC}")
        latest = reading
        latestMillis = SystemClock.elapsedRealtime()
    }
}
