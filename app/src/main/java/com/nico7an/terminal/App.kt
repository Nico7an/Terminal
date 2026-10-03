package com.nico7an.terminal

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Android ships a stripped-down "BC" provider: replace it with the full one so sshj gets
        // Ed25519/X25519/ChaCha20. Appended last so it never shadows the AndroidKeyStore providers.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(SshService.CHANNEL_ID, "Sessions SSH", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(AdbPairing.CHANNEL_ID, "Appairage adb", NotificationManager.IMPORTANCE_HIGH).apply {
                setShowBadge(false)
            }
        )

        // Warm up the slow parts off the main thread so the first connection is instant.
        Thread({
            Vault.load(this)
            Ssh.config
        }, "Warmup").start()
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
