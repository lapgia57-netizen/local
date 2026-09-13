package com.example.locationmap

import android.app.Application
import android.util.Log
import androidx.preference.PreferenceManager
import org.osmdroid.config.Configuration
import java.io.File

/**
 * Application class – khởi tạo osmdroid một lần duy nhất, ổn định trên mọi thiết bị.
 */
class LocationMapApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initOsmdroid()
    }

    private fun initOsmdroid() {
        try {
            val ctx = applicationContext

            Configuration.getInstance().load(
                ctx,
                PreferenceManager.getDefaultSharedPreferences(ctx)
            )

            // User-Agent bắt buộc để OpenStreetMap không block request
            Configuration.getInstance().userAgentValue =
                "LocationMapApp/2.0 (Android; OSM; com.example.locationmap)"

            // App-specific storage – không cần quyền WRITE_EXTERNAL trên Android 10+
            val basePath = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "osmdroid")
            val tileCache = File(basePath, "tiles")
            if (!basePath.exists()) basePath.mkdirs()
            if (!tileCache.exists()) tileCache.mkdirs()

            Configuration.getInstance().osmdroidBasePath = basePath
            Configuration.getInstance().osmdroidTileCache = tileCache

            // Cấu hình ổn định
            Configuration.getInstance().isDebugMode = false
            Configuration.getInstance().isDebugTileProviders = false
            Configuration.getInstance().isDebugMapView = false
            Configuration.getInstance().tileDownloadThreads = 2
            Configuration.getInstance().tileFileSystemThreads = 2
            Configuration.getInstance().tileDownloadMaxQueueSize = 40
            Configuration.getInstance().tileFileSystemMaxQueueSize = 40
            Configuration.getInstance().expirationOverrideDuration = 1000L * 60 * 60 * 24 * 7 // 7 days

            Log.i(TAG, "osmdroid initialized. Cache: ${tileCache.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init osmdroid", e)
        }
    }

    companion object {
        private const val TAG = "LocationMapApp"
    }
}
