package com.nico7an.terminal

import android.util.Base64
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom

object Ssh {

    /** Building the algorithm lists is slow (reflection), so it is done once and shared. */
    val config: DefaultConfig by lazy {
        DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
    }

    fun passwordFinder(secret: String?): PasswordFinder = object : PasswordFinder {
        override fun reqPassword(resource: Resource<*>?): CharArray = (secret ?: "").toCharArray()
        override fun shouldRetry(resource: Resource<*>?) = false
    }

    fun keyProvider(client: SSHClient, key: SshKey): KeyProvider =
        client.loadKeys(key.privateKey, null, passwordFinder(key.passphrase))

    /** Public key blob as in authorized_keys / known_hosts. */
    fun publicBlob(key: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(key).compactData

    fun publicLine(key: PublicKey, comment: String): String =
        "${KeyType.fromKey(key)} ${b64(publicBlob(key))} $comment".trim()

    /** Same format as `ssh-keygen -l`: SHA256:base64-without-padding. */
    fun fingerprint(blob: ByteArray): String =
        "SHA256:" + b64(MessageDigest.getInstance("SHA-256").digest(blob)).trimEnd('=')

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** Parses and checks an imported private key (any format sshj knows), returning it ready to store. */
    fun importKey(name: String, privateKey: String, passphrase: String?): SshKey {
        val text = privateKey.trim() + "\n"
        val client = SSHClient(config)
        val public = client.loadKeys(text, null, passwordFinder(passphrase)).public
        val blob = publicBlob(public)
        return SshKey(
            name = name, privateKey = text, passphrase = passphrase,
            publicKey = publicLine(public, comment(name)), fingerprint = fingerprint(blob),
        )
    }

    /** Generates an Ed25519 key, serialized in the unencrypted OpenSSH format. */
    fun generateKey(name: String): SshKey {
        val gen = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }
        val pair = gen.generateKeyPair()
        val priv = (pair.private as Ed25519PrivateKeyParameters).encoded
        val pub = (pair.public as Ed25519PublicKeyParameters).encoded
        val comment = comment(name)

        val pubBlob = sshBytes { str("ssh-ed25519"); bytes(pub) }
        val check = SecureRandom().nextInt()
        val privSection = sshBytes {
            int(check); int(check)
            str("ssh-ed25519"); bytes(pub); bytes(priv + pub); str(comment)
            var pad = 1
            while (size() % 8 != 0) writeByte(pad++)
        }
        val file = sshBytes {
            write("openssh-key-v1".toByteArray()); writeByte(0)
            str("none"); str("none"); bytes(ByteArray(0))
            int(1); bytes(pubBlob); bytes(privSection)
        }
        val pem = buildString {
            append("-----BEGIN OPENSSH PRIVATE KEY-----\n")
            b64(file).chunked(70).forEach { append(it).append('\n') }
            append("-----END OPENSSH PRIVATE KEY-----\n")
        }
        return SshKey(
            name = name, privateKey = pem, passphrase = null,
            publicKey = "ssh-ed25519 ${b64(pubBlob)} $comment", fingerprint = fingerprint(pubBlob),
        )
    }

    private fun comment(name: String) = name.trim().replace(Regex("\\s+"), "-") + "@terminal"

    private class SshWriter(out: ByteArrayOutputStream) : DataOutputStream(out) {
        fun int(v: Int) = writeInt(v)
        fun bytes(b: ByteArray) { writeInt(b.size); write(b) }
        fun str(s: String) = bytes(s.toByteArray())
    }

    private fun sshBytes(block: SshWriter.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        SshWriter(out).apply(block).flush()
        return out.toByteArray()
    }
}
