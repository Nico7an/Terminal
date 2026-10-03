package com.nico7an.terminal

import android.os.Build
import android.system.Os
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * The built-in "Linux" entry: an Alpine Linux in the app private storage, run with proot (no root),
 * like Termux's proot-distro. First start downloads it and installs Node.js/npm, git, adb, Claude Code
 * and Gemini CLI; afterwards `apk add` and `npm install -g` work as on any Linux.
 *
 * Android forbids executing files from the app data (W^X): proot and its loader ship as native
 * libraries (jniLibs, see tools/fetch-proot.sh), and proot's loader maps the Linux binaries itself.
 */
object Linux {
    const val SERVER_ID = "linux-local"
    private const val TAG = "Linux"
    private const val MIRROR = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases"

    /** Not stored in the vault: it can be neither edited nor deleted. */
    val server = Server(id = SERVER_ID, name = "Linux", host = "alpine", port = 0, user = "root", auth = AuthType.NONE)

    /** Instrumentation tests only: skip the npm global installs, which take minutes. */
    @Volatile internal var minimal = false

    private val context get() = App.instance
    private val nativeDir get() = File(context.applicationInfo.nativeLibraryDir)
    val base: File get() = File(context.filesDir, "linux")
    val rootfs: File get() = File(base, "rootfs")
    private val tmp get() = File(base, "tmp")
    private val fakeProc get() = File(base, "proc")
    val provisioned: File get() = File(rootfs, "etc/terminal/provisioned")

    val supported: Boolean get() = File(nativeDir, "libproot.so").exists()
    val installed: Boolean get() = File(rootfs, "bin/busybox").exists()

    private val arch: String?
        get() = when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "aarch64"
            "x86_64" -> "x86_64"
            else -> null
        }

    /** Downloads and extracts Alpine if needed, then (re)writes the integration files. Blocking. */
    fun prepare(status: (String) -> Unit) {
        if (!supported || arch == null) throw IOException("Linux n'est disponible que sur les appareils 64 bits (arm64 ou x86_64)")
        if (!installed) install(status)
        configure()
    }

    /** Deletes the whole environment; the next start reinstalls it. */
    fun reset() {
        deleteTree(base)
    }

    /**
     * Unlike File.deleteRecursively(), never follows symlinks: a Linux tree is full of them, some pointing
     * outside (to the app data or Android itself). Read-only directories are made writable first.
     */
    private fun deleteTree(dir: File) {
        val root = dir.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
                d.toFile().setWritable(true, true)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(f)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(f: Path, e: IOException): FileVisitResult {
                Files.deleteIfExists(f)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(d: Path, e: IOException?): FileVisitResult {
                Files.deleteIfExists(d)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun install(status: (String) -> Unit) {
        status("Téléchargement d'Alpine Linux…")
        val (name, sha256) = latestMiniRootfs()
        base.mkdirs()
        val archive = File(base, name)
        download("$MIRROR/$arch/$name", archive, sha256, status)

        status("Extraction…")
        val staging = File(base, "rootfs.partial")
        deleteTree(staging)
        staging.mkdirs()
        archive.inputStream().use { Tar.extract(GZIPInputStream(BufferedInputStream(it, 64 * 1024)), staging) }
        deleteTree(rootfs)
        if (!staging.renameTo(rootfs)) throw IOException("Impossible de finaliser l'extraction")
        archive.delete()
    }

    /** File name and checksum of the current mini root filesystem, from the release index. */
    private fun latestMiniRootfs(): Pair<String, String> {
        val yaml = http("$MIRROR/$arch/latest-releases.yaml").inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        for (entry in yaml.split("\n-")) {
            if (!entry.contains("flavor: alpine-minirootfs")) continue
            fun field(key: String) = Regex("""^\s*$key:\s*(\S+)""", RegexOption.MULTILINE).find(entry)?.groupValues?.get(1)
            val file = field("file")
            val sha = field("sha256")
            if (file != null && sha != null) return file to sha
        }
        throw IOException("Index des versions d'Alpine illisible")
    }

    private fun download(url: String, target: File, sha256: String, status: (String) -> Unit) {
        val conn = http(url)
        val total = conn.contentLengthLong
        val digest = MessageDigest.getInstance("SHA-256")
        conn.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    done += n
                    val percent = if (total > 0) (done * 100 / total).toInt() else -1
                    if (percent != last && percent % 10 == 0) {
                        last = percent
                        status("  $percent %")
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(sha256, ignoreCase = true)) {
            target.delete()
            throw IOException("Somme de contrôle invalide pour ${target.name}")
        }
    }

    private fun http(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
    }

    // --- Integration with Android and the app ---

    private fun configure() {
        tmp.mkdirs()
        write("etc/resolv.conf", "nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        write("etc/hosts", "127.0.0.1 localhost linux\n::1 localhost ip6-localhost\n")
        write("etc/hostname", "linux\n")
        write("etc/profile.d/terminal.sh", PROFILE)
        write("etc/terminal/start.sh", START)
        write("usr/local/bin/adb", ADB_WRAPPER, executable = true)

        // The adb of Linux uses the identity already paired by the app: no second pairing.
        runCatching {
            write("root/.android/adbkey", Adb.privateKeyPem())
            write("root/.android/adbkey.pub", Adb.publicKeyLine() + "\n")
        }.onFailure { Log.w(TAG, "adb key", it) }

        // Android hides these from apps; some programs (node's os.cpus(), top…) need them.
        fakeProc.mkdirs()
        val cores = Runtime.getRuntime().availableProcessors()
        File(fakeProc, "stat").writeText(buildString {
            append("cpu  1000 0 1000 100000 0 0 0 0 0 0\n")
            for (i in 0 until cores) append("cpu$i 100 0 100 10000 0 0 0 0 0 0\n")
            append("intr 0\nctxt 0\nbtime 0\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n")
        })
        File(fakeProc, "loadavg").writeText("0.10 0.10 0.10 1/100 1\n")
        File(fakeProc, "uptime").writeText("1000.00 4000.00\n")
        File(fakeProc, "vmstat").writeText("nr_free_pages 100000\n")
    }

    private fun write(path: String, content: String, executable: Boolean = false) {
        val file = File(rootfs, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        if (executable) Os.chmod(file.path, "755".toInt(8))
    }

    /** Port of wireless debugging, for the `adb` wrapper. Found in the background, best effort. */
    fun refreshAdbPort() {
        if (!Adb.supported || !Adb.paired) return
        Thread({
            runCatching {
                if (Adb.blocker() != null) return@runCatching
                val port = Adb.find("adb-tls-connect", 10_000) ?: return@runCatching
                write("etc/terminal/adb-port", "$port\n")
            }
        }, "linux-adb-port").start()
    }

    class Command(val executable: String, val args: Array<String>, val env: Array<String>, val cwd: String)

    fun command(): Command {
        val proot = File(nativeDir, "libproot.so").path
        val args = mutableListOf(
            proot, "--kill-on-exit", "--link2symlink", "-0", "--sysvipc",
            "-r", rootfs.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            "-b", "${tmp.path}:/tmp", "-b", "${tmp.path}:/dev/shm",
        )
        for (name in listOf("stat", "loadavg", "uptime", "vmstat")) {
            if (!File("/proc/$name").canRead()) args += listOf("-b", "${File(fakeProc, name).path}:/proc/$name")
        }
        args += listOf(
            "-w", "/root", "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "TERM=xterm-256color", "COLORTERM=truecolor", "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERMINAL_MINIMAL=${if (minimal) 1 else 0}",
            "/bin/sh", "/etc/terminal/start.sh",
        )
        val env = arrayOf(
            "PROOT_LOADER=${File(nativeDir, "libproot-loader.so").path}",
            "PROOT_TMP_DIR=${tmp.path}",
            "TMPDIR=${tmp.path}",
            "LD_LIBRARY_PATH=${nativeDir.path}",
            "PATH=/system/bin",
        )
        return Command(proot, args.toTypedArray(), env, base.path)
    }

    private val PROFILE = """
        # Written by Terminal at every start.
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        export LANG=C.UTF-8
        export USE_BUILTIN_RIPGREP=0
        if [ -n "${'$'}BASH_VERSION" ]; then
          PS1='\[\e[32m\]linux\[\e[0m\]:\[\e[34m\]\w\[\e[0m\]\$ '
        fi
    """.trimIndent() + "\n"

    /** First start installs the tools in the terminal itself, so the user sees the progress. */
    private val START = """
        #!/bin/sh
        if [ ! -f /etc/terminal/provisioned ]; then
          printf '\033[1mPremière ouverture : installation des outils (quelques minutes)…\033[0m\n'
          apk update && apk add bash bash-completion nodejs npm git ripgrep curl wget openssh-client \
            android-tools libgcc libstdc++ ca-certificates nano less ncurses procps coreutils python3 \
          && { [ "${'$'}TERMINAL_MINIMAL" = 1 ] || npm install -g @anthropic-ai/claude-code @google/gemini-cli; } \
          && touch /etc/terminal/provisioned \
          && printf '\n\033[32mPrêt.\033[0m Commandes : claude, gemini, node, npm, git, adb. Paquets : apk add <nom>.\n\n' \
          || printf '\n\033[31mL\047installation a échoué.\033[0m Vérifie la connexion puis ferme et rouvre l\047onglet.\n\n'
        fi
        cd /root
        if [ -x /bin/bash ]; then exec /bin/bash -l; fi
        exec /bin/sh -l
    """.trimIndent() + "\n"

    /** Connects adb to this device (port found by the app) before every command, transparently. */
    private val ADB_WRAPPER = """
        #!/bin/sh
        port=${'$'}(cat /etc/terminal/adb-port 2>/dev/null)
        if [ -n "${'$'}port" ] && ! /usr/bin/adb devices 2>/dev/null | grep -q "^127.0.0.1:${'$'}port[[:space:]]*device"; then
          /usr/bin/adb disconnect >/dev/null 2>&1
          /usr/bin/adb connect "127.0.0.1:${'$'}port" >/dev/null 2>&1
        fi
        exec /usr/bin/adb "${'$'}@"
    """.trimIndent() + "\n"
}

/** Minimal tar reader (ustar, GNU long names, pax paths): files, directories, symlinks, hard links. */
private object Tar {

    fun extract(input: InputStream, dest: File) {
        val root = dest.canonicalFile
        val header = ByteArray(512)
        var longName: String? = null
        var longLink: String? = null
        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it.toInt() == 0 }) break
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                val data = ByteArray(size.toInt())
                readFully(input, data)
                skip(input, pad(size))
                when (type) {
                    'L' -> longName = cString(data, 0, data.size)
                    'K' -> longLink = cString(data, 0, data.size)
                    'x' -> pax(data).let { p -> p["path"]?.let { longName = it }; p["linkpath"]?.let { longLink = it } }
                }
                continue
            }
            val prefix = cString(header, 345, 155)
            val shortName = cString(header, 0, 100)
            val name = longName ?: if (prefix.isNotEmpty()) "$prefix/$shortName" else shortName
            val link = longLink ?: cString(header, 157, 100)
            longName = null
            longLink = null
            val mode = octal(header, 100, 8).toInt() and "7777".toInt(8)

            val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.any { it == ".." }) throw IOException("Chemin invalide dans l'archive : $name")
            if (parts.isEmpty()) {
                skip(input, size + pad(size))
                continue
            }
            val target = File(root, parts.joinToString("/"))

            when (type) {
                '5' -> target.mkdirs()
                '2' -> {
                    target.parentFile?.mkdirs()
                    target.delete()
                    Os.symlink(link, target.path)
                }
                '1' -> {
                    target.parentFile?.mkdirs()
                    File(root, link).copyTo(target, overwrite = true)
                    Os.chmod(target.path, mode)
                }
                '0', '\u0000', '7' -> {
                    target.parentFile?.mkdirs()
                    target.delete()
                    target.outputStream().use { out -> copy(input, out, size) }
                    Os.chmod(target.path, mode)
                    skip(input, pad(size))
                    continue
                }
            }
            skip(input, size + pad(size))
        }
    }

    private fun pad(size: Long) = (512 - size % 512) % 512

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        for (i in off until off + len) {
            val c = b[i].toInt()
            if (c == 0 || c == ' '.code) { if (v != 0L) break else continue }
            v = v * 8 + (c - '0'.code)
        }
        return v
    }

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    /** "len key=value\n" records. */
    private fun pax(data: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i < data.size) {
            val space = (i until data.size).firstOrNull { data[it] == ' '.code.toByte() } ?: break
            val len = String(data, i, space - i).toIntOrNull() ?: break
            val record = String(data, space + 1, len - (space - i) - 2, Charsets.UTF_8)
            val eq = record.indexOf('=')
            if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
            i += len
        }
        return out
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buffer = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (n < 0) throw IOException("Archive tronquée")
            out.write(buffer, 0, n)
            left -= n
        }
    }

    private fun skip(input: InputStream, count: Long) {
        var left = count
        val buffer = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (n < 0) return
            left -= n
        }
    }
}
