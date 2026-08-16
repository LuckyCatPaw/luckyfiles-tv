package com.luckycatpaw.luckyfilestv.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Size
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import com.luckycatpaw.luckyfilestv.data.common.ProviderCallRunner
import com.luckycatpaw.luckyfilestv.data.provider.model.DocumentRootInfo
import com.luckycatpaw.luckyfilestv.data.provider.model.ProviderDocumentInfo
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ProviderVisualRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val packageManager = appContext.packageManager

    suspend fun loadThumbnail(document: ProviderDocumentInfo, width: Int = 384, height: Int = 240): Bitmap? {
        if (document.isDirectory || !document.supportsThumbnail) {
            return null
        }

        return withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()

            try {
                withTimeoutOrNull(PROVIDER_THUMBNAIL_TIMEOUT) {
                    ProviderCallRunner.run { signal ->
                        resolver.loadThumbnail(
                            document.uri,
                            Size(width, height),
                            signal
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun loadRootIcon(root: DocumentRootInfo, size: Int = 128): Bitmap? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()

        val drawable = runCatching {
            if (root.iconResId != 0) {
                val resources = packageManager.getResourcesForApplication(root.packageName)
                ResourcesCompat.getDrawable(resources, root.iconResId, null)
            } else {
                packageManager.getApplicationIcon(root.packageName)
            }
        }.getOrNull() ?: return@withContext null

        drawableToBitmap(drawable = drawable, width = size, height = size)
    }

    suspend fun loadDocumentIcon(document: ProviderDocumentInfo, size: Int = 128): Bitmap? =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()

            val providerInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveContentProvider(
                    document.authority,
                    PackageManager.ComponentInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.resolveContentProvider(document.authority, 0)
            } ?: return@withContext null

            val drawable = runCatching {
                val resources = packageManager.getResourcesForApplication(providerInfo.applicationInfo)
                ResourcesCompat.getDrawable(resources, document.iconResId, null)
            }.getOrNull() ?: return@withContext null

            drawableToBitmap(drawable = drawable, width = size, height = size)
        }

    private fun drawableToBitmap(drawable: Drawable, width: Int, height: Int): Bitmap {
        if (drawable is android.graphics.drawable.BitmapDrawable && drawable.bitmap != null) {
            val source = drawable.bitmap
            if (source.width == width && source.height == height) {
                return source
            }
        }

        val bitmap = createBitmap(
            width.coerceAtLeast(1),
            height.coerceAtLeast(1)
        )

        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(canvas)
        return bitmap
    }

    companion object {
        private val PROVIDER_THUMBNAIL_TIMEOUT = 8000.milliseconds

        @Volatile
        private var instance: ProviderVisualRepository? = null

        fun get(context: Context): ProviderVisualRepository = instance ?: synchronized(this) {
            instance ?: ProviderVisualRepository(context.applicationContext).also {
                instance = it
            }
        }
    }
}
