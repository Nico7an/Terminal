package com.nico7an.terminal

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class KeysActivity : Activity() {

    private lateinit var list: ListView
    private lateinit var empty: View
    private var keys = listOf<SshKey>()
    /** The private key field of the open import dialog, filled from the file picker. */
    private var importField: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keys)
        edgeToEdge(findViewById(R.id.root))

        findViewById<TextView>(R.id.title).text = "Clés SSH"
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.generate).setOnClickListener { generate() }
        findViewById<View>(R.id.import_key).setOnClickListener { import() }

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> showPublic(keys[position]) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            delete(keys[position])
            true
        }
        refresh()
    }

    private fun refresh() {
        keys = Vault.keys()
        adapter.notifyDataSetChanged()
        empty.visibility = if (keys.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun field(hint: String, text: String = ""): Pair<View, EditText> {
        val input = EditText(this).apply {
            setText(text)
            this.hint = hint
            setSingleLine()
            setTextColor(getColor(R.color.text_primary))
            setBackgroundResource(R.drawable.bg_field)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        return box to input
    }

    private fun generate() {
        val (box, input) = field("Nom de la clé", android.os.Build.MODEL.lowercase().replace(' ', '-'))
        AlertDialog.Builder(this)
            .setTitle("Nouvelle clé Ed25519")
            .setView(box)
            .setPositiveButton("Générer") { _, _ ->
                val key = Ssh.generateKey(input.text.toString().trim().ifEmpty { "android" })
                Vault.saveKey(key)
                refresh()
                showPublic(key)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun import() {
        val view = layoutInflater.inflate(R.layout.dialog_import, null)
        val name = view.findViewById<EditText>(R.id.name)
        val privateKey = view.findViewById<EditText>(R.id.private_key)
        val passphrase = view.findViewById<EditText>(R.id.passphrase)
        importField = privateKey
        view.findViewById<View>(R.id.pick_file).setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                REQUEST_FILE,
            )
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Importer une clé")
            .setView(view)
            .setPositiveButton("Importer", null)
            .setNegativeButton("Annuler", null)
            .setOnDismissListener { importField = null }
            .show()
        // Custom click handler so the dialog stays open on error.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val key = Ssh.importKey(
                    name.text.toString().trim().ifEmpty { "importée" },
                    privateKey.text.toString(),
                    passphrase.text.toString().ifEmpty { null },
                )
                Vault.saveKey(key)
                refresh()
                dialog.dismiss()
            } catch (e: Exception) {
                Toast.makeText(this, "Clé illisible : ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_FILE || resultCode != RESULT_OK || uri == null) return
        runCatching {
            contentResolver.openInputStream(uri)?.use { stream ->
                // A private key is a few KB at most: refuse anything bigger.
                val bytes = stream.readNBytesCompat(64 * 1024)
                importField?.setText(String(bytes, Charsets.UTF_8))
            }
        }.onFailure { Toast.makeText(this, "Lecture impossible", Toast.LENGTH_SHORT).show() }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun showPublic(key: SshKey) {
        val text = TextView(this).apply {
            this.text = key.publicKey
            typeface = resources.getFont(R.font.cascadia_mono)
            textSize = 12f
            setTextColor(getColor(R.color.text_primary))
            setTextIsSelectable(true)
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val fingerprint = TextView(this).apply {
            this.text = key.fingerprint
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(dp(24), dp(12), dp(24), 0)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(text)
            addView(fingerprint)
        }
        AlertDialog.Builder(this)
            .setTitle(key.name)
            .setView(box)
            .setPositiveButton("Copier la clé publique") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Clé publique", key.publicKey))
                Toast.makeText(this, "Copiée — à coller dans ~/.ssh/authorized_keys", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun delete(key: SshKey) {
        val users = Vault.servers().filter { it.keyId == key.id }
        val message = if (users.isEmpty()) "Cette action est définitive."
        else "Utilisée par : ${users.joinToString(", ") { it.name }}.\nCes serveurs ne pourront plus se connecter."
        val dialog = AlertDialog.Builder(this)
            .setTitle("Supprimer ${key.name} ?")
            .setMessage(message)
            .setPositiveButton("Supprimer") { _, _ ->
                Vault.deleteKey(key.id)
                refresh()
            }
            .setNegativeButton("Annuler", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.error))
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = keys.size
        override fun getItem(position: Int) = keys[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_key, parent, false)
            view.findViewById<TextView>(R.id.name).text = keys[position].name
            view.findViewById<TextView>(R.id.fingerprint).text = keys[position].fingerprint
            return view
        }
    }

    companion object {
        private const val REQUEST_FILE = 1
    }
}
