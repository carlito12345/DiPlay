package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.io.File

/** Strict projection target, discovered afresh through the authorized shell. */
internal object AdbClusterRouter {
    private const val REPORT = "adb-cluster-route.txt"
    data class Result(val success: Boolean, val report: String)

    /** Known 5/5.1 displays win; the explicit legacy route may own the measured projection task. */
    fun enabled(context: Context): Boolean {
        if (DiLink51ClusterLayout.supported()) return false
        val public = ClusterMapPresentation.findDisplay(context)
        if (AirPlayPersistence.loadLegacyClusterEnabled(context)) {
            // Only the measured projection may use the independent task; public 5/5.1 wins.
            return public == null || DiLink4ClusterDisplay.matches(public.name,
                ClusterMapPresentation.sizeOf(public).x, ClusterMapPresentation.sizeOf(public).y)
        }
        return AirPlayPersistence.loadAdbClusterEnabled(context) && public == null
    }

    // Match only the base logical display, not a device's layer-stack number or override record.
    internal fun displayId(dump: String): Int? {
        val candidates = dump.lineSequence().mapNotNull { line ->
            if (!line.contains("mBaseDisplayInfo=DisplayInfo{\"${DiLink4ClusterDisplay.NAME}, displayId ") ||
                !Regex("\\breal 1920 x 720\\b").containsMatchIn(line) ||
                !line.contains("owner com.xdja.containerservice (uid 1000)")) return@mapNotNull null
            Regex("displayId (\\d+)\"").find(line)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
        }.distinct().toList()
        return candidates.singleOrNull()
    }

    // Direct shell launch, following Hanxu4131's legacy platform-21 adapter.
    internal fun launchCommand(pkg: String, display: Int, token: String): String {
        require(display > 0)
        require(Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+").matches(pkg))
        require(runCatching { java.util.UUID.fromString(token).toString() == token }.getOrDefault(false))
        return "am start-activity --display $display -f 0x18000000 " +
            "-n $pkg/com.shilapi.xcertplay.AdbClusterActivity --es cluster_launch_token $token"
    }

    internal fun accepted(output: String): Boolean =
        (output.contains("Starting: Intent {") || Regex("(?m)^Status: ok\\s*$").containsMatchIn(output)) &&
        !Regex("(?i)error|exception|permission\\s*deni(?:al|ed)").containsMatchIn(output)

    internal fun activityDisplay(dump: String, pkg: String, task: Int): Int? {
        var display: Int? = null
        val matches = mutableListOf<Int>()
        val component = "$pkg/com.shilapi.xcertplay.AdbClusterActivity"
        for (line in dump.lineSequence()) {
            val header = Regex("^Display #(\\d+) \\(activities from top to bottom\\):\\s*$").matchEntire(line)
            if (header != null) { display = header.groupValues[1].toInt(); continue }
            if (line.isNotEmpty() && !line.first().isWhitespace()) display = null
            val current = display ?: continue
            if (Regex("^\\s{4,}\\* Hist #\\d+: ActivityRecord\\{").containsMatchIn(line) &&
                Regex("\\bu\\d+\\s+" + Regex.escape(component) + "(?=\\s|,)").containsMatchIn(line) &&
                Regex("\\bt$task(?=\\s|\\})").containsMatchIn(line)) matches.add(current)
        }
        return matches.singleOrNull()?.takeIf { it > 0 }
    }

    fun launch(context: Context, token: String, holdStockMap: Boolean = true, prepare: (Int) -> Boolean): Result {
        var success = false
        val text = buildString {
            appendLine("ADB direct cluster launch capturedAt=${java.util.Date()}")
            appendLine("platform21Route=" + AirPlayPersistence.loadLegacyClusterEnabled(context))
            appendLine("diLink3ModeSwitchSuppressed=" + (AirPlayPersistence.loadAdbClusterEnabled(context) || AirPlayPersistence.loadLegacyClusterEnabled(context)))
            appendLine("calibrationOnly=${!holdStockMap}")
            appendLine("stockMapHoldMode=" + com.shilapi.xcertplay.hud.BydOutputSettings.oemClusterHold(context))
            try {
                LocalAdb(AdbKeys.load(context)).use { adb ->
                    val access = adb.connect(mayAsk = false)
                    appendLine("adbAccess=$access")
                    if (access != LocalAdb.Access.READY) return@use
                    val display = displayId(adb.shell("dumpsys display").orEmpty())
                    appendLine("routeTarget=${display ?: "none"}")
                    if (display == null || !enabled(context) || !prepare(display)) return@use
                    val held = AirPlayPersistence.loadLegacyClusterEnabled(context) || !holdStockMap || com.shilapi.xcertplay.hud.BydOemClusterNavi.holdForLaunch(context, token) {
                        enabled(context) && prepare(display)
                    }
                    appendLine("stockMapHoldReady=$held")
                    if (!held || !enabled(context) || !prepare(display)) return@use
                    val output = adb.shell(launchCommand(context.packageName, display, token)).orEmpty()
                    success = accepted(output)
                    appendLine(output.take(1500))
                    appendLine("launchAccepted=$success; awaiting actual display confirmation")
                }
            } catch (error: Exception) {
                appendLine("routeError=${error.javaClass.simpleName}: ${error.message}")
            }
        }
        // A failed diagnostic write must not leave launchPending stuck forever.
        runCatching { File(context.filesDir, REPORT).writeText(text) }
        return Result(success, text)
    }

    fun verify(context: Context, task: Int): Int? = runCatching {
        LocalAdb(AdbKeys.load(context)).use { adb ->
            if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) null
            else activityDisplay(adb.shell("dumpsys activity activities").orEmpty(), context.packageName, task)
        }
    }.getOrNull()

    fun report(context: Context): String = File(context.filesDir, REPORT).let {
        if (it.isFile) it.readText() else "ADB cluster routing has not been run."
    }
}
