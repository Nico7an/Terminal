package com.nico7an.terminal

import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs on an emulator in CI. The SSH test needs a reachable sshd, passed as instrumentation arguments
 * (see .github/workflows/smoke.yml): sshUser, sshKey (base64 private key), sshHostKey ("type base64").
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun generatedKeyIsValidOpenSsh() {
        val key = Ssh.generateKey("test key")
        val parsed = Ssh.importKey("again", key.privateKey, null)
        assertEquals(key.publicKey.split(' ')[1], parsed.publicKey.split(' ')[1])
        assertEquals(key.fingerprint, parsed.fingerprint)
        assertTrue(key.publicKey.startsWith("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5"))
    }

    @Test
    fun vaultSurvivesReload() {
        val server = Server(name = "vault-test", host = "example.org", user = "me", auth = AuthType.PASSWORD, password = "sécret")
        Vault.saveServer(server)
        Vault.reload(instrumentation.targetContext)
        assertEquals(server, Vault.server(server.id))
        Vault.deleteServer(server.id)
    }

    /** Server entry for the sshd of the CI runner, reachable from the emulator at 10.0.2.2. */
    private fun ciServer(name: String): Server {
        val user = args.getString("sshUser")
        assumeTrue("no sshd configured", user != null)
        Vault.setKnownHost("10.0.2.2:22", args.getString("sshHostKey")!!)
        val key = Ssh.importKey("ci", String(Base64.decode(args.getString("sshKey"), Base64.DEFAULT)), null)
        Vault.saveKey(key)
        return Server(name = name, host = "10.0.2.2", user = user!!, auth = AuthType.KEY, keyId = key.id)
    }

    @Test
    fun sshSessionRunsCommandsAndResizes() {
        val tab = Tab(ciServer("ci"))
        val session = tab.session

        instrumentation.runOnMainSync { session.updateSize(80, 24, 10, 20) }
        session.waitFor("connection") { session.isRunning }

        instrumentation.runOnMainSync { session.write("echo SMOKE_$((6*7))\r") }
        session.waitFor("command output") { screen(session).contains("SMOKE_42") }

        instrumentation.runOnMainSync { session.updateSize(100, 30, 10, 20) }
        Thread.sleep(500)
        instrumentation.runOnMainSync { session.write("stty size\r") }
        session.waitFor("resized pty") { screen(session).contains("30 100") }

        instrumentation.runOnMainSync { session.finishIfRunning() }
        session.waitFor("disconnect") { !session.isRunning }
    }

    @Test
    fun adbIdentityIsValid() {
        val (key, cert) = Adb.generateIdentity()
        assertEquals("RSA", key.algorithm)
        cert.verify(cert.publicKey)
        assertTrue(Adb.publicKeyLine().endsWith(" terminal"))
    }

    /** Written for ci/smoke.sh, which adds it to the emulator's adb_keys before [adbShellRunsCommandsAndResizes]. */
    @Test
    fun exportAdbKey() {
        val dir = instrumentation.targetContext.getExternalFilesDir(null)!!
        File(dir, "adb_key.pub").writeText(Adb.publicKeyLine() + "\n")
    }

    /** The emulator's adbd on a plain TCP port stands in for wireless debugging (same protocol after TLS). */
    @Test
    fun adbShellRunsCommandsAndResizes() {
        val port = args.getString("adbPort")?.toIntOrNull()
        assumeTrue("no adb port configured", port != null)
        Adb.testPort = port!!
        try {
            val tab = Tab(Adb.server)
            val session = tab.session

            instrumentation.runOnMainSync { session.updateSize(80, 24, 10, 20) }
            session.waitFor("adb connection") { session.isRunning }

            instrumentation.runOnMainSync { session.write("echo ADB_$((6*7)) $(id -un)\r") }
            session.waitFor("adb command output") { screen(session).contains("ADB_42") }

            instrumentation.runOnMainSync { session.updateSize(100, 30, 10, 20) }
            Thread.sleep(500)
            instrumentation.runOnMainSync { session.write("stty size\r") }
            session.waitFor("resized adb pty") { screen(session).contains("30 100") }

            // The HyperOS check: on a stock emulator adb is allowed to grant the permission.
            assertEquals(null, Adb.testPermissions())
            assertTrue(Adb.canWriteSecureSettings)

            instrumentation.runOnMainSync { session.finishIfRunning() }
            session.waitFor("adb disconnect") { !session.isRunning }
        } finally {
            Adb.testPort = -1
            Adb.disconnect()
        }
    }

    /** Screenshots of the real screens, pulled by the CI workflow to review the look. */
    @Test
    fun screenshots() {
        val ci = ciServer("CI runner")
        val context = instrumentation.targetContext
        val keyId = ci.keyId
        val vps = Server(name = "VPS", host = "vps.example.org", port = 2222, user = "root", auth = AuthType.PASSWORD)
        Vault.saveServer(Server(name = "Serveur maison", host = "192.168.1.10", user = "nico", auth = AuthType.KEY, keyId = keyId))
        Vault.saveServer(vps)
        Vault.saveServer(Server(name = "Raspberry", host = "raspberry.tailnet.ts.net", user = "pi", auth = AuthType.NONE))
        Vault.saveServer(ci)

        context.startActivity(Intent(context, ServerListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(2500)
        shot("1-servers")

        context.startActivity(
            Intent(context, ServerEditActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ServerEditActivity.EXTRA_ID, vps.id)
        )
        Thread.sleep(1500)
        shot("2-edit")

        var tab: Tab? = null
        instrumentation.runOnMainSync {
            Sessions.open(Server(name = "Serveur maison", host = "10.0.2.2", user = ci.user, auth = AuthType.KEY, keyId = keyId))
            tab = Sessions.open(ci)
        }
        context.startActivity(Intent(context, TerminalActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val session = tab!!.session
        session.waitFor("terminal connection") { session.isRunning }
        instrumentation.runOnMainSync {
            session.write(
                "clear; printf '\\e[31mred \\e[32mgreen \\e[33myellow \\e[34mblue \\e[35mpurple \\e[36mcyan \\e[1;37mbold\\e[0m\\n'; " +
                    "ls -la --color=always /\r"
            )
        }
        Thread.sleep(2000)
        shot("3-terminal")

        context.startActivity(Intent(context, AdbSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(1500)
        shot("5-adb-setup")

        instrumentation.runOnMainSync { Sessions.closeAll() }
    }

    private fun shot(name: String) {
        // The CI emulator is slow: let the screen settle before capturing.
        instrumentation.waitForIdleSync()
        Thread.sleep(2500)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val dir = instrumentation.targetContext.getExternalFilesDir(null)!!
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun screen(session: com.termux.terminal.TerminalSession): String {
        var text = ""
        instrumentation.runOnMainSync { text = session.emulator?.screen?.transcriptText ?: "" }
        return text
    }

    private fun com.termux.terminal.TerminalSession.waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("Timeout waiting for $what. Screen:\n" + screen(this))
    }
}
