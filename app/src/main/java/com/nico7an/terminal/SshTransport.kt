package com.nico7an.terminal

import com.termux.terminal.TerminalSession
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthNone
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.PublicKey
import java.util.concurrent.Executors

/** Questions the SSH layer needs to ask the user. Called from background threads, blocks until answered. */
interface Prompter {
    fun confirm(title: String, message: String, positive: String, danger: Boolean): Boolean
    fun askPassword(title: String): String?
}

class SshTransport(private val server: Server, private val prompter: Prompter) : TerminalSession.Transport {

    /** Control operations (resize, close) must not run on the main thread: they hit the network. */
    private val control = Executors.newSingleThreadExecutor()

    @Volatile private var client: SSHClient? = null
    @Volatile private var shell: Session.Shell? = null
    @Volatile private var columns = 80
    @Volatile private var rows = 24
    @Volatile private var closed = false

    override fun connect(session: TerminalSession, columns: Int, rows: Int) {
        this.columns = columns
        this.rows = rows
        Thread({ run(session) }, "ssh-${server.host}").start()
    }

    override fun resize(columns: Int, rows: Int) {
        this.columns = columns
        this.rows = rows
        val sh = shell ?: return
        runCatching { control.execute { runCatching { sh.changeWindowDimensions(columns, rows, 0, 0) } } }
    }

    override fun disconnect() {
        closed = true
        control.execute { close() }
        control.shutdown()
    }

    private fun close() {
        runCatching { shell?.close() }
        runCatching { client?.disconnect() }
        shell = null
        client = null
    }

    private fun run(session: TerminalSession) {
        var reason: String? = "Connexion fermée"
        try {
            status(session, "Connexion à ${server.address}…")
            val c = SSHClient(Ssh.config)
            client = c
            c.connectTimeout = 10_000
            c.addHostKeyVerifier(KnownHostsVerifier(prompter))
            c.connect(server.host, server.port)
            c.socket.tcpNoDelay = true
            c.connection.keepAlive.keepAliveInterval = 15
            if (closed) return

            authenticate(c)

            val s = c.startSession()
            val cols = columns
            val rws = rows
            s.allocatePTY("xterm-256color", cols, rws, 0, 0, emptyMap())
            val sh = s.startShell()
            shell = sh
            session.onConnected(sh.outputStream)
            // The view may have been resized while connecting.
            if (cols != columns || rws != rows) resize(columns, rows)

            val input = sh.inputStream
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) session.onRemoteData(buffer, 0, n)
            }
            reason = "Session terminée"
        } catch (e: Exception) {
            reason = describe(e)
        } finally {
            close()
            session.onDisconnected(if (closed) null else reason)
        }
    }

    private fun authenticate(c: SSHClient) {
        when (server.auth) {
            AuthType.KEY -> {
                val key = Vault.key(server.keyId) ?: throw IllegalStateException("Clé SSH introuvable, modifie le serveur")
                c.authPublickey(server.user, Ssh.keyProvider(c, key))
            }
            AuthType.PASSWORD -> {
                val password = server.password?.takeIf { it.isNotEmpty() }
                    ?: prompter.askPassword("Mot de passe pour ${server.address}")
                    ?: throw IllegalStateException("Connexion annulée")
                val finder = Ssh.passwordFinder(password)
                c.auth(server.user, AuthPassword(finder), AuthKeyboardInteractive(PasswordResponseProvider(finder)))
            }
            AuthType.NONE -> c.auth(server.user, AuthNone())
        }
    }

    private fun status(session: TerminalSession, text: String) {
        val bytes = "\u001b[0;90m$text\u001b[0m\r\n".toByteArray()
        session.onRemoteData(bytes, 0, bytes.size)
    }

    private fun describe(e: Throwable): String {
        var t: Throwable? = e
        while (t != null) {
            when (t) {
                is UnknownHostException -> return "Hôte introuvable : ${server.host}"
                is ConnectException -> return "Connexion refusée par ${server.host}:${server.port}"
                is NoRouteToHostException -> return "Hôte injoignable : ${server.host}"
                is SocketTimeoutException -> return "Délai de connexion dépassé"
                is UserAuthException -> return "Authentification refusée pour ${server.user}"
                is SSHException -> if (t.disconnectReason == DisconnectReason.HOST_KEY_NOT_VERIFIABLE) return "Empreinte du serveur refusée"
            }
            if (t.cause == null) break
            t = t.cause
        }
        return "Erreur : " + (t?.message ?: e.javaClass.simpleName)
    }
}

/**
 * Trust on first use, with every key type known for a host stored in the vault.
 * A changed key for a known type is loudly reported (possible man-in-the-middle).
 */
class KnownHostsVerifier(private val prompter: Prompter) : HostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val id = "$hostname:$port"
        val type = KeyType.fromKey(key).toString()
        val blob = Ssh.publicBlob(key)
        val line = "$type ${Ssh.b64(blob)}"
        val known = Vault.knownHost(id)?.lines()?.filter { it.isNotBlank() } ?: emptyList()
        if (line in known) return true

        val fingerprint = Ssh.fingerprint(blob)
        val sameType = known.find { it.substringBefore(' ') == type }
        val ok = if (sameType == null) {
            prompter.confirm(
                "Nouveau serveur",
                "Première connexion à $id.\n\nEmpreinte $type :\n$fingerprint\n\nFaire confiance à ce serveur ?",
                "Faire confiance", danger = false,
            )
        } else {
            prompter.confirm(
                "⚠ Empreinte modifiée",
                "L'empreinte de $id a changé depuis la dernière connexion.\n\n" +
                    "Soit le serveur a été réinstallé, soit quelqu'un intercepte la connexion.\n\n" +
                    "Nouvelle empreinte $type :\n$fingerprint",
                "Accepter la nouvelle clé", danger = true,
            )
        }
        if (ok) Vault.setKnownHost(id, (known.filterNot { it.substringBefore(' ') == type } + line).joinToString("\n"))
        return ok
    }

    /** Prefer the key types we already know so the server presents a key we can check. */
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        Vault.knownHost("$hostname:$port")?.lines()
            ?.map { it.substringBefore(' ') }
            ?.filter { it.isNotBlank() && it != "ssh-rsa" }
            ?: emptyList()
}
