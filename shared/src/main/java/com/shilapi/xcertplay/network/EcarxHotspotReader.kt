package com.shilapi.xcertplay.network

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import dalvik.system.PathClassLoader
import java.io.Closeable
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.TimeUnit

/** carlito | Reads the car-owned AP; no configuration, tethering or client limits are changed. */
internal class EcarxHotspotReader(
    private val context: Context,
    private val log: (String) -> Unit = {},
) : Closeable {
    data class Snapshot(
        val enabled: Boolean? = null,
        val wifi6Enabled: Boolean? = null,
        val band: Int? = null,
        val routedHosts: Map<String, InetAddress> = emptyMap(),
        val diagnostic: String = "ecarxHotspot=unavailable",
    )

    private data class Driver(val api: Class<*>, val instance: Any, val connection: Class<*>?)
    private var drivers: List<Driver>? = null
    private var lastRead = Long.MIN_VALUE
    @Volatile private var latest = Snapshot()
    private val failures = mutableSetOf<String>()
    private var closed = false
    private val loader = apiLoader(context)

    @Synchronized
    fun snapshot(): Snapshot {
        if (closed) return Snapshot()
        val now = SystemClock.elapsedRealtime()
        if (lastRead != Long.MIN_VALUE && now - lastRead < 1_000) return latest
        lastRead = now
        val active = drivers ?: API_NAMES.mapNotNull { name ->
            val api = runCatching { Class.forName(name, false, loader) }
                .onFailure { failure(name.substringAfterLast('.'), "load", it) }.getOrNull()
                ?: return@mapNotNull null
            try {
                val instance = api.getMethod("create", Context::class.java).invoke(null, context)
                    ?: return@mapNotNull null
                val connectable = runCatching {
                    Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable", false, loader)
                        .takeIf { it.isInstance(instance) }
                }.getOrNull()
                val connection = connectable?.also {
                    // carlito | Retain ownership even if connect partially succeeds then throws.
                    runCatching { it.getMethod("connect").invoke(instance) }
                        .onFailure { error -> failure(api.simpleName, "connect", error) }
                }
                Driver(api, instance, connection)
            } catch (error: Exception) {
                failure(api.simpleName, "create", error)
                null
            } catch (error: LinkageError) {
                failure(api.simpleName, "create", error)
                null
            }
        }.also { drivers = it.takeIf { connected -> connected.isNotEmpty() } }
        var wifi6Enabled: Boolean? = null
        var band: Int? = null
        var count = 0
        val peers = mutableListOf<InetAddress>()
        val clientMacs = mutableSetOf<String>()
        active.forEach { driver ->
            val enabled = invoke(driver, "getWifi6ApEnabled") as? Boolean
            if (enabled != null) wifi6Enabled = enabled
            val clients = (invoke(driver, "getWifiApClients") as? Iterable<*>)
                ?.filterNotNull().orEmpty()
            count += clients.size
            clients.mapNotNull { client -> runCatching {
                val api = Class.forName(CLIENT_API, false, loader)
                normalizeMac(api.getMethod("getMac").invoke(client) as? String)
            }.getOrNull() }.forEach(clientMacs::add)
            val addresses = clients.mapNotNull { client ->
                runCatching {
                    val api = Class.forName(CLIENT_API, false, loader)
                    numericAddress(api.getMethod("getIP").invoke(client) as? String)
                }.getOrNull()
            }
            peers += addresses
            addresses.forEach { log("LOCAL_NETWORK peer api=${driver.api.simpleName} " +
                "address=${it.hostAddress} family=${if (it is Inet4Address) "IPv4" else "IPv6"} " +
                "scope=${(it as? Inet6Address)?.scopeId ?: 0}") }
            if (enabled == true || clients.isNotEmpty()) {
                val host = invoke(driver, "getWifiAPHost")
                if (host != null) runCatching {
                    val api = Class.forName(HOST_API, false, loader)
                    (api.getMethod("getCurrentFrequencyMode").invoke(host) as? Number)?.toInt()
                }.getOrNull()?.takeIf { it == 1 || it == 2 }?.let { band = it }
            }
        }
        val directRoutes = peers.distinctBy { it.hostAddress }.mapNotNull(::routeToClient)
        // carlito | The SDK can expose only IPv4 even when this process sees only IPv6. Match
        // the currently reported Wi-Fi client's MAC to a real neighbor/interface, never guess scope.
        val neighborRoutes = if (clientMacs.isNotEmpty()) neighborPeers(clientMacs).mapNotNull(::routeToClient)
            else emptyList()
        val routes = (directRoutes + neighborRoutes)
            .groupBy({ it.first }, { it.second }).mapNotNull { (name, hosts) ->
                // carlito | Prefer a verified IPv4 route when both families reach this factory AP.
                wirelessHostAddress(hosts.distinct(),
                    runCatching { NetworkInterface.getByName(name)?.index }.getOrNull() ?: 0)
                    ?.let { name to it }
            }.toMap()
        val verifiedRoutes = routes.takeIf { it.size == 1 }.orEmpty()
        routes.forEach { (name, address) -> log("LOCAL_NETWORK route iface=$name local=${address.hostAddress} result=matched") }
        val enabled = when {
            wifi6Enabled == true || count > 0 -> true
            else -> null // Wifi6 off does not prove that the separate classic AP is off.
        }
        val diagnostic = "ecarxHotspot apis=${active.joinToString(",") { it.api.simpleName }} " +
            "wifi6Enabled=$wifi6Enabled clients=$count addresses=${peers.size} " +
            "routes=${routes.keys.sorted()} band=$band " +
            "path=${if (routes.size > 1) "ambiguous_factory_routes" else if (verifiedRoutes.isNotEmpty()) "verified" else if (peers.isNotEmpty()) "no_verified_local_route" else if (enabled == true) "waiting_for_client" else "unobservable"}"
        if (diagnostic != latest.diagnostic) log(diagnostic)
        // Different APs may be active at once. Without an SSID-to-route association, do not
        // assign the saved credentials to an arbitrary factory path.
        latest = Snapshot(enabled, wifi6Enabled, band, verifiedRoutes, diagnostic)
        return latest
    }

    fun diagnosticSnapshot(): String = latest.diagnostic

    private fun invoke(driver: Driver, method: String): Any? = try {
        driver.api.getMethod(method).invoke(driver.instance)
    } catch (_: NoSuchMethodException) {
        null
    } catch (error: Exception) {
        failure(driver.api.simpleName, method, error)
        null
    } catch (error: LinkageError) {
        failure(driver.api.simpleName, method, error)
        null
    }

    private fun failure(api: String, method: String, error: Throwable) {
        val cause = (error as? InvocationTargetException)?.targetException ?: error
        val diagnostic = "ecarxHotspot api=$api method=$method failed=${cause.javaClass.simpleName}"
        if (failures.add(diagnostic)) log(diagnostic)
    }

    private fun routeToClient(peer: InetAddress): Pair<String, InetAddress>? = runCatching {
        if (!(peer is Inet4Address && peer.isSiteLocalAddress ||
                peer is Inet6Address && peer.isLinkLocalAddress && peer.scopeId > 0)) {
            log("LOCAL_NETWORK route peer=${peer.hostAddress} result=unsupported_address_or_missing_scope")
            return null
        }
        val routed = runCatching { DatagramSocket().use { socket ->
            // connect resolves a local route without sending traffic or binding the process.
            socket.connect(peer, 9)
            val host = socket.localAddress
            log("LOCAL_NETWORK route peer=${peer.hostAddress} local=${host.hostAddress} result=socket_route")
            val iface = NetworkInterface.getByInetAddress(host) ?: return@use null
            if (!iface.isUp || iface.isLoopback) return@use null
            val direct = if (peer is Inet6Address) peer.scopeId == iface.index else
                iface.interfaceAddresses.any { entry ->
                    val address = entry.address
                    address is Inet4Address && address.address.contentEquals(host.address) &&
                        sameSubnet(address.address, peer.address, entry.networkPrefixLength.toInt())
                }
            if (!direct) null else iface.name to host
        } }.getOrNull()
        if (routed != null) return@runCatching routed
        // carlito: Vendor APs can lack a usable default socket route. Accept only a unique
        // local subnet match; never infer an AP from an unrelated default-network address.
        if (peer !is Inet4Address) return@runCatching null
        val matches = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface -> iface.interfaceAddresses.mapNotNull { entry ->
                val host = entry.address
                if (host is Inet4Address && host.isSiteLocalAddress &&
                    sameSubnet(host.address, peer.address, entry.networkPrefixLength.toInt()))
                    iface.name to host else null
            } }.distinct()
        log("LOCAL_NETWORK route peer=${peer.hostAddress} subnetMatches=${matches.size} " +
            "result=${if (matches.size == 1) "unique_subnet" else if (matches.isEmpty()) "no_local_subnet" else "ambiguous_subnet"}")
        matches.singleOrNull()
    }.getOrNull()

    private fun neighborPeers(clientMacs: Set<String>): List<InetAddress> {
        var process: Process? = null
        return try {
            process = ProcessBuilder("/system/bin/ip", "-6", "neigh", "show")
                .redirectErrorStream(true).start()
            if (!process.waitFor(400, TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                log("ecarxHotspot neighborTable=unavailable")
                emptyList()
            } else {
                val entries = process.inputStream.bufferedReader().use {
                    it.lineSequence().take(512).mapNotNull { line -> neighborEntry(line, clientMacs) }.toList()
                }
                entries.mapNotNull { (raw, name) -> runCatching {
                    val iface = NetworkInterface.getByName(name)?.takeIf { it.isUp && !it.isLoopback && it.index > 0 }
                        ?: return@runCatching null
                    val address = numericAddress(raw) as? Inet6Address ?: return@runCatching null
                    Inet6Address.getByAddress(null, address.address, iface.index)
                }.getOrNull() }.also { log("ecarxHotspot neighborTable=read matchedClients=${it.size}") }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            emptyList()
        } catch (error: Exception) {
            failure("clients", "neighborTable", error)
            emptyList()
        } finally {
            process?.destroyForcibly()
            runCatching { process?.inputStream?.close() }
            runCatching { process?.errorStream?.close() }
            runCatching { process?.outputStream?.close() }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        drivers.orEmpty().forEach { driver ->
            driver.connection?.let { connection ->
                runCatching { connection.getMethod("disconnect").invoke(driver.instance) }
                    .onFailure { failure(driver.api.simpleName, "disconnect", it) }
            }
        }
        drivers = null
    }

    companion object {
        private val API_NAMES = listOf(
            "com.ecarx.xui.adaptapi.wifiap.WifiAp",
            "com.ecarx.xui.adaptapi.wifiap.Wifi6Ap",
        )
        private const val CLIENT_API = "com.ecarx.xui.adaptapi.wifiap.IWifiApClient"
        private const val HOST_API = "com.ecarx.xui.adaptapi.wifiap.IWifiAPHost"
        @Volatile private var cachedLoader: ClassLoader? = null

        private fun apiLoader(context: Context): ClassLoader {
            cachedLoader?.let { return it }
            val parent = context.classLoader
            if (API_NAMES.all { runCatching { Class.forName(it, false, parent) }.isSuccess }) {
                return parent.also { cachedLoader = it }
            }
            // Some ROMs expose the SDK to system apps without adding it to an installed app's loader.
            val jars = listOf("/system/framework", "/system_ext/framework", "/vendor/framework")
                .flatMap { directory -> runCatching { File(directory).listFiles()?.toList() }.getOrNull().orEmpty() }
                .filter { file -> file.isFile && file.canRead() && file.extension == "jar" &&
                    (file.name.contains("ecarx", true) || file.name.contains("adaptapi", true)) }
                .sortedBy { it.absolutePath }
            val frameworkLoader = if (jars.isEmpty()) parent else runCatching {
                PathClassLoader(jars.joinToString(File.pathSeparator) { it.absolutePath }, parent)
            }.getOrDefault(parent)
            // carlito | Some KX11 firmwares bundle the SDK in installed system settings. Load
            // only that SDK in-process, retaining DiPlay's own UID and permission checks.
            val loader = if (API_NAMES.any { runCatching {
                    Class.forName(it, false, frameworkLoader)
                }.isSuccess }) frameworkLoader else runCatching {
                val app = context.packageManager.getApplicationInfo(
                    "com.geely.settings", PackageManager.GET_SHARED_LIBRARY_FILES)
                check(app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                val paths = (app.sharedLibraryFiles.orEmpty().toList() + listOf(app.sourceDir) +
                    app.splitSourceDirs.orEmpty().toList()).filter { File(it).canRead() }
                if (paths.isEmpty()) frameworkLoader else
                    PathClassLoader(paths.joinToString(File.pathSeparator), frameworkLoader)
            }.getOrDefault(frameworkLoader)
            return loader.also { cachedLoader = it }
        }

        fun available(context: Context): Boolean = API_NAMES.any { name ->
            runCatching { Class.forName(name, false, apiLoader(context)) }.isSuccess
        }

        internal fun numericAddress(value: String?): InetAddress? {
            val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val ipv4 = raw.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) &&
                raw.split('.').all { it.toInt() in 0..255 }
            val ipv6 = ':' in raw && raw.substringBefore('%').matches(Regex("[0-9a-fA-F:.]+")) &&
                raw.substringAfter('%', "").matches(Regex("[a-zA-Z0-9_.-]*"))
            return if (ipv4 || ipv6) runCatching { InetAddress.getByName(raw) }.getOrNull() else null
        }

        internal fun normalizeMac(value: String?): String? = value?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf { it.matches(Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")) &&
                it != "00:00:00:00:00:00" && it != "02:00:00:00:00:00" &&
                it.substringBefore(':').toInt(16) and 1 == 0 }

        internal fun neighborEntry(line: String, clientMacs: Set<String>): Pair<String, String>? {
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 6 || fields.last() !in setOf("REACHABLE", "STALE", "DELAY", "PROBE", "PERMANENT")) return null
            val macIndex = fields.indexOf("lladdr")
            val devIndex = fields.indexOf("dev")
            val mac = normalizeMac(fields.getOrNull(macIndex + 1))
            if (macIndex < 0 || devIndex < 0 || mac == null || mac !in clientMacs) return null
            val name = fields.getOrNull(devIndex + 1)?.takeIf { it.matches(Regex("[a-zA-Z0-9_.-]+")) }
                ?: return null
            val address = numericAddress(fields.first()) as? Inet6Address ?: return null
            return if (address.isLinkLocalAddress) fields.first() to name else null
        }

        internal fun sameSubnet(first: ByteArray, second: ByteArray, prefix: Int): Boolean {
            if (first.size != second.size || prefix !in 1..first.size * 8) return false
            return (0 until prefix).all { bit ->
                val mask = 1 shl (7 - bit % 8)
                (first[bit / 8].toInt() and mask) == (second[bit / 8].toInt() and mask)
            }
        }
    }
}
