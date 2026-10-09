package com.shilapi.xcertplay.hud

import android.annotation.SuppressLint
import android.content.Context
import java.io.InputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Token-bound app_process bridge. It accepts only the explicit interior-lamp operations below. */
internal object BydAmbientLightWorkerProtocol {
    private const val MAX_LINE_BYTES = 512
    private val tokenPattern = Regex("[0-9a-f]{32}")
    data class Request(val op: String, val args: List<Int> = emptyList())
    fun validToken(token: String) = tokenPattern.matches(token)

    fun parse(line: String, token: String): Request? {
        if (!validToken(token) || line.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES) return null
        val f = line.split(' ')
        if (f.firstOrNull() != token) return null
        if (f.size == 2 && f[1] in setOf("ping", "read", "begin", "stop", "minimum-stop", "close")) return Request(f[1])
        if (f.size == 7 && f[1] == "begin") {
            val a = f.drop(2).map { it.toIntOrNull() ?: return null }
            val seed = BydAmbientLightPolicy.Snapshot(a[0], a[1], a[2], a[3], a[4])
            return if (BydAmbientLightPolicy.validSnapshot(seed)) Request("begin", a) else null
        }
        if (f.size == 5 && f[1] == "apply") {
            val a = f.drop(2).map { it.toIntOrNull() ?: return null }
            return if (BydAmbientLightPolicy.validApply(a[0], a[1], a[2])) Request("apply", a) else null
        }
        return null
    }

    fun snapshotForBegin(request: Request, readCurrent: () -> BydAmbientLightPolicy.Snapshot): BydAmbientLightPolicy.Snapshot {
        require(request.op == "begin")
        val a = request.args
        val snapshot = if (a.isEmpty()) readCurrent() else {
            require(a.size == 5)
            BydAmbientLightPolicy.Snapshot(a[0], a[1], a[2], a[3], a[4])
        }
        require(BydAmbientLightPolicy.validSnapshot(snapshot))
        return snapshot
    }

    fun readLine(input: InputStream): String? {
        val b = java.io.ByteArrayOutputStream()
        repeat(MAX_LINE_BYTES + 1) {
            val c = input.read()
            if (c < 0) return null
            if (c == 10) return b.toString("UTF-8")
            b.write(c)
        }
        return null
    }
}

/** Called by app_process under the authorized shell identity, not from the app's restricted UID. */
object BydAmbientLightTool {
    @JvmStatic fun main(args: Array<String>) {
        if (args.size != 2 || args[0] != "--stdin") return
        val token = args[1].takeIf(BydAmbientLightWorkerProtocol::validToken) ?: return
        // A blocked old Binder/restore must exclude new writes even beyond its 15-second lease.
        val ownership = runCatching { AmbientWorkerOwnerLock.tryAcquire(File("/data/local/tmp/diplay-ambient-worker.lock")) }.getOrNull()
        if (ownership == null) {
            println("error worker-busy"); println("done"); System.out.flush()
            return // Do not initialize the SDK before acquiring ownership.
        }
        try { runOwned(token) } finally { ownership.close() }
    }

    private fun runOwned(token: String) {
        val lease = java.util.concurrent.atomic.AtomicLong(System.nanoTime())
        val snapshotRef = AtomicReference<BydAmbientLightPolicy.Snapshot?>(null)
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        val device = runCatching { AmbientDevice() }.getOrNull()
        val restoreLock = Any()
        watchdog.scheduleAtFixedRate({
            if (System.nanoTime() - lease.get() > TimeUnit.MILLISECONDS.toNanos(BydAmbientLightPolicy.LEASE_MILLIS)) {
                synchronized(restoreLock) {
                    if (System.nanoTime() - lease.get() > TimeUnit.MILLISECONDS.toNanos(BydAmbientLightPolicy.LEASE_MILLIS)) {
                        val saved = snapshotRef.get()
                        if (saved == null) System.exit(0)
                        if (saved != null && device != null) {
                            val ok = runCatching { restore(device, saved); true }.getOrDefault(false)
                            if (ok) {
                                snapshotRef.compareAndSet(saved, null)
                                System.exit(0)
                            } else {
                                System.err.println("ambient lease restore failed; retaining snapshot and retrying")
                            }
                        }
                    }
                }
            }
        }, 1, 1, TimeUnit.SECONDS)
        try {
            if (device == null) { println("error init"); println("done"); System.out.flush(); return }
            var lastSetNanos = 0L
            var stop = false
            while (!stop) {
                val line = BydAmbientLightWorkerProtocol.readLine(System.`in`) ?: break
                val req = BydAmbientLightWorkerProtocol.parse(line, token) ?: break
                lease.set(System.nanoTime())
                val out = synchronized(restoreLock) { try {
                    when (req.op) {
                        "ping" -> "ready"
                        "read" -> "state=${device.read().encode()}"
                        "begin" -> {
                            if (snapshotRef.get() != null) "error already-begun"
                            else {
                                val s = BydAmbientLightWorkerProtocol.snapshotForBegin(req, device::read)
                                if (!BydAmbientLightPolicy.validSnapshot(s)) "error unsupported-state"
                                else { snapshotRef.compareAndSet(null, s); "begun=${s.encode()}" }
                            }
                        }
                        "apply" -> {
                            val s = snapshotRef.get() ?: throw IllegalStateException("begin-required")
                            val (area, color, bright) = req.args
                            if (!BydAmbientLightPolicy.validSnapshot(s)) throw IllegalStateException("unsupported-state")
                            val wait = TimeUnit.NANOSECONDS.toMillis(200_000_000L - (System.nanoTime() - lastSetNanos)).coerceAtLeast(0)
                            if (wait > 0) Thread.sleep(wait)
                            val result = device.set(color, bright, area)
                            lastSetNanos = System.nanoTime()
                            if (result != 0) throw IllegalStateException("set-result-$result")
                            awaitReadback(device) { r -> when (area) {
                                1 -> r.frontColor == color && r.frontBrightness == bright && r.area == area
                                2 -> r.backColor == color && r.backBrightness == bright && r.area == area
                                else -> r.frontColor == color && r.backColor == color && r.frontBrightness == bright && r.backBrightness == bright && r.area == area
                            } }
                            "applied=${device.read().encode()}"
                        }
                        "minimum-stop" -> {
                            check(snapshotRef.get() != null) { "begin-required" }
                            val minimum = holdAtMinimum(device)
                            // Commit only after both zones and the original current area read back.
                            snapshotRef.set(null)
                            stop = true
                            "minimum=${minimum.encode()}"
                        }
                        "stop" -> {
                            val s = snapshotRef.get()
                            val restored = if (s == null) "no-snapshot" else { restore(device, s); "restored=${device.read().encode()}" }
                            snapshotRef.set(null); stop = true; restored
                        }
                        "close" -> { snapshotRef.get()?.let { restore(device, it) }; snapshotRef.set(null); stop = true; "closed-restored" }
                        else -> throw IllegalArgumentException("operation")
                    }
                } catch (t: Throwable) {
                    val s = snapshotRef.get()
                    val restored = s == null || runCatching { restore(device, s); true }.getOrDefault(false)
                    if (restored) snapshotRef.set(null)
                    stop = true
                    "error=${safeError(t)}"
                } }
                println(out); println("done"); System.out.flush()
            }
        } catch (_: Throwable) {
            // The finally path attempts the one saved restoration on stream failure/EOF.
        } finally {
            synchronized(restoreLock) { snapshotRef.get()?.let { s ->
                val ok = runCatching { device?.let { restore(it, s) }; true }.getOrDefault(false)
                if (ok) snapshotRef.compareAndSet(s, null)
                else System.err.println("ambient restore failed; retry did not confirm readback")
            } }
            watchdog.shutdownNow()
            System.exit(0)
        }
    }

    private fun holdAtMinimum(device: AmbientDevice): BydAmbientLightPolicy.Snapshot {
        val current = device.read()
        val target = BydAmbientLightPolicy.minimumTarget(current)
        for (step in BydAmbientLightPolicy.minimumSteps(current)) {
            val values = step.values
            if (values != null) {
                check(device.set(values.color, values.rawBrightness, values.area) == 0) { "minimum-set-failed" }
                awaitReadback(device) { read -> when (values.area) {
                    1 -> read.frontColor == target.frontColor && read.frontBrightness == 1 &&
                        read.backColor == current.backColor && read.area == 1
                    2 -> read.frontColor == target.frontColor && read.frontBrightness == 1 &&
                        read.backColor == target.backColor && read.backBrightness == 1 && read.area == 2
                    else -> false
                } }
            } else {
                check(device.setArea(requireNotNull(step.areaOnly)) == 0) { "minimum-area-failed" }
                awaitReadback(device) { it == target }
            }
        }
        return awaitReadback(device) { it == target }
    }

    private fun restore(device: AmbientDevice, snapshot: BydAmbientLightPolicy.Snapshot) {
        val steps = BydAmbientLightPolicy.restoreSteps(snapshot)
        for (step in steps) {
            val v = step.values
            if (v != null) check(device.set(v.color, v.rawBrightness, v.area) == 0) { "restore-set-failed" }
            else check(device.setArea(step.areaOnly!!) == 0) { "restore-area-failed" }
            awaitReadback(device) { r -> when {
                v?.area == 1 -> r.frontColor == v.color && r.frontBrightness == v.rawBrightness && r.area == 1
                v?.area == 2 -> r.backColor == v.color && r.backBrightness == v.rawBrightness && r.area == 2
                v?.area == 3 -> r.frontColor == snapshot.frontColor && r.backColor == snapshot.backColor &&
                    r.frontBrightness == snapshot.frontBrightness && r.backBrightness == snapshot.backBrightness && r.area == 3
                step.areaOnly != null -> r.area == step.areaOnly
                else -> false
            }
            }
        }
        awaitReadback(device) { it == snapshot }
    }

    private fun awaitReadback(device: AmbientDevice, predicate: (BydAmbientLightPolicy.Snapshot) -> Boolean): BydAmbientLightPolicy.Snapshot {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500)
        var last: BydAmbientLightPolicy.Snapshot? = null
        while (System.nanoTime() < until) {
            last = device.read()
            if (predicate(last)) return last
            Thread.sleep(BydAmbientLightPolicy.READBACK_POLL_MILLIS)
        }
        error("readback-timeout:${last?.encode()}")
    }

    private fun safeError(t: Throwable): String = ((t.cause ?: t).javaClass.simpleName + ":" + (t.cause ?: t).message.orEmpty())
        .replace(Regex("[\\r\\n ]+"), " ").take(120)
}

private fun BydAmbientLightPolicy.Snapshot.encode() = "${frontColor},${backColor},${frontBrightness},${backBrightness},${area}"

@SuppressLint("PrivateApi")
private class AmbientDevice {
    private val manager: Any
    private val getInt: java.lang.reflect.Method
    private val setIntArray: java.lang.reflect.Method
    private val setInt: java.lang.reflect.Method
    private val ids: Map<String, Int>

    init {
        runCatching { android.os.Looper.prepareMainLooper() }
        val thread = Class.forName("android.app.ActivityThread")
        val main = thread.getMethod("systemMain").invoke(null)
        val systemContext = thread.getMethod("getSystemContext").invoke(main) as Context
        val context = systemContext.createPackageContext("com.android.shell", 0)
        val managerClass = Class.forName("android.hardware.bydauto.BYDAutoDeviceManager")
        manager = requireNotNull(managerClass.getMethod("getInstance", Context::class.java).invoke(null, context)) { "manager-null" }
        getInt = managerClass.getMethod("getInt", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        setIntArray = managerClass.methods.firstOrNull { it.name == "setIntArray" && it.parameterTypes.contentEquals(arrayOf(
            Int::class.javaPrimitiveType, IntArray::class.java, IntArray::class.java)) }
            ?: throw NoSuchMethodException("BYDAutoDeviceManager.setIntArray(int,int[],int[])")
        setInt = managerClass.getMethod("setInt", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        ids = BydAmbientLightPolicy.getNames.associateWith(::featureId) + BydAmbientLightPolicy.setNames.associateWith(::featureId)
    }

    private fun featureId(name: String): Int {
        val cls = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds\$Setting")
        return cls.getField(name).getInt(null)
    }
    private fun get(name: String): Int = (getInt.invoke(manager, BydAmbientLightPolicy.TYPE, ids.getValue(name)) as Number).toInt()
    fun read(): BydAmbientLightPolicy.Snapshot {
        check(get("SET_HAS_INTERIOR_ATMOSPHERE_LAMP") == 1) { "lamp-not-supported" }
        val s = BydAmbientLightPolicy.Snapshot(get("SET_IAL_FRONT_COLOR"), get("SET_IAL_BACK_COLOR"),
            get("SET_IAL_FRONT_BRIGHTNESS"), get("SET_IAL_BACK_BRIGHTNESS"), get("SET_INTERIOR_ATMOSPHERE_LAMP_AREA"))
        check(BydAmbientLightPolicy.validSnapshot(s)) { "invalid-lamp-state" }
        return s
    }
    fun set(color: Int, brightness: Int, area: Int): Int {
        require(BydAmbientLightPolicy.validApply(area, color, brightness))
        val method = setIntArray
        return (method.invoke(manager, BydAmbientLightPolicy.TYPE,
            intArrayOf(ids.getValue("SET_INTERIOR_ATMOSPHERE_LAMP_COLOR_SET"), ids.getValue("SET_INTERIOR_ATMOSPHERE_LAMP_BRIGHTNESS_SET"), ids.getValue("SET_INTERIOR_ATMOSPHERE_LAMP_AREA_SET")),
            intArrayOf(color, brightness, area)) as Number).toInt()
    }
    fun setArea(area: Int): Int {
        require(area in 1..3)
        return (setInt.invoke(manager, BydAmbientLightPolicy.TYPE, ids.getValue("SET_INTERIOR_ATMOSPHERE_LAMP_AREA_SET"), area) as Number).toInt()
    }
}
