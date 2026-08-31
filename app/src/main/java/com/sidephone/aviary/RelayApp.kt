package com.sidephone.aviary

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.room.Room
import com.sidephone.aviary.data.AviaryDatabase
import com.sidephone.aviary.data.DbCrypto
import com.sidephone.aviary.data.UnifiedRepository
import com.sidephone.aviary.transport.TransportRegistry
import com.sidephone.aviary.transport.imessage.IMessageTransport
import com.sidephone.aviary.transport.instagram.InstagramTransport
import com.sidephone.aviary.transport.signal.SignalTransport
import com.sidephone.aviary.transport.sms.SmsTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

class RelayApp : Application(), coil.ImageLoaderFactory {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Coil loader with animated-image support (GIF/WebP) via Android's ImageDecoder. */
    override fun newImageLoader(): coil.ImageLoader =
        coil.ImageLoader.Builder(this)
            .components { add(coil.decode.ImageDecoderDecoder.Factory()) }
            .build()

    val db: AviaryDatabase by lazy {
        System.loadLibrary("sqlcipher")
        Room.databaseBuilder(this, AviaryDatabase::class.java, "aviary.db")
            .openHelperFactory(SupportOpenHelperFactory(DbCrypto.passphrase(this)))
            .addMigrations(
                com.sidephone.aviary.data.MIGRATION_1_2,
                com.sidephone.aviary.data.MIGRATION_2_3,
                com.sidephone.aviary.data.MIGRATION_3_4,
                com.sidephone.aviary.data.MIGRATION_4_5,
                com.sidephone.aviary.data.MIGRATION_5_6,
                com.sidephone.aviary.data.MIGRATION_6_7,
            )
            .build()
    }

    val repository: UnifiedRepository by lazy { UnifiedRepository(db) }
    val typing: com.sidephone.aviary.data.TypingTracker by lazy {
        com.sidephone.aviary.data.TypingTracker(appScope)
    }
    val contactNames: com.sidephone.aviary.data.ContactNames by lazy {
        com.sidephone.aviary.data.ContactNames(this)
    }
    val avatarStore: com.sidephone.aviary.data.AvatarStore by lazy {
        com.sidephone.aviary.data.AvatarStore(this)
    }
    val mediaStore: com.sidephone.aviary.data.MediaStore by lazy {
        com.sidephone.aviary.data.MediaStore(this)
    }

    val smsTransport: SmsTransport by lazy { SmsTransport(this, repository, mediaStore, avatarStore, appScope) }
    val signalTransport: SignalTransport by lazy {
        SignalTransport(this, repository, appScope, contactNames, avatarStore, mediaStore)
    }
    val imessageTransport: IMessageTransport by lazy {
        IMessageTransport(this, repository, appScope, avatarStore, mediaStore)
    }
    val instagramTransport: InstagramTransport by lazy {
        InstagramTransport(this, repository, appScope, avatarStore, mediaStore)
    }

    val router: com.sidephone.aviary.transport.MessageRouter by lazy {
        com.sidephone.aviary.transport.MessageRouter(transports)
    }

    val outbox: com.sidephone.aviary.transport.Outbox by lazy {
        com.sidephone.aviary.transport.Outbox(this, repository, transports, appScope)
    }

    val transports: TransportRegistry by lazy {
        TransportRegistry(
            listOf(
                smsTransport,
                signalTransport,
                imessageTransport,
                instagramTransport,
            )
        )
    }

    /** The conversation currently on screen, if any — used to suppress its notifications. */
    @Volatile
    var foregroundConversationId: Long? = null

    /** Whether the app is in the foreground — transports poll faster when it is, to save battery. */
    @Volatile
    var isForeground: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        appScope.launch { transports.startAll() }
        // Auto-retry messages that failed to send, when the network returns.
        outbox.start()
        // Keep the attachment cache under budget (evict oldest media).
        appScope.launch { runCatching { mediaStore.enforceBudget() } }
        // Keep the process alive in the background so notifications stay reliable.
        com.sidephone.aviary.transport.ReceiveService.start(this)
        // Watchdog: restart the receive service if the OEM battery manager kills it.
        com.sidephone.aviary.transport.WatchdogWorker.schedule(this)
        // Track foreground/background so background polling can back off and save battery.
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) { isForeground = true }
                override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { isForeground = false }
            }
        )
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Incoming messages" }
        )
        // Low-key channel for the ongoing "keeping alive in the background" notification.
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BACKGROUND,
                "Background activity",
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = "Keeps Messenger receiving in the background" }
        )
    }

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_BACKGROUND = "background"
    }
}
