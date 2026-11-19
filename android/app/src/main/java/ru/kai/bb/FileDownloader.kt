package ru.kai.bb

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Что именно просит скачать сайт. */
data class DownloadRequest(
    val url: String,
    val userAgent: String? = null,
    val contentDisposition: String? = null,
    val mimeType: String? = null,
    val referer: String? = null,
)

data class SavedFile(val uri: Uri, val name: String, val mime: String)

/**
 * Скачивание своим клиентом, а не DownloadManager: портал отдаёт файлы из bbcswebdav только
 * с cookie сессии, а DownloadManager в части прошивок выбрасывает заголовок Cookie — файл
 * приходит пустым или 401. Здесь cookie берём из WebView и стримим в «Загрузки/Blackboard».
 */
object FileDownloader {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun download(context: Context, req: DownloadRequest): SavedFile =
        withContext(Dispatchers.IO) {
            val cookies = runCatching { CookieManager.getInstance().getCookie(req.url) }.getOrNull()
            val builder = Request.Builder().url(req.url)
                .header("User-Agent", req.userAgent?.takeIf { it.isNotBlank() } ?: Site.FALLBACK_MOBILE_UA)
            if (!cookies.isNullOrBlank()) builder.header("Cookie", cookies)
            req.referer?.takeIf { it.isNotBlank() }?.let { builder.header("Referer", it) }

            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val cd = response.header("Content-Disposition") ?: req.contentDisposition
                val mime = (response.header("Content-Type") ?: req.mimeType ?: "application/octet-stream")
                    .substringBefore(';').trim().ifBlank { "application/octet-stream" }
                val name = DownloadNames.guess(response.request.url.toString(), cd, mime)

                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/Blackboard",
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("нет доступа к папке «Загрузки»")
                try {
                    val body = response.body ?: throw IOException("пустой ответ")
                    resolver.openOutputStream(uri)?.use { out ->
                        body.byteStream().copyTo(out, 64 * 1024)
                    } ?: throw IOException("не удалось записать файл")
                    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                } catch (t: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw t
                }
                SavedFile(uri, name, mime)
            }
        }

    /** Открыть скачанный файл тем приложением, которое умеет этот тип. */
    fun open(context: Context, file: SavedFile): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(file.uri, file.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
