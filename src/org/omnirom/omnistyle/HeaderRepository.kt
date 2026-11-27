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

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateFormat
import android.util.Log
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

enum class PackScheduleKind {
    HOURLY,
    CALENDAR,
    RANDOM,
    INTERVAL,
    COLLECTION,
}

data class DaylightHeaderItem(
    val packageName: String,
    val imageName: String,
    val displayName: String,
    val resId: Int,
    val scheduleTag: String?,
) {
    val staticSettingValue: String
        get() = "$packageName/$imageName"
}

data class DaylightHeaderPack(
    val label: String,
    val packId: String,
    val packageName: String,
    val headerName: String?,
    val creator: String?,
    val scheduleKind: PackScheduleKind,
    val intervalMinutes: Int,
    val items: List<DaylightHeaderItem>,
)

data class RemoteMediaItem(
    val fileName: String,
    val displayName: String,
    val creator: String,
    val tag: String,
    val thumbUrl: String,
    val fullUrl: String,
)

data class LocalWallpaperItem(
    val packageName: String,
    val imageName: String,
    val displayName: String,
    val creator: String,
    val resId: Int,
)

object HeaderRepository {
    private const val TAG = "OmniStyleRepo"

    private const val ACTION_HEADER_PACK = "org.omnirom.DaylightHeaderPack"
    private const val ACTION_HEADER_PACK_1 = "org.omnirom.DaylightHeaderPack1"

    private const val HEADERS_LIST_URI =
        "https://dl.omnirom.org/images/headers/thumbs/json_headers_xml.php"
    private const val HEADERS_THUMB_URI = "https://dl.omnirom.org/images/headers/thumbs/"
    private const val HEADERS_FULL_URI = "https://dl.omnirom.org/images/headers/"

    private const val WALLPAPER_LIST_URI =
        "https://dl.omnirom.org/images/wallpapers/thumbs/json_wallpapers_xml.php"
    private const val WALLPAPER_THUMB_URI = "https://dl.omnirom.org/images/wallpapers/thumbs/"
    private const val WALLPAPER_FULL_URI = "https://dl.omnirom.org/images/wallpapers/"

    private const val HTTP_TIMEOUT_MS = 20_000
    private const val DURABLE_HEADERS_DIR = "headers"
    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    private const val SYSTEMUI_PACKAGE = "com.android.systemui"

    private const val PREFS_NAME = "omnistyle_prefs"
    private const val KEY_APPLIED_REMOTE_FILE = "applied_remote_header_file"
    private const val KEY_APPLIED_REMOTE_URI = "applied_remote_header_uri"

    fun isNetworkConnected(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun saveAppliedRemoteHeader(context: Context, fileName: String, uriString: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APPLIED_REMOTE_FILE, fileName)
            .putString(KEY_APPLIED_REMOTE_URI, uriString)
            .apply()
    }

    fun getPersistedRemoteHeaderFileName(context: Context, activeFileUri: String): String {
        if (activeFileUri.isBlank()) return ""
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedUri = prefs.getString(KEY_APPLIED_REMOTE_URI, "").orEmpty()
        val savedFile = prefs.getString(KEY_APPLIED_REMOTE_FILE, "").orEmpty()
        return if (savedUri.isNotBlank() && savedUri == activeFileUri) savedFile else ""
    }

    suspend fun loadAvailableHeaderPacks(context: Context): List<DaylightHeaderPack> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val rawPacks = linkedMapOf<String, String>()

            val packIntent = Intent(ACTION_HEADER_PACK)
            for (resolveInfo in pm.queryIntentActivities(packIntent, 0)) {
                val pkg = resolveInfo.activityInfo.packageName
                val label = resolveInfo.activityInfo.loadLabel(pm)?.toString()?.takeIf {
                    it.isNotBlank()
                } ?: pkg
                rawPacks[label] = pkg
            }

            val pack1Intent = Intent(ACTION_HEADER_PACK_1)
            for (resolveInfo in pm.queryIntentActivities(pack1Intent, 0)) {
                val pkg = resolveInfo.activityInfo.packageName
                val activityName = resolveInfo.activityInfo.name
                val label = resolveInfo.activityInfo.loadLabel(pm)?.toString()?.takeIf {
                    it.isNotBlank()
                } ?: pkg
                rawPacks[label] = "$pkg/$activityName"
            }

            rawPacks.entries
                .sortedBy { it.key.lowercase(Locale.ROOT) }
                .mapNotNull { (label, packId) ->
                    loadSingleHeaderPack(context, label, packId)
                }
        }

    suspend fun loadThemeHeaderPack(context: Context): DaylightHeaderPack? =
        withContext(Dispatchers.IO) {
            loadSingleHeaderPack(
                context = context,
                label = context.getString(R.string.browse_theme_header),
                packId = "org.omnirom.omnistyle/org.omnirom.omnistyle.theme",
            )
        }

    private fun loadSingleHeaderPack(
        context: Context,
        label: String,
        packId: String,
    ): DaylightHeaderPack? {
        val slashIndex = packId.indexOf('/')
        val packageName: String
        val headerName: String?
        if (slashIndex >= 0) {
            packageName = packId.substring(0, slashIndex)
            headerName = packId.substring(slashIndex + 1)
        } else {
            packageName = packId
            headerName = null
        }

        return try {
            val res = context.packageManager.getResourcesForApplication(packageName)
            parseDaylightHeaderPack(
                context = context,
                res = res,
                label = label,
                packId = packId,
                packageName = packageName,
                headerName = headerName,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Unable to load header pack $packId", e)
            null
        }
    }

    private fun parseDaylightHeaderPack(
        context: Context,
        res: Resources,
        label: String,
        packId: String,
        packageName: String,
        headerName: String?,
    ): DaylightHeaderPack {
        val assetFileName = if (headerName == null) {
            "daylight_header.xml"
        } else {
            val dotIdx = headerName.lastIndexOf('.')
            val shortName = if (dotIdx >= 0) headerName.substring(dotIdx + 1) else headerName
            "$shortName.xml"
        }

        val locale = Locale.getDefault()
        val is24Hour = DateFormat.is24HourFormat(context)
        val timeFormat = SimpleDateFormat(
            DateFormat.getBestDateTimePattern(locale, if (is24Hour) "Hm" else "hma"),
            locale,
        )
        val dateFormat = SimpleDateFormat(
            DateFormat.getBestDateTimePattern(locale, "MMMd"),
            locale,
        )
        val calendar = Calendar.getInstance()

        fun formatHour(hour: Int): String {
            calendar.set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            return timeFormat.format(calendar.time)
        }

        fun formatMonthDay(month: Int, day: Int): String {
            calendar.set(Calendar.MONTH, (month - 1).coerceIn(0, 11))
            calendar.set(Calendar.DAY_OF_MONTH, day.coerceIn(1, 31))
            return dateFormat.format(calendar.time)
        }

        val items = mutableListOf<DaylightHeaderItem>()
        var creator: String? = null
        var intervalMinutes = 0
        var hasHour = false
        var hasCalendar = false
        var hasRandom = false
        var hasList = false

        var parser: XmlPullParser? = null
        res.assets.open(assetFileName).use { input ->
            val factory = XmlPullParserFactory.newInstance()
            parser = factory.newPullParser().apply {
                setInput(input, StandardCharsets.UTF_8.name())
            }

            var eventType = parser!!.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (parser!!.name.lowercase(Locale.ROOT)) {
                        "day_header" -> {
                            val day = parser!!.getAttributeValue(null, "day")?.toIntOrNull() ?: -1
                            val month =
                                parser!!.getAttributeValue(null, "month")?.toIntOrNull() ?: -1
                            val hour = parser!!.getAttributeValue(null, "hour")?.toIntOrNull() ?: -1
                            val image = parser!!.getAttributeValue(null, "image")
                            val name = parser!!.getAttributeValue(null, "name")
                            if (!image.isNullOrBlank() && day != -1 && month != -1) {
                                hasCalendar = true
                                val resId = res.getIdentifier(image, "drawable", packageName)
                                val datePart = formatMonthDay(month, day)
                                val schedule = if (hour >= 0) {
                                    "$datePart · ${formatHour(hour)}"
                                } else {
                                    datePart
                                }
                                items.add(
                                    DaylightHeaderItem(
                                        packageName = packageName,
                                        imageName = image,
                                        displayName = name?.takeIf { it.isNotBlank() }
                                            ?: prettifyAssetName(image),
                                        resId = resId,
                                        scheduleTag = schedule,
                                    )
                                )
                            }
                        }

                        "hour_header" -> {
                            val hour = parser!!.getAttributeValue(null, "hour")?.toIntOrNull() ?: -1
                            val image = parser!!.getAttributeValue(null, "image")
                            val name = parser!!.getAttributeValue(null, "name")
                            if (!image.isNullOrBlank() && hour != -1) {
                                hasHour = true
                                val resId = res.getIdentifier(image, "drawable", packageName)
                                items.add(
                                    DaylightHeaderItem(
                                        packageName = packageName,
                                        imageName = image,
                                        displayName = name?.takeIf { it.isNotBlank() }
                                            ?: prettifyAssetName(image),
                                        resId = resId,
                                        scheduleTag = formatHour(hour),
                                    )
                                )
                            }
                        }

                        "random_header", "list_header" -> {
                            val isRandom = parser!!.name.equals("random_header", ignoreCase = true)
                            if (isRandom) hasRandom = true else hasList = true
                            val image = parser!!.getAttributeValue(null, "image")
                            val name = parser!!.getAttributeValue(null, "name")
                            if (!image.isNullOrBlank()) {
                                val resId = res.getIdentifier(image, "drawable", packageName)
                                items.add(
                                    DaylightHeaderItem(
                                        packageName = packageName,
                                        imageName = image,
                                        displayName = name?.takeIf { it.isNotBlank() }
                                            ?: prettifyAssetName(image),
                                        resId = resId,
                                        scheduleTag = null,
                                    )
                                )
                            }
                        }

                        "change_interval" -> {
                            intervalMinutes =
                                parser!!.getAttributeValue(null, "minutes")?.toIntOrNull() ?: 0
                        }

                        "meta_data" -> {
                            creator =
                                parser!!.getAttributeValue(null, "creator")?.takeIf {
                                    it.isNotBlank()
                                }
                        }
                    }
                }
                eventType = parser!!.next()
            }
        }
        if (parser is XmlResourceParser) {
            (parser as XmlResourceParser).close()
        }

        val kind = when {
            hasRandom -> PackScheduleKind.RANDOM
            hasList && intervalMinutes > 0 -> PackScheduleKind.INTERVAL
            hasCalendar -> PackScheduleKind.CALENDAR
            hasHour -> PackScheduleKind.HOURLY
            else -> PackScheduleKind.COLLECTION
        }

        return DaylightHeaderPack(
            label = label,
            packId = packId,
            packageName = packageName,
            headerName = headerName,
            creator = creator,
            scheduleKind = kind,
            intervalMinutes = intervalMinutes,
            items = items,
        )
    }

    suspend fun fetchRemoteHeaders(): Result<List<RemoteMediaItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = fetchString(HEADERS_LIST_URI)
                    ?: error("Failed to fetch remote headers")
                val json = JSONArray(payload)
                val items = ArrayList<RemoteMediaItem>(json.length())
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    val fileName = obj.optString("filename", "")
                    val dot = fileName.lastIndexOf('.')
                    if (dot == -1) continue
                    val ext = fileName.substring(dot).lowercase(Locale.ROOT)
                    if (ext != ".png" && ext != ".jpg" && ext != ".jpeg" && ext != ".webp") {
                        continue
                    }
                    val creator = obj.optString("creator", "").ifBlank { "OmniROM" }
                    val name = obj.optString("name", "").ifBlank { prettifyAssetName(fileName) }
                    val rawTag = obj.optString("tag", "").ifBlank { inferTag(fileName) }
                    items.add(
                        RemoteMediaItem(
                            fileName = fileName,
                            displayName = name,
                            creator = creator,
                            tag = rawTag,
                            thumbUrl = HEADERS_THUMB_URI + fileName,
                            fullUrl = HEADERS_FULL_URI + fileName,
                        )
                    )
                }
                items
            }
        }

    suspend fun loadLocalWallpapers(context: Context): List<LocalWallpaperItem> =
        withContext(Dispatchers.IO) {
            val packageName = context.packageName
            val res = context.resources
            val list = mutableListOf<LocalWallpaperItem>()
            runCatching {
                res.assets.open("wallpapers.xml").use { input ->
                    val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
                        setInput(input, StandardCharsets.UTF_8.name())
                    }
                    var eventType = parser.eventType
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG &&
                            parser.name.equals("wallpaper", ignoreCase = true)
                        ) {
                            val image = parser.getAttributeValue(null, "image")
                            if (!image.isNullOrBlank()) {
                                val resId = res.getIdentifier(image, "drawable", packageName)
                                if (resId != 0) {
                                    val creator = parser.getAttributeValue(null, "creator")
                                        ?.takeIf { it.isNotBlank() && it != "ZZZ" }
                                        ?: ""
                                    val displayName = parser.getAttributeValue(null, "name")
                                        ?.takeIf { it.isNotBlank() }
                                        ?: prettifyAssetName(image)
                                    list.add(
                                        LocalWallpaperItem(
                                            packageName = packageName,
                                            imageName = image,
                                            displayName = displayName,
                                            creator = creator,
                                            resId = resId,
                                        )
                                    )
                                }
                            }
                        }
                        eventType = parser.next()
                    }
                }
            }.onFailure { e ->
                Log.w(TAG, "Failed to parse wallpapers.xml", e)
            }
            list
        }

    suspend fun fetchRemoteWallpapers(filterTag: String? = null): Result<List<RemoteMediaItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = fetchString(WALLPAPER_LIST_URI)
                    ?: error("Failed to fetch remote wallpapers")
                val json = JSONArray(payload)
                val items = ArrayList<RemoteMediaItem>(json.length())
                for (i in 0 until json.length()) {
                    val obj = json.getJSONObject(i)
                    val fileName = obj.optString("filename", "")
                    val dot = fileName.lastIndexOf('.')
                    if (dot == -1) continue
                    val ext = fileName.substring(dot).lowercase(Locale.ROOT)
                    if (ext != ".png" && ext != ".jpg" && ext != ".jpeg" && ext != ".webp") {
                        continue
                    }
                    val tag = obj.optString("tag", "")
                    if (!filterTag.isNullOrBlank() && !tag.equals(filterTag, ignoreCase = true)) {
                        continue
                    }
                    val creator = obj.optString("creator", "")
                        .takeIf { it.isNotBlank() && it != "ZZZ" } ?: ""
                    val name = obj.optString("name", "").ifBlank { prettifyAssetName(fileName) }
                    items.add(
                        RemoteMediaItem(
                            fileName = fileName,
                            displayName = name,
                            creator = creator,
                            tag = tag,
                            thumbUrl = WALLPAPER_THUMB_URI + fileName,
                            fullUrl = WALLPAPER_FULL_URI + fileName,
                        )
                    )
                }
                items
            }
        }

    /**
     * Downloads a remote header into durable app-internal storage (`filesDir/headers`) and
     * returns a [FileProvider] `content://` URI with read access granted to SystemUI so
     * [com.android.systemui.android.header.FileHeaderProvider] can stream it reliably without
     * `file://` cache eviction or cross-process URI exposure issues.
     */
    suspend fun downloadToDurableHeaderUri(
        context: Context,
        url: String,
        fileName: String,
    ): Uri? = withContext(Dispatchers.IO) {
        val headersDir = File(context.filesDir, DURABLE_HEADERS_DIR).apply { mkdirs() }
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            .takeIf { it == "png" || it == "jpg" || it == "jpeg" || it == "webp" }
            ?: "img"
        val target = File(headersDir, "header_${url.hashCode().toUInt()}.$ext")
        val temp = File(headersDir, "header_tmp_${System.nanoTime()}.$ext")

        if (!downloadFile(url, temp)) {
            runCatching { temp.delete() }
            return@withContext null
        }

        if (target.exists()) {
            target.delete()
        }
        val moved = temp.renameTo(target)
        val finalFile = if (moved) {
            target
        } else {
            runCatching {
                temp.copyTo(target, overwrite = true)
                temp.delete()
                target
            }.getOrNull() ?: run {
                runCatching { temp.delete() }
                return@withContext null
            }
        }

        // Prune older downloaded headers so internal storage does not grow unboundedly.
        headersDir.listFiles()?.forEach { existing ->
            if (existing.isFile && existing.name != finalFile.name) {
                runCatching { existing.delete() }
            }
        }

        runCatching {
            val authority = "${context.packageName}$FILE_PROVIDER_SUFFIX"
            val contentUri = FileProvider.getUriForFile(context, authority, finalFile)
            runCatching {
                context.grantUriPermission(
                    SYSTEMUI_PACKAGE,
                    contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            contentUri
        }.onFailure { e ->
            Log.e(TAG, "Failed creating FileProvider URI for ${finalFile.name}", e)
        }.getOrNull()
    }

    suspend fun downloadToCacheFile(
        context: Context,
        url: String,
        prefix: String = "omnistyle_",
    ): File? = withContext(Dispatchers.IO) {
        val dir = context.externalCacheDir ?: context.cacheDir
        val target = File(dir, "${prefix}${url.hashCode().toUInt()}.img")
        if (downloadFile(url, target)) target else null
    }

    suspend fun savePackageDrawableToPictures(
        context: Context,
        packageName: String,
        resId: Int,
        imageName: String,
    ): Uri? = withContext(Dispatchers.IO) {
        if (resId == 0) return@withContext null
        val resolver = context.contentResolver
        val fileName = if (imageName.endsWith(".png", ignoreCase = true) ||
            imageName.endsWith(".jpg", ignoreCase = true) ||
            imageName.endsWith(".jpeg", ignoreCase = true)
        ) {
            imageName
        } else {
            "$imageName.png"
        }
        val isJpeg = fileName.endsWith(".jpg", ignoreCase = true) ||
            fileName.endsWith(".jpeg", ignoreCase = true)
        val mime = if (isJpeg) "image/jpeg" else "image/png"
        val compressFormat = if (isJpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/OmniStyle")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val outUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext null

        val ok = runCatching {
            val res = if (packageName == context.packageName) {
                context.resources
            } else {
                context.packageManager.getResourcesForApplication(packageName)
            }
            val decoded = BitmapFactory.decodeResource(res, resId) ?: run {
                val drawable = res.getDrawable(resId, null) ?: error("Drawable not found")
                val w = drawable.intrinsicWidth.coerceAtLeast(1080)
                val h = drawable.intrinsicHeight.coerceAtLeast(1920)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, w, h)
                drawable.draw(canvas)
                bmp
            }
            try {
                resolver.openOutputStream(outUri, "w")?.use { output ->
                    if (!decoded.compress(compressFormat, 95, output)) {
                        error("Bitmap compress failed")
                    }
                    output.flush()
                } ?: error("Null output stream")
            } finally {
                decoded.recycle()
            }
            val done = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(outUri, done, null, null)
            true
        }.getOrElse { e ->
            Log.w(TAG, "Failed saving local drawable $imageName to Pictures", e)
            runCatching { resolver.delete(outUri, null, null) }
            false
        }

        if (ok) outUri else null
    }

    suspend fun downloadToPictures(
        context: Context,
        url: String,
        fileName: String,
    ): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = when {
            fileName.endsWith(".png", ignoreCase = true) -> "image/png"
            fileName.endsWith(".webp", ignoreCase = true) -> "image/webp"
            else -> "image/jpeg"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/OmniStyle")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val outUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext null

        val ok = runCatching {
            val conn = openConnection(url) ?: error("Connection failed")
            try {
                conn.inputStream.use { input ->
                    resolver.openOutputStream(outUri, "w")?.use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                        output.flush()
                    } ?: error("Null output stream")
                }
            } finally {
                conn.disconnect()
            }
            val done = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(outUri, done, null, null)
            true
        }.getOrElse {
            runCatching { resolver.delete(outUri, null, null) }
            false
        }

        if (ok) outUri else null
    }

    internal fun openConnection(urlStr: String): HttpsURLConnection? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpsURLConnection).apply {
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                requestMethod = "GET"
                doInput = true
                connect()
            }
            if (conn.responseCode == HttpsURLConnection.HTTP_OK) {
                conn
            } else {
                conn.disconnect()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTPS request failed: $urlStr", e)
            null
        }
    }

    private fun fetchString(urlStr: String): String? {
        val conn = openConnection(urlStr) ?: return null
        return try {
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                input.copyTo(out, bufferSize = 16 * 1024)
                out.toString(StandardCharsets.UTF_8.name())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading string from $urlStr", e)
            null
        } finally {
            conn.disconnect()
        }
    }

    internal fun downloadFile(urlStr: String, target: File): Boolean {
        if (target.exists()) {
            target.delete()
        }
        val conn = openConnection(urlStr) ?: return false
        return try {
            conn.inputStream.use { input ->
                FileOutputStream(target, false).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                    output.flush()
                }
            }
            target.exists() && target.length() > 0L
        } catch (e: Exception) {
            Log.w(TAG, "Failed downloading $urlStr", e)
            runCatching { target.delete() }
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun inferTag(fileName: String): String {
        val letters = fileName.takeWhile { it.isLetter() }
        return letters.ifBlank { "other" }.lowercase(Locale.ROOT)
    }

    private fun prettifyAssetName(raw: String): String {
        val base = raw.substringBeforeLast('.')
        return base
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
            }
    }
}
