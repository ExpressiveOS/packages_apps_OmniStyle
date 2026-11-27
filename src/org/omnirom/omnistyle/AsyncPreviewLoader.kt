/*
 * Copyright (C) 2026 The ExpressiveOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.omnirom.omnistyle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.android.axion.compose.expressive.ExpressiveMorphLoadingIndicator
import com.android.axion.compose.expressive.rememberExpressiveReducedMotion
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface PreviewImageSource {
    val cacheKey: String

    data class PackageDrawable(
        val packageName: String,
        val resId: Int,
        val maxDimensionPx: Int = 1080,
    ) : PreviewImageSource {
        override val cacheKey: String = "res:$packageName:$resId:$maxDimensionPx"
    }

    data class RemoteUrl(
        val url: String,
        val maxDimensionPx: Int = 960,
    ) : PreviewImageSource {
        override val cacheKey: String = "url:$url:$maxDimensionPx"
    }
}

private sealed interface PreviewDecodeState {
    data object Loading : PreviewDecodeState
    data class Ready(val bitmap: ImageBitmap) : PreviewDecodeState
    data object Error : PreviewDecodeState
}

object AsyncPreviewCache {
    // 24 MiB in-memory decoded preview cache
    private val memoryCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun getCached(key: String): ImageBitmap? = synchronized(memoryCache) {
        memoryCache.get(key)?.takeIf { !it.isRecycled }?.asImageBitmap()
    }

    suspend fun load(context: Context, source: PreviewImageSource): ImageBitmap? {
        getCached(source.cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            synchronized(memoryCache) {
                memoryCache.get(source.cacheKey)?.takeIf { !it.isRecycled }
            }?.let { return@withContext it.asImageBitmap() }

            val decoded: Bitmap? = when (source) {
                is PreviewImageSource.PackageDrawable -> decodePackageDrawable(
                    context = context,
                    packageName = source.packageName,
                    resId = source.resId,
                    maxDimensionPx = source.maxDimensionPx,
                )

                is PreviewImageSource.RemoteUrl -> decodeRemoteUrl(
                    context = context,
                    url = source.url,
                    maxDimensionPx = source.maxDimensionPx,
                )
            }

            if (decoded != null) {
                synchronized(memoryCache) {
                    memoryCache.put(source.cacheKey, decoded)
                }
                decoded.asImageBitmap()
            } else {
                null
            }
        }
    }

    private fun decodePackageDrawable(
        context: Context,
        packageName: String,
        resId: Int,
        maxDimensionPx: Int,
    ): Bitmap? {
        if (resId == 0) return null
        return runCatching {
            val res = if (packageName == context.packageName) {
                context.resources
            } else {
                context.packageManager.getResourcesForApplication(packageName)
            }

            val boundsOpts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeResource(res, resId, boundsOpts)
            if (boundsOpts.outWidth > 0 && boundsOpts.outHeight > 0) {
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = computeInSampleSize(
                        boundsOpts.outWidth,
                        boundsOpts.outHeight,
                        maxDimensionPx,
                    )
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeResource(res, resId, decodeOpts)?.let { return@runCatching it }
            }

            val drawable = res.getDrawable(resId, null) ?: return@runCatching null
            val rawW = drawable.intrinsicWidth.coerceAtLeast(256)
            val rawH = drawable.intrinsicHeight.coerceAtLeast(128)
            val scale = (maxDimensionPx.toFloat() / maxOf(rawW, rawH).toFloat()).coerceAtMost(1f)
            val targetW = (rawW * scale).toInt().coerceAtLeast(1)
            val targetH = (rawH * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, targetW, targetH)
            drawable.draw(canvas)
            bitmap
        }.getOrNull()
    }

    private fun decodeRemoteUrl(
        context: Context,
        url: String,
        maxDimensionPx: Int,
    ): Bitmap? {
        return runCatching {
            val thumbDir = File(context.cacheDir, "remote_thumbs").apply { mkdirs() }
            val cacheFile = File(thumbDir, "thumb_${url.hashCode().toUInt()}.img")
            if (!cacheFile.exists() || cacheFile.length() == 0L) {
                if (!HeaderRepository.downloadFile(url, cacheFile)) {
                    return@runCatching null
                }
            }
            val boundsOpts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(cacheFile.absolutePath, boundsOpts)
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
                cacheFile.delete()
                return@runCatching null
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = computeInSampleSize(
                    boundsOpts.outWidth,
                    boundsOpts.outHeight,
                    maxDimensionPx,
                )
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(cacheFile.absolutePath, decodeOpts)
        }.getOrNull()
    }

    private fun computeInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        val largest = maxOf(width, height)
        while (largest / (sample * 2) >= maxDim) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AsyncPreviewImage(
    source: PreviewImageSource,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val reducedMotion = rememberExpressiveReducedMotion()

    val decodeState by produceState<PreviewDecodeState>(
        initialValue = AsyncPreviewCache.getCached(source.cacheKey)
            ?.let { PreviewDecodeState.Ready(it) }
            ?: PreviewDecodeState.Loading,
        key1 = source.cacheKey,
    ) {
        if (value is PreviewDecodeState.Ready) return@produceState
        val loaded = AsyncPreviewCache.load(context, source)
        value = if (loaded != null) {
            PreviewDecodeState.Ready(loaded)
        } else {
            PreviewDecodeState.Error
        }
    }

    Crossfade(
        targetState = decodeState,
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            MaterialTheme.motionScheme.fastEffectsSpec()
        },
        modifier = modifier,
        label = "AsyncPreviewCrossfade",
    ) { state ->
        when (state) {
            is PreviewDecodeState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    ExpressiveMorphLoadingIndicator(
                        size = 38.dp,
                        contained = true,
                    )
                }
            }

            is PreviewDecodeState.Ready -> {
                Image(
                    bitmap = state.bitmap,
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            is PreviewDecodeState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BrokenImage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
