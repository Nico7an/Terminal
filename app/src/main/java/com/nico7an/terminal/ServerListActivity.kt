package com.nico7an.terminal

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView

/**
 * Launcher screen: the saved servers. A tap opens a new tab connected to that server.
 * Also used as the picker behind the "+" of the tab bar ([EXTRA_PICK]).
 */
class ServerListActivity : Activity() {

    private lateinit var list: ListView
    private lateinit var empty: View
    private lateinit var updateBanner: TextView
    private var servers = listOf<Server>()
    private val pick get() = intent.getBooleanExtra(EXTRA_PICK, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Launched from the home screen while tabs are open: go straight to them.
        if (!pick && savedInstanceState == null && Sessions.tabs.isNotEmpty() && isTaskRoot) {
            startActivity(Intent(this, TerminalActivity::class.java))
        }

        setContentView(R.layout.activity_servers)
        edgeToEdge(findViewById(R.id.root))

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        updateBanner = findViewById(R.id.update)
        findViewById<TextView>(R.id.version).text = "Terminal ${BuildConfig.VERSION_NAME}"

        if (pick) {
            findViewById<TextView>(R.id.title).text = "Nouvel onglet"
            findViewById<View>(R.id.logo).visibility = View.GONE
            findViewById<ImageButton>(R.id.back).apply {
                visibility = View.VISIBLE
                setOnClickListener { finish() }
            }
        }
        findViewById<View>(R.id.add).setOnClickListener {
            startActivity(Intent(this, ServerEditActivity::class.java))
        }
        findViewById<View>(R.id.keys).setOnClickListener {
            startActivity(Intent(this, KeysActivity::class.java))
        }

        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> connect(servers[position]) }
        list.setOnItemLongClickListener { _, view, position, _ ->
            showMenu(view, servers[position])
            true
        }
    }

    override fun onResume() {
        super.onResume()
        val saved = Vault.servers()
        servers = listOf(Adb.server) + saved
        adapter.notifyDataSetChanged()
        empty.visibility = if (saved.isEmpty()) View.VISIBLE else View.GONE
        Updater.check { release -> showUpdate(release) }
    }

    private fun connect(server: Server) {
        // This device: straight to the terminal when everything is ready, to the checklist otherwise.
        if (server.id == Adb.SERVER_ID && (!Adb.supported || Adb.blocker() != null)) {
            startActivity(Intent(this, AdbSetupActivity::class.java))
            if (pick) finish()
            return
        }
        Sessions.open(server)
        startActivity(Intent(this, TerminalActivity::class.java))
        if (pick) finish()
    }

    private fun showMenu(anchor: View, server: Server) {
        if (server.id == Adb.SERVER_ID) {
            // Built in: can be configured, never edited or deleted.
            PopupMenu(this, anchor).apply {
                menu.add("Configurer").setOnMenuItemClickListener {
                    startActivity(Intent(this@ServerListActivity, AdbSetupActivity::class.java))
                    true
                }
                show()
            }
            return
        }
        PopupMenu(this, anchor).apply {
            menu.add("Modifier").setOnMenuItemClickListener {
                startActivity(Intent(this@ServerListActivity, ServerEditActivity::class.java)
                    .putExtra(ServerEditActivity.EXTRA_ID, server.id))
                true
            }
            menu.add("Dupliquer").setOnMenuItemClickListener {
                startActivity(Intent(this@ServerListActivity, ServerEditActivity::class.java)
                    .putExtra(ServerEditActivity.EXTRA_ID, server.id)
                    .putExtra(ServerEditActivity.EXTRA_DUPLICATE, true))
                true
            }
            menu.add("Monter").setOnMenuItemClickListener { Vault.moveServer(server.id, -1); onResume(); true }
            menu.add("Descendre").setOnMenuItemClickListener { Vault.moveServer(server.id, 1); onResume(); true }
            menu.add("Supprimer").setOnMenuItemClickListener {
                AlertDialog.Builder(this@ServerListActivity)
                    .setTitle("Supprimer ${server.name} ?")
                    .setPositiveButton("Supprimer") { _, _ -> Vault.deleteServer(server.id); onResume() }
                    .setNegativeButton("Annuler", null)
                    .show()
                true
            }
            show()
        }
    }

    private fun showUpdate(release: Updater.Release?) {
        if (release == null || isFinishing) {
            updateBanner.visibility = View.GONE
            return
        }
        updateBanner.visibility = View.VISIBLE
        updateBanner.text = "Mise à jour ${release.version} disponible — appuie pour installer"
        updateBanner.setOnClickListener {
            val warn = Sessions.tabs.isNotEmpty()
            if (warn) {
                AlertDialog.Builder(this)
                    .setTitle("Installer la mise à jour ?")
                    .setMessage("Les sessions SSH ouvertes seront fermées.")
                    .setPositiveButton("Installer") { _, _ -> install(release) }
                    .setNegativeButton("Plus tard", null)
                    .show()
            } else {
                install(release)
            }
        }
    }

    private fun install(release: Updater.Release) {
        updateBanner.setOnClickListener(null)
        Updater.downloadAndInstall(this, release) { progress ->
            updateBanner.text = when {
                progress < 0 -> "Échec du téléchargement — appuie pour réessayer".also { showUpdate(release) }
                progress >= 100 -> "Installation de ${release.version}…"
                else -> "Téléchargement… $progress %"
            }
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = servers.size
        override fun getItem(position: Int) = servers[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_server, parent, false)
            val server = servers[position]
            view.findViewById<TextView>(R.id.name).text = server.name
            val local = server.id == Adb.SERVER_ID
            view.findViewById<TextView>(R.id.address).text =
                if (local) "adb shell · ${android.os.Build.MODEL}" else server.address
            view.findViewById<TextView>(R.id.auth).text = when {
                local -> "adb"
                server.auth == AuthType.KEY -> "clé"
                server.auth == AuthType.PASSWORD -> "mdp"
                else -> "tailscale"
            }
            val count = Sessions.countFor(server.id)
            view.findViewById<TextView>(R.id.badge).apply {
                visibility = if (count > 0) View.VISIBLE else View.GONE
                text = if (count > 1) "● $count" else "●"
            }
            return view
        }
    }

    companion object {
        const val EXTRA_PICK = "pick"
    }
}
