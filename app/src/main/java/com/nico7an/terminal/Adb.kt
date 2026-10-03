package com.nico7an.terminal

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** What prevents the local adb connection, in the order the user has to fix it. */
enum class AdbBlocker(val message: String) {
    DEVELOPER_OPTIONS("Options pour les développeurs désactivées"),
    WIFI("Pas de Wi-Fi : le débogage sans fil en a besoin"),
    WIRELESS_DEBUGGING("Débogage sans fil désactivé"),
    PAIRING("Terminal n'est pas encore appairé au débogage sans fil"),
}

/** The device is not ready for adb: the message says what to fix, [AdbSetupActivity] shows how. */
class AdbSetupException(message: String) : Exception(message)

/**
 * The built-in "this device" entry: an adb shell on the device the app runs on, through Wireless debugging
 * (Android 11+). The app is its own adb client (pairing, TLS, shell protocol), no root and no PC needed.
 */
object Adb {
    const val SERVER_ID = "adb-local"
    private const val TAG = "Adb"
    private const val HOST = "127.0.0.1"
    private const val SERVICE_CONNECT = "adb-tls-connect"
    const val SERVICE_PAIRING = "adb-tls-pairing"
    private const val WIRELESS_SETTING = "adb_wifi_enabled"
    private const val KEY_PORT = "connectPort"
    private const val KEY_PAIRED = "paired"

    /** Not stored in the vault: it can be neither edited nor deleted. */
    val server = Server(id = SERVER_ID, name = "Cet appareil", host = HOST, port = 0, user = "shell", auth = AuthType.NONE)

    val supported get() = Build.VERSION.SDK_INT >= 30

    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }

    private val prefs by lazy { App.instance.getSharedPreferences("adb", Context.MODE_PRIVATE) }
    private val resolver get() = App.instance.contentResolver
    private val lock = Object()
    private var manager: Manager? = null
    private val timer = Executors.newSingleThreadScheduledExecutor()

    /** Instrumentation tests only: plain adb port on 127.0.0.1, skipping the wireless debugging checks. */
    @Volatile internal var testPort = -1

    // --- State of the device ---

    val developerOptions: Boolean
        get() = Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    val wirelessDebugging: Boolean
        get() = Settings.Global.getInt(resolver, WIRELESS_SETTING, 0) == 1

    /** Any Wi-Fi network, even behind a VPN such as Tailscale. */
    @Suppress("DEPRECATION")
    val onWifi: Boolean
        get() {
            val cm = App.instance.getSystemService(ConnectivityManager::class.java)
            return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }

    /** Granted through adb itself by [testPermissions]: lets the app turn wireless debugging on alone. */
    val canWriteSecureSettings: Boolean
        get() = App.instance.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    var paired: Boolean
        get() = prefs.getBoolean(KEY_PAIRED, false)
        set(value) = prefs.edit().putBoolean(KEY_PAIRED, value).apply()

    /** Turns wireless debugging on when the app has been allowed to. True if it is on. */
    fun enableWirelessDebugging(): Boolean {
        if (wirelessDebugging) return true
        if (!canWriteSecureSettings || !developerOptions || !onWifi) return false
        runCatching { Settings.Global.putInt(resolver, WIRELESS_SETTING, 1) }.onFailure { Log.w(TAG, "enable", it) }
        return wirelessDebugging
    }

    fun blocker(): AdbBlocker? = when {
        !developerOptions -> AdbBlocker.DEVELOPER_OPTIONS
        !onWifi -> AdbBlocker.WIFI
        !enableWirelessDebugging() -> AdbBlocker.WIRELESS_DEBUGGING
        !paired -> AdbBlocker.PAIRING
        else -> null
    }

    // --- Settings screens ---

    fun aboutIntent() = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
    fun developerIntent() = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)

    /** Straight to the "Wireless debugging" page where the system supports it, developer options otherwise. */
    fun wirelessDebuggingIntent(context: Context): Intent {
        val direct = Intent("com.android.settings.SUBSETTINGS_LAUNCHER")
            .putExtra(":settings:show_fragment", "com.android.settings.development.WirelessDebuggingFragment")
        return if (direct.resolveActivity(context.packageManager) != null) direct else developerIntent()
    }

    // --- Connection ---

    /** Connected adb client shared by every tab, connecting first if needed. Blocking. */
    fun connect(): AbsAdbConnectionManager {
        synchronized(lock) { return connectLocked() }
    }

    private fun connectLocked(): AbsAdbConnectionManager {
        val m = manager()
        if (m.isConnected) return m
        runCatching { m.disconnect() }
        if (testPort > 0) {
            if (!attempt(m, testPort)) throw IOException("adb injoignable sur le port $testPort")
            return m
        }
        blocker()?.let { throw AdbSetupException(it.message) }

        val cached = prefs.getInt(KEY_PORT, -1)
        if (cached > 0 && attempt(m, cached)) return m
        // The port changes every time wireless debugging is turned on: it is advertised over mDNS.
        val port = find(SERVICE_CONNECT, 10_000)
            ?: throw AdbSetupException("Débogage sans fil introuvable : vérifie qu'il est activé et que le Wi-Fi est connecté")
        if (!attempt(m, port)) {
            paired = false
            throw AdbSetupException("adb refuse la connexion : l'appairage a sans doute été révoqué, appaire à nouveau")
        }
        prefs.edit().putInt(KEY_PORT, port).apply()
        return m
    }

    private fun attempt(m: Manager, port: Int): Boolean = try {
        m.connect(HOST, port)
    } catch (e: AdbPairingRequiredException) {
        paired = false
        throw AdbSetupException(AdbBlocker.PAIRING.message)
    } catch (e: Exception) {
        Log.i(TAG, "connect $port: $e")
        runCatching { m.disconnect() }
        false
    }

    fun disconnect() {
        synchronized(lock) { runCatching { manager?.disconnect() } }
    }

    /** Pairs with the code shown by "Pair device with pairing code". Blocking, throws on a wrong code. */
    fun pair(port: Int, code: String) {
        val m = synchronized(lock) { manager() }
        m.pair(HOST, port, code.trim())
        paired = true
    }

    /** Runs a non-interactive command and returns its output. Blocking. */
    fun exec(command: String, timeoutMs: Long = 20_000): String {
        val stream = connect().openStream("shell:$command")
        val watchdog = timer.schedule(Runnable { runCatching { stream.close() } }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            val input = stream.openInputStream()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = try { input.read(buffer) } catch (e: IOException) { -1 }
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            watchdog.cancel(false)
            runCatching { stream.close() }
        }
    }

    /**
     * Checks that adb is allowed to act on the system and not only to read it: HyperOS/MIUI block
     * `pm grant`, `input`… until "USB debugging (Security settings)" is on. The command used grants the
     * app WRITE_SECURE_SETTINGS, so next time it turns wireless debugging on by itself.
     * Returns null when everything works, the command output otherwise.
     */
    fun testPermissions(): String? {
        val output = exec("pm grant ${App.instance.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS} 2>&1")
        return if (canWriteSecureSettings) null else output.trim().ifEmpty { "Permission non accordée" }
    }

    /** Explanation shown when [testPermissions] fails. */
    fun permissionHelp(output: String): String = buildString {
        if (isXiaomi) {
            append("La connexion adb fonctionne, mais HyperOS bloque les commandes qui modifient le système ")
            append("(pm grant, input, settings put…).\n\n")
            append("Pour débloquer :\n")
            append("1. Paramètres → Paramètres supplémentaires → Options pour les développeurs.\n")
            append("2. Active « Débogage USB (paramètres de sécurité) ». HyperOS exige d'être connecté à un compte Xiaomi ")
            append("(parfois avec une carte SIM) et affiche plusieurs avertissements à accepter.\n")
            append("3. Active aussi « Installer via USB » si tu veux installer des APK avec adb.\n")
            append("4. Si l'option « Désactiver l'optimisation MIUI » existe sur ta version, désactive l'optimisation.\n")
            append("5. Reviens ici et relance le test.\n\n")
        } else {
            append("La connexion adb fonctionne, mais le système refuse les commandes qui modifient les réglages. ")
            append("Vérifie les options pour les développeurs (certains constructeurs ont une option de sécurité en plus du débogage USB).\n\n")
        }
        append("Réponse de adb :\n")
        append(output.lines().take(4).joinToString("\n"))
    }

    // --- Identity ---

    private class Manager(private val key: PrivateKey, val cert: X509Certificate) : AbsAdbConnectionManager() {
        init {
            api = Build.VERSION.SDK_INT
            setTimeout(10, TimeUnit.SECONDS)
        }

        override fun getPrivateKey(): PrivateKey = key
        override fun getCertificate(): Certificate = cert
        override fun getDeviceName(): String = "Terminal"
    }

    private fun manager(): Manager {
        manager?.let { return it }
        val (key, cert) = Vault.adbIdentity()?.let { (k, c) -> runCatching { decode(k, c) }.getOrNull() }
            ?: generateIdentity().also { (k, c) ->
                Vault.setAdbIdentity(b64(k.encoded), b64(c.encoded))
                // A new key is unknown to adbd.
                paired = false
            }
        return Manager(key, cert).also { manager = it }
    }

    private fun decode(key: String, cert: String): Pair<PrivateKey, X509Certificate> {
        val k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(key, Base64.DEFAULT)))
        val c = CertificateFactory.getInstance("X.509")
            .generateCertificate(Base64.decode(cert, Base64.DEFAULT).inputStream()) as X509Certificate
        return k to c
    }

    /** RSA 2048 like the adb of a PC, with the self-signed certificate used for TLS. */
    internal fun generateIdentity(): Pair<PrivateKey, X509Certificate> {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=Terminal")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(now), Date(now - 86_400_000L), Date(now + 20 * 365 * 86_400_000L), name, pair.public,
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
        return pair.private to JcaX509CertificateConverter().getCertificate(holder)
    }

    /** adb_keys line of this app, as a PC's ~/.android/adbkey.pub. */
    internal fun publicKeyLine(): String {
        val key = synchronized(lock) { manager() }.cert.publicKey as RSAPublicKey
        // struct RSAPublicKey of AOSP android_pubkey: sizes, -1/n[0] mod 2^32, n and R^2 mod n little-endian, e.
        val n = key.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toInt()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        fun littleEndian(v: BigInteger): ByteArray {
            val be = v.toByteArray()
            return ByteArray(256) { i -> be.getOrElse(be.size - 1 - i) { 0 } }
        }
        val blob = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(64).putInt(n0inv).put(littleEndian(n)).put(littleEndian(rr)).putInt(key.publicExponent.toInt())
            .array()
        return b64(blob) + " terminal"
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    // --- mDNS ---

    /** Port of a wireless debugging service of this device, or null after [timeoutMs]. Blocking. */
    fun find(type: String, timeoutMs: Long): Int? {
        val port = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val discovery = AdbDiscovery(type) {
            port.compareAndSet(-1, it)
            latch.countDown()
        }
        discovery.start()
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            discovery.stop()
        }
        return port.get().takeIf { it > 0 }
    }
}

/** Finds the wireless debugging services advertised by this device; other devices on the Wi-Fi are ignored. */
class AdbDiscovery(type: String, private val onPort: (Int) -> Unit) {
    private val serviceType = "_$type._tcp"
    private val nsd = App.instance.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var stopped = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            synchronized(this@AdbDiscovery) {
                started = true
                // stop() came first: stopping is only possible now.
                if (stopped) runCatching { nsd.stopServiceDiscovery(this) }
            }
        }

        override fun onServiceFound(info: NsdServiceInfo) = resolve(info, 0)
        override fun onServiceLost(info: NsdServiceInfo) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    fun start() {
        runCatching { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stop() {
        synchronized(this) {
            if (stopped) return
            stopped = true
            if (started) runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo, attempt: Int) {
        if (stopped) return
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Before Android 14 only one resolution can run at a time.
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attempt < 20) {
                    main.postDelayed({ resolve(info, attempt + 1) }, 250)
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host
                if (!stopped && host != null && isLocal(host)) onPort(serviceInfo.port)
            }
        })
    }

    private fun isLocal(address: InetAddress): Boolean = runCatching {
        address.isLoopbackAddress || NetworkInterface.getNetworkInterfaces().toList()
            .any { iface -> iface.inetAddresses.toList().any { it == address } }
    }.getOrDefault(false)
}
