package ru.kai.bb

import java.net.URLDecoder

/**
 * Имя файла для скачивания: заголовок Content-Disposition врёт реже, чем URL,
 * но встречается и в виде `filename*=UTF-8''...`, и в виде обычного `filename="..."`.
 */
object DownloadNames {

    private val EXT_BY_MIME = mapOf(
        "application/pdf" to "pdf",
        "application/zip" to "zip",
        "application/x-rar-compressed" to "rar",
        "text/plain" to "txt",
        "text/csv" to "csv",
        "application/msword" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.ms-powerpoint" to "ppt",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/gif" to "gif",
        "video/mp4" to "mp4",
        "audio/mpeg" to "mp3",
        "audio/mp4" to "m4a",
    )

    fun extensionFor(mime: String?): String? {
        val clean = mime?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return EXT_BY_MIME[clean]
    }

    fun guess(url: String, contentDisposition: String?, mime: String?): String {
        val fromHeader = contentDisposition?.let { fromDisposition(it) }
        val fromUrl = fromUrlPath(url)
        val base = (fromHeader ?: fromUrl)?.let { sanitize(it) }
        val ext = extensionFor(mime) ?: (fromHeader ?: fromUrl)?.substringAfterLast('.', "")?.takeIf {
            it.isNotEmpty() && it.length <= 5 && it.all { ch -> ch.isLetterOrDigit() }
        }
        val name = base ?: "file"
        return if (ext != null && !name.lowercase().endsWith(".$ext")) "$name.$ext" else name
    }

    fun fromDisposition(value: String): String? {
        // filename*=UTF-8''%D0%9B%D0%B5%D0%BA%D1%86%D0%B8%D1%8F.pdf
        val star = Regex("""filename\*\s*=\s*([^';]+)''([^;]+)""", RegexOption.IGNORE_CASE)
            .find(value)
        if (star != null) {
            val charset = star.groupValues[1].trim()
            val raw = star.groupValues[2].trim().trim('"')
            return runCatching { URLDecoder.decode(raw, charset.ifBlank { "UTF-8" }) }
                .getOrElse { percentDecode(raw) }
        }
        val plain = Regex("""filename\s*=\s*"([^"]*)\"""", RegexOption.IGNORE_CASE).find(value)
        if (plain != null) return percentDecode(plain.groupValues[1])
        val bare = Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(value)
        return bare?.groupValues?.get(1)?.trim()?.trim('"')?.let { percentDecode(it) }
    }

    fun fromUrlPath(url: String): String? {
        val withoutQuery = url.substringBefore('#').substringBefore('?')
        val afterScheme = if ("://" in withoutQuery) withoutQuery.substringAfter("://") else withoutQuery
        val slash = afterScheme.indexOf('/')
        if (slash < 0 || slash == afterScheme.length - 1) return null
        val path = afterScheme.substring(slash + 1)
        val last = if ('/' in path) path.substringAfterLast('/') else path
        if (last.isBlank()) return null
        return percentDecode(last)
    }

    /** Проценты декодируем в байты и только потом в UTF-8 — иначе кириллица ломается в мохнатый мусор. */
    fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val bytes = java.io.ByteArrayOutputStream()
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '%' && i + 2 < value.length) {
                val code = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    bytes.write(code)
                    i += 3
                    continue
                }
            }
            if (bytes.size() > 0) {
                out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                bytes.reset()
            }
            out.append(ch)
            i++
        }
        if (bytes.size() > 0) out.append(String(bytes.toByteArray(), Charsets.UTF_8))
        return out.toString()
    }

    fun sanitize(name: String): String = name
        .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
        .trim()
        .take(120)
        .ifBlank { "file" }
}
