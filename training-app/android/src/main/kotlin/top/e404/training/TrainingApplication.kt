package top.e404.training

import android.app.Application
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder

class TrainingApplication : Application(), ImageLoaderFactory {
    val database: TrainingDatabase by lazy { Room.databaseBuilder(this, TrainingDatabase::class.java, "training.db").build() }
    val vault: ConnectionVault by lazy { ConnectionVault(this) }
    val repository: TrainingRepository by lazy { TrainingRepository(this, database, vault) }
    override fun onCreate() { super.onCreate(); TimerEngine.createChannel(this) }
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this).components {
        add(SvgDecoder.Factory())
        if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
    }.build()
}
