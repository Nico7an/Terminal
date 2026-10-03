package com.nico7an.terminal

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/**
 * Checklist for the built-in "this device" terminal: each step says if it is done and, if not, how to do it,
 * with a button to the right settings screen. Re-checked every time the user comes back from the settings.
 */
class AdbSetupActivity : Activity() {

    private enum class State(val label: String, val color: Int) {
        OK("OK", R.color.ok),
        TODO("À faire", R.color.error),
        WAITING("En attente", R.color.text_secondary),
        RUNNING("En cours…", R.color.warn),
        ADVICE("Conseillé", R.color.warn),
    }

    private lateinit var code: EditText
    private var testing = false
    /** Output of the failed permission test, "" when it passed, null when not run yet. */
    private var testOutput: String? = null
    private var connectError: String? = null
    private var wasReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_adb_setup)
        edgeToEdge(findViewById(R.id.root))

        findViewById<TextView>(R.id.title).text = Adb.server.name
        findViewById<View>(R.id.back).setOnClickListener { finish() }
        findViewById<View>(R.id.open).setOnClickListener { openTerminal() }

        code = findViewById(R.id.pairing_code)
        findViewById<View>(R.id.pairing_submit).setOnClickListener { submitCode() }
        code.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) submitCode()
            actionId == EditorInfo.IME_ACTION_DONE
        }

        intent.getStringExtra(EXTRA_REASON)?.let { connectError = it }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onResume() {
        super.onResume()
        AdbPairing.listener = { refresh() }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        AdbPairing.listener = null
    }

    private fun refresh() {
        if (isFinishing) return
        if (!Adb.supported) {
            findViewById<TextView>(R.id.intro).text =
                "Le débogage sans fil demande Android 11 ou plus récent. Cet appareil est sous Android ${Build.VERSION.RELEASE}."
            for (id in listOf(R.id.step_developer, R.id.step_wifi, R.id.step_wireless, R.id.step_pairing, R.id.step_test, R.id.step_battery)) {
                findViewById<View>(id).visibility = View.GONE
            }
            findViewById<View>(R.id.open).isEnabled = false
            return
        }

        val developer = Adb.developerOptions
        val wifi = Adb.onWifi
        val wireless = developer && wifi && Adb.enableWirelessDebugging()
        val paired = Adb.paired
        val ready = developer && wifi && wireless && paired
        if (ready && !wasReady) connectError = null
        wasReady = ready

        step(
            R.id.step_developer, "Options pour les développeurs", if (developer) State.OK else State.TODO,
            if (developer) "Activées."
            else if (Adb.isXiaomi) "Paramètres → À propos de la tablette → touche 7 fois « Version d'OS ». " +
                "Le menu apparaît ensuite dans Paramètres → Paramètres supplémentaires."
            else "Paramètres → À propos → touche 7 fois « Numéro de build ».",
            if (developer) null else "Ouvrir « À propos »" to { launch(Adb.aboutIntent()) },
        )

        step(
            R.id.step_wifi, "Wi-Fi", if (wifi) State.OK else State.TODO,
            if (wifi) "Connecté." else "Le débogage sans fil ne fonctionne que sur un réseau Wi-Fi (Internet n'est pas nécessaire, un VPN comme Tailscale peut rester actif).",
            if (wifi) null else "Paramètres Wi-Fi" to { launch(Intent(Settings.ACTION_WIFI_SETTINGS)) },
        )

        step(
            R.id.step_wireless, "Débogage sans fil",
            when {
                wireless -> State.OK
                !developer || !wifi -> State.WAITING
                else -> State.TODO
            },
            when {
                wireless && Adb.canWriteSecureSettings -> "Activé. Terminal le réactive tout seul quand Android le coupe (changement de Wi-Fi, redémarrage)."
                wireless -> "Activé. Android le coupe quand le Wi-Fi change : une fois le test d'autorisations réussi, Terminal le réactivera tout seul."
                else -> "Options pour les développeurs → « Débogage sans fil » → active-le, puis choisis « Toujours autoriser sur ce réseau »."
            },
            if (wireless || !developer) null else "Ouvrir le débogage sans fil" to { launch(Adb.wirelessDebuggingIntent(this)) },
        )

        val pairing = AdbPairing.running || AdbPairing.busy
        step(
            R.id.step_pairing, "Appairage",
            when {
                paired -> State.OK
                !wireless -> State.WAITING
                pairing -> State.RUNNING
                else -> State.TODO
            },
            if (paired) "Terminal est appairé (il apparaît dans « Appareils associés » du débogage sans fil). " +
                "En cas de souci, « Appairer à nouveau »."
            else "1. Touche « Appairer » : Terminal ouvre le débogage sans fil et trouve le port tout seul.\n" +
                "2. Touche « Associer l'appareil avec un code ».\n" +
                "3. Sans fermer la fenêtre du code, déroule la notification Terminal et saisis-y le code. " +
                "Si la notification n'a pas de champ de saisie, mets Terminal en écran partagé et saisis le code ci-dessous.",
            when {
                !wireless -> null
                pairing -> "Ouvrir le débogage sans fil" to { launch(Adb.wirelessDebuggingIntent(this)) }
                paired -> "Appairer à nouveau" to { startPairing() }
                else -> "Appairer" to { startPairing() }
            },
            if (pairing) "Annuler" to { AdbPairing.stop() } else null,
        )

        findViewById<View>(R.id.pairing_panel).visibility = if (pairing && !paired) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.pairing_status).text = (AdbPairing.message
            ?: if (AdbPairing.port > 0) "Service d'appairage trouvé (port ${AdbPairing.port}). Saisis le code à 6 chiffres."
            else "Recherche du service d'appairage… Touche « Associer l'appareil avec un code » dans le débogage sans fil.").nbsp()
        findViewById<View>(R.id.pairing_submit).isEnabled = !AdbPairing.busy

        val output = testOutput
        step(
            R.id.step_test, if (Adb.isXiaomi) "Autorisations adb (HyperOS)" else "Autorisations adb",
            when {
                testing -> State.RUNNING
                connectError != null -> State.TODO
                !ready || output == null -> State.WAITING
                output.isEmpty() -> State.OK
                else -> State.TODO
            },
            when {
                testing -> "Connexion à adb et test d'une commande système (pm grant)…"
                connectError != null -> "Connexion impossible : $connectError"
                !ready || output == null -> "Lancé automatiquement une fois l'appareil appairé : vérifie que adb peut agir sur le système et pas seulement le lire."
                output.isEmpty() -> "adb peut agir sur le système (pm, settings, input…). Terminal a obtenu le droit de réactiver le débogage sans fil tout seul."
                else -> Adb.permissionHelp(output)
            },
            if (!ready || testing) null else "Tester" to { runTest() },
            if (output.isNullOrEmpty() || testing) null else "Options pour les développeurs" to { launch(Adb.developerIntent()) },
        )

        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        step(
            R.id.step_battery, "Arrière-plan", if (battery) State.OK else State.ADVICE,
            if (battery) "Aucune restriction de batterie : les sessions restent ouvertes en arrière-plan."
            else if (Adb.isXiaomi) "HyperOS ferme les applis en arrière-plan. Infos de l'appli → Économie de batterie → « Aucune restriction », " +
                "et active « Démarrage automatique ». Tu peux aussi verrouiller Terminal dans les applis récentes."
            else "Infos de l'appli → Batterie → « Aucune restriction » pour que les sessions restent ouvertes en arrière-plan.",
            if (battery) null else "Infos de l'appli" to {
                launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            },
        )

        findViewById<View>(R.id.open).apply {
            isEnabled = ready
            alpha = if (ready) 1f else 0.4f
        }
        findViewById<TextView>(R.id.summary).text = when {
            !ready -> Adb.blocker()?.message ?: ""
            output?.isNotEmpty() == true -> "Connecté, mais HyperOS bloque certaines commandes"
            else -> ""
        }

        if (ready && output == null && !testing && connectError == null) runTest()
    }

    private fun step(
        id: Int, title: String, state: State, help: String,
        action: Pair<String, () -> Unit>?, action2: Pair<String, () -> Unit>? = null,
    ) {
        val view = findViewById<View>(id)
        view.findViewById<TextView>(R.id.title).text = title
        view.findViewById<TextView>(R.id.help).text = help.nbsp()
        view.findViewById<TextView>(R.id.state).apply {
            text = state.label
            setTextColor(getColor(state.color))
        }
        view.findViewById<View>(R.id.dot).background.mutate().setTint(getColor(state.color))
        view.findViewById<View>(R.id.actions).visibility = if (action == null && action2 == null) View.GONE else View.VISIBLE
        bind(view.findViewById(R.id.action), action)
        bind(view.findViewById(R.id.action2), action2)
    }

    private fun bind(button: Button, action: Pair<String, () -> Unit>?) {
        button.visibility = if (action == null) View.GONE else View.VISIBLE
        button.text = action?.first
        button.setOnClickListener { action?.second?.invoke() }
    }

    private fun launch(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun startPairing() {
        AdbPairing.start()
        launch(Adb.wirelessDebuggingIntent(this))
    }

    private fun submitCode() {
        AdbPairing.submit(code.text.toString()) { code.setText("") }
    }

    private fun runTest() {
        if (testing) return
        testing = true
        connectError = null
        refresh()
        Thread({
            val result = runCatching { Adb.testPermissions() }
            runOnUiThread {
                testing = false
                result.onSuccess { output ->
                    testOutput = output ?: ""
                    if (output != null && !isFinishing) showPermissionHelp(output)
                }.onFailure {
                    testOutput = null
                    connectError = it.message ?: it.javaClass.simpleName
                }
                refresh()
            }
        }, "adb-test").start()
    }

    private fun showPermissionHelp(output: String) {
        AlertDialog.Builder(this)
            .setTitle(if (Adb.isXiaomi) "HyperOS bloque adb" else "adb est limité")
            .setMessage(Adb.permissionHelp(output).nbsp())
            .setPositiveButton("Options développeur") { _, _ -> launch(Adb.developerIntent()) }
            .setNeutralButton("Retester") { _, _ -> runTest() }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun openTerminal() {
        // Reuse the tab that failed and sent the user here.
        val dead = Sessions.tabs.find { it.server.id == Adb.SERVER_ID && !it.session.isRunning && !it.session.isConnecting }
        if (dead != null) {
            Sessions.current = dead
            dead.session.reconnect()
        } else {
            Sessions.open(Adb.server)
        }
        startActivity(Intent(this, TerminalActivity::class.java))
        finish()
    }

    companion object {
        /** Why the terminal could not connect, shown on the test step. */
        const val EXTRA_REASON = "reason"
    }
}
