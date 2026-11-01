package top.e404.training

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
class TrainingApplication : Application(), ImageLoaderFactory {
    val database: TrainingDatabase by lazy {
        Room.databaseBuilder(this, TrainingDatabase::class.java, "training.db")
            .addMigrations(HEALTH_CACHE_MIGRATION_1_2)
            .build()
    }
    val vault: ConnectionVault by lazy { ConnectionVault(this) }
    val preferences: AppPreferences by lazy { AppPreferences(this) }
    val repository: TrainingRepository by lazy { TrainingRepository(this, database, vault) }
    override fun onCreate() {
        super.onCreate()
        TimerEngine.createChannel(this)
        val connectivity = getSystemService(ConnectivityManager::class.java)
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { repository.enqueueSync() }
        })
    }
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this).components {
        add(SvgDecoder.Factory())
        if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
    }.build()
}
