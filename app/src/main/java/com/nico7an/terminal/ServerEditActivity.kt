package com.nico7an.terminal

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

class ServerEditActivity : Activity() {

    private lateinit var name: EditText
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var user: EditText
    private lateinit var password: EditText
    private lateinit var auth: RadioGroup
    private lateinit var keyField: TextView

    private var existing: Server? = null
    private var keyId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_edit)
        edgeToEdge(findViewById(R.id.root))

        name = findViewById(R.id.name)
        host = findViewById(R.id.host)
        port = findViewById(R.id.port)
        user = findViewById(R.id.user)
        password = findViewById(R.id.password)
        auth = findViewById(R.id.auth)
        keyField = findViewById(R.id.key)

        val duplicate = intent.getBooleanExtra(EXTRA_DUPLICATE, false)
        val source = intent.getStringExtra(EXTRA_ID)?.let { Vault.server(it) }
        existing = if (duplicate) null else source

        findViewById<TextView>(R.id.title).text = when {
            existing != null -> "Modifier"
            else -> "Nouveau serveur"
        }
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.cancel).setOnClickListener { finish() }
        findViewById<View>(R.id.save).setOnClickListener { save() }

        if (source != null && savedInstanceState == null) {
            name.setText(if (duplicate) "${source.name} (copie)" else source.name)
            host.setText(source.host)
            port.setText(source.port.toString())
            user.setText(source.user)
            password.setText(source.password ?: "")
            keyId = source.keyId
            auth.check(when (source.auth) {
                AuthType.KEY -> R.id.auth_key
                AuthType.PASSWORD -> R.id.auth_password
                AuthType.NONE -> R.id.auth_none
            })
        } else if (savedInstanceState == null) {
            auth.check(R.id.auth_key)
            keyId = Vault.keys().firstOrNull()?.id
        } else {
            keyId = savedInstanceState.getString("keyId")
        }

        auth.setOnCheckedChangeListener { _, _ -> updateSections() }
        keyField.setOnClickListener { pickKey() }
        updateSections()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the keys screen: pick the key that was just created if none was chosen.
        if (Vault.key(keyId) == null) keyId = Vault.keys().lastOrNull()?.id
        keyField.text = Vault.key(keyId)?.name ?: "Aucune clé — appuie pour en créer une"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("keyId", keyId)
    }

    private fun selectedAuth() = when (auth.checkedRadioButtonId) {
        R.id.auth_password -> AuthType.PASSWORD
        R.id.auth_none -> AuthType.NONE
        else -> AuthType.KEY
    }

    private fun updateSections() {
        val a = selectedAuth()
        findViewById<View>(R.id.key_section).visibility = if (a == AuthType.KEY) View.VISIBLE else View.GONE
        findViewById<View>(R.id.password_section).visibility = if (a == AuthType.PASSWORD) View.VISIBLE else View.GONE
        findViewById<View>(R.id.none_hint).visibility = if (a == AuthType.NONE) View.VISIBLE else View.GONE
    }

    private fun pickKey() {
        val keys = Vault.keys()
        if (keys.isEmpty()) {
            startActivity(Intent(this, KeysActivity::class.java))
            return
        }
        val labels = keys.map { it.name } + "Gérer les clés…"
        AlertDialog.Builder(this)
            .setTitle("Clé SSH")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == keys.size) {
                    startActivity(Intent(this, KeysActivity::class.java))
                } else {
                    keyId = keys[which].id
                    keyField.text = keys[which].name
                }
            }
            .show()
    }

    private fun save() {
        val h = host.text.toString().trim()
        val u = user.text.toString().trim()
        val p = port.text.toString().trim().ifEmpty { "22" }.toIntOrNull()
        val a = selectedAuth()
        when {
            h.isEmpty() -> return error(host, "Hôte requis")
            u.isEmpty() -> return error(user, "Utilisateur requis")
            p == null || p !in 1..65535 -> return error(port, "Port invalide")
            a == AuthType.KEY && Vault.key(keyId) == null -> {
                Toast.makeText(this, "Choisis ou crée une clé SSH", Toast.LENGTH_SHORT).show()
                return
            }
        }
        val server = Server(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = name.text.toString().trim().ifEmpty { h },
            host = h,
            port = p!!,
            user = u,
            auth = a,
            keyId = if (a == AuthType.KEY) keyId else null,
            password = if (a == AuthType.PASSWORD) password.text.toString().ifEmpty { null } else null,
        )
        Vault.saveServer(server)
        finish()
    }

    private fun error(field: EditText, message: String) {
        field.error = message
        field.requestFocus()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_DUPLICATE = "duplicate"
    }
}
