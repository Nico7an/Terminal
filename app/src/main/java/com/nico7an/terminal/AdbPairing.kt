package com.nico7an.terminal

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Pairing with "Pair device with pairing code". The system dialog showing the code closes as soon as
 * another app comes to the front, so the code is typed in a notification (or in the app, in split screen).
 * The pairing port is found over mDNS: the user never has to type it.
 */
object AdbPairing {
    const val CHANNEL_ID = "adb"
    private const val NOTIFICATION_ID = 2
    private const val KEY_CODE = "code"
    private const val TIMEOUT_MS = 10 * 60_000L

    /** Pairing port found on this device, -1 while searching. */
    @Volatile var port = -1
        private set
    @Volatile var running = false
        private set
    @Volatile var busy = false
        private set
    /** Last outcome shown to the user, null while nothing happened. */
    @Volatile var message: String? = null
        private set

    /** Refreshes the setup screen. Always called on the main thread. */
    var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var discovery: AdbDiscovery? = null
    private val timeout = Runnable { stop() }

    fun start() {
        stop()
        running = true
        port = -1
        message = null
        discovery = AdbDiscovery(Adb.SERVICE_PAIRING) { p ->
            main.post {
                if (running && p != port) {
                    port = p
                    notifyState()
                    listener?.invoke()
                }
            }
        }.also { it.start() }
        main.postDelayed(timeout, TIMEOUT_MS)
        notifyState()
        listener?.invoke()
    }

    fun stop() {
        main.removeCallbacks(timeout)
        discovery?.stop()
        discovery = null
        running = false
        if (!busy) cancelNotification()
        listener?.invoke()
    }

    /** From the notification or the setup screen. */
    fun submit(code: String, done: (() -> Unit)? = null) {
        val digits = code.filter { it.isDigit() }
        if (digits.length != 6 || busy) {
            if (!busy) message = "Le code d'appairage fait 6 chiffres"
            notifyState()
            listener?.invoke()
            done?.invoke()
            return
        }
        busy = true
        message = "Appairage…"
        notifyState()
        listener?.invoke()
        Thread({
            // The process may have been restarted while the user was in the settings: search again.
            val p = port.takeIf { it > 0 } ?: Adb.find(Adb.SERVICE_PAIRING, 8_000) ?: -1
            val error = if (p > 0) runCatching { Adb.pair(p, digits) }.exceptionOrNull()
            else IllegalStateException("service d'appairage introuvable, la fenêtre « Associer l'appareil avec un code » doit rester ouverte")
            main.post {
                busy = false
                if (error == null) {
                    message = null
                    stop()
                    notifySuccess()
                } else {
                    if (!running) start()
                    message = "Échec de l'appairage : " + (error.message ?: error.javaClass.simpleName) +
                        ". Vérifie le code (il change à chaque ouverture de la fenêtre)."
                    notifyState()
                }
                listener?.invoke()
                done?.invoke()
            }
        }, "adb-pair").start()
    }

    // --- Notification ---

    private val context get() = App.instance
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    private fun openSetup(): PendingIntent = PendingIntent.getActivity(
        context, 10, Intent(context, AdbSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notifyState() {
        if (!running && !busy) return
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_prompt)
            .setContentIntent(openSetup())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
        val text = message ?: if (port > 0) "Saisis ici le code à 6 chiffres affiché par « Associer l'appareil avec un code »"
        else "Dans « Débogage sans fil », touche « Associer l'appareil avec un code »"
        builder.setContentTitle(if (port > 0) "Code d'appairage" else "Recherche du service d'appairage…")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
        if (port > 0 && !busy) {
            val input = RemoteInput.Builder(KEY_CODE).setLabel("Code à 6 chiffres").build()
            val reply = PendingIntent.getBroadcast(
                context, 11, Intent(context, AdbPairingReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(
                Notification.Action.Builder(null, "Saisir le code", reply)
                    .addRemoteInput(input)
                    .setAllowGeneratedReplies(false)
                    .build()
            )
        }
        runCatching { notifications.notify(NOTIFICATION_ID, builder.build()) }
    }

    private fun notifySuccess() {
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_prompt)
            .setContentTitle("Appairage réussi")
            .setContentText("Reviens dans Terminal pour ouvrir le terminal de cet appareil")
            .setContentIntent(openSetup())
            .setAutoCancel(true)
            .build()
        runCatching { notifications.notify(NOTIFICATION_ID, notification) }
    }

    private fun cancelNotification() = runCatching { notifications.cancel(NOTIFICATION_ID) }

    internal fun codeFrom(intent: Intent): String? =
        RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()
}

/** Receives the code typed in the pairing notification. */
class AdbPairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = AdbPairing.codeFrom(intent) ?: return
        val pending = goAsync()
        AdbPairing.submit(code) { pending.finish() }
    }
}
