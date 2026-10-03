package com.nico7an.terminal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class AuthType { KEY, PASSWORD, NONE }

data class Server(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val auth: AuthType,
    val keyId: String? = null,
    val password: String? = null,
) {
    val address: String get() = if (port == 22) "$user@$host" else "$user@$host:$port"
}

data class SshKey(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** OpenSSH private key file content. */
    val privateKey: String,
    val passphrase: String?,
    /** "ssh-ed25519 AAAA... name" line, ready for authorized_keys. */
    val publicKey: String,
    val fingerprint: String,
)

/**
 * Everything sensitive (servers, passwords, private keys, known hosts) lives in a single file
 * encrypted with AES-256-GCM using a key that never leaves the Android Keystore.
 * The file sits in the app private directory and is excluded from every backup.
 */
object Vault {
    private const val KEY_ALIAS = "vault"
    private const val FILE_NAME = "vault.bin"
    private const val TAG = "Vault"

    private val lock = Object()
    private var loaded = false
    private lateinit var file: AtomicFile

    private val servers = mutableListOf<Server>()
    private val keys = mutableListOf<SshKey>()
    /** "host:port" -> "keytype base64(blob)" */
    private val knownHosts = mutableMapOf<String, String>()
    /** Key and certificate this app presents to the local adbd (base64 PKCS#8 / DER). */
    private var adbKey: String? = null
    private var adbCert: String? = null

    fun load(context: Context) = synchronized(lock) {
        if (loaded) return
        file = AtomicFile(File(context.filesDir, FILE_NAME))
        if (file.baseFile.exists()) {
            try {
                parse(JSONObject(String(decrypt(file.readFully()), Charsets.UTF_8)))
            } catch (e: Exception) {
                // Never silently overwrite data we could not read.
                Log.e(TAG, "Unreadable vault, moved aside", e)
                file.baseFile.renameTo(File(context.filesDir, "$FILE_NAME.unreadable-${System.currentTimeMillis()}"))
            }
        }
        loaded = true
    }

    /** Drops the in-memory state and reads the file again (tests the encryption round trip). */
    internal fun reload(context: Context) = synchronized(lock) {
        servers.clear()
        keys.clear()
        knownHosts.clear()
        adbKey = null
        adbCert = null
        loaded = false
        load(context)
    }

    private fun ensureLoaded() {
        if (!loaded) load(App.instance)
    }

    fun servers(): List<Server> = synchronized(lock) { ensureLoaded(); servers.toList() }
    fun server(id: String): Server? = synchronized(lock) { ensureLoaded(); servers.find { it.id == id } }
    fun keys(): List<SshKey> = synchronized(lock) { ensureLoaded(); keys.toList() }
    fun key(id: String?): SshKey? = synchronized(lock) { ensureLoaded(); keys.find { it.id == id } }
    fun knownHost(hostPort: String): String? = synchronized(lock) { ensureLoaded(); knownHosts[hostPort] }

    fun saveServer(server: Server) = mutate {
        val i = servers.indexOfFirst { it.id == server.id }
        if (i >= 0) servers[i] = server else servers.add(server)
    }

    fun deleteServer(id: String) = mutate { servers.removeAll { it.id == id } }

    fun moveServer(id: String, delta: Int) = mutate {
        val i = servers.indexOfFirst { it.id == id }
        val j = i + delta
        if (i >= 0 && j in servers.indices) servers.add(j, servers.removeAt(i))
    }

    fun saveKey(key: SshKey) = mutate {
        val i = keys.indexOfFirst { it.id == key.id }
        if (i >= 0) keys[i] = key else keys.add(key)
    }

    fun deleteKey(id: String) = mutate { keys.removeAll { it.id == id } }

    fun setKnownHost(hostPort: String, value: String) = mutate { knownHosts[hostPort] = value }

    fun adbIdentity(): Pair<String, String>? = synchronized(lock) {
        ensureLoaded()
        val key = adbKey ?: return null
        val cert = adbCert ?: return null
        key to cert
    }

    fun setAdbIdentity(key: String, cert: String) = mutate {
        adbKey = key
        adbCert = cert
    }

    private inline fun mutate(block: () -> Unit) = synchronized(lock) {
        ensureLoaded()
        block()
        persist()
    }

    private fun persist() {
        val json = JSONObject()
            .put("version", 1)
            .put("servers", JSONArray().apply {
                servers.forEach {
                    put(JSONObject()
                        .put("id", it.id).put("name", it.name).put("host", it.host).put("port", it.port)
                        .put("user", it.user).put("auth", it.auth.name).put("keyId", it.keyId).put("password", it.password))
                }
            })
            .put("keys", JSONArray().apply {
                keys.forEach {
                    put(JSONObject()
                        .put("id", it.id).put("name", it.name).put("privateKey", it.privateKey)
                        .put("passphrase", it.passphrase).put("publicKey", it.publicKey).put("fingerprint", it.fingerprint))
                }
            })
            .put("knownHosts", JSONObject(knownHosts as Map<*, *>))
            .put("adbKey", adbKey)
            .put("adbCert", adbCert)
        val out = file.startWrite()
        try {
            out.write(encrypt(json.toString().toByteArray(Charsets.UTF_8)))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    private fun parse(json: JSONObject) {
        json.optJSONArray("servers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                servers.add(Server(
                    id = o.getString("id"), name = o.getString("name"), host = o.getString("host"),
                    port = o.optInt("port", 22), user = o.getString("user"),
                    auth = runCatching { AuthType.valueOf(o.getString("auth")) }.getOrDefault(AuthType.KEY),
                    keyId = o.optStringOrNull("keyId"), password = o.optStringOrNull("password"),
                ))
            }
        }
        json.optJSONArray("keys")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                keys.add(SshKey(
                    id = o.getString("id"), name = o.getString("name"), privateKey = o.getString("privateKey"),
                    passphrase = o.optStringOrNull("passphrase"), publicKey = o.getString("publicKey"),
                    fingerprint = o.optString("fingerprint"),
                ))
            }
        }
        json.optJSONObject("knownHosts")?.let { o ->
            o.keys().forEach { knownHosts[it] = o.getString(it) }
        }
        adbKey = json.optStringOrNull("adbKey")
        adbCert = json.optStringOrNull("adbCert")
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).ifEmpty { null }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as SecretKey?)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, data, 0, 12))
        return cipher.doFinal(data, 12, data.size - 12)
    }
}
