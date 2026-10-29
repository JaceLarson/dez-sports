package com.dez.sports

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.dez.sports.data.EspnApi
import com.dez.sports.data.Favorites

class DezApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        EspnApi.init(cacheDir)
        Favorites.init(this)
        com.dez.sports.live.LivePins.load(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { EspnApi.client })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("logos")).maxSizeBytes(150L * 1024 * 1024).build() }
            .crossfade(220)
            .build()
}
