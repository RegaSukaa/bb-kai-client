package ru.kai.bb

import android.content.Context
import android.webkit.WebSettings

/** Всё, что знает приложение о сайте. */
object Site {
    const val ENTRY_URL = "https://bb.kai.ru/webapps/login/"
    const val HOME_URL = "https://bb.kai.ru/"
    const val HOST = "bb.kai.ru"

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Safari/537.36"

    const val FALLBACK_MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Mobile Safari/537.36"

    /** Внутри приложения держим только портал вуза; всё остальное — в браузер. */
    fun isOwnHost(url: String?): Boolean {
        val host = runCatching { java.net.URI(url ?: return false).host }.getOrNull() ?: return false
        return host == HOST || host.endsWith(".$HOST")
    }
}

/** Чистые правила, которые легко проверить юнит-тестом. */
object SiteRules {
    const val MIN_ZOOM = 80
    const val MAX_ZOOM = 160

    fun clampZoom(value: Int): Int = value.coerceIn(MIN_ZOOM, MAX_ZOOM)

    fun userAgent(desktop: Boolean, mobileUa: String): String =
        if (desktop) Site.DESKTOP_UA else mobileUa

    /** Стартовый адрес: возвращаемся туда, где пользователь остановился. */
    fun startUrl(lastUrl: String?): String =
        if (!lastUrl.isNullOrBlank() && Site.isOwnHost(lastUrl)) lastUrl else Site.ENTRY_URL
}

/**
 * Системный UA содержит «; wv» и «Version/4.0» — по этим меткам часть сайтов (и Google-логин)
 * считает клиента встроенным и отказывает. Чистим, чтобы сайт видел обычный Chrome на Android.
 */
fun mobileUserAgent(context: Context): String = runCatching {
    WebSettings.getDefaultUserAgent(context)
        .replace("; wv", "")
        .replace("Version/4.0 ", "")
}.getOrDefault(Site.FALLBACK_MOBILE_UA)

class SitePrefs(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("bb_app", Context.MODE_PRIVATE)

    var desktopMode: Boolean
        get() = prefs.getBoolean("desktop_mode", false)
        set(value) = prefs.edit().putBoolean("desktop_mode", value).apply()

    var textZoom: Int
        get() = SiteRules.clampZoom(prefs.getInt("text_zoom", 100))
        set(value) = prefs.edit().putInt("text_zoom", SiteRules.clampZoom(value)).apply()

    /** Последняя открытая страница — чтобы после выгрузки приложения вернуться туда же. */
    var lastUrl: String?
        get() = prefs.getString("last_url", null)
        set(value) = prefs.edit().putString("last_url", value).apply()

    fun userAgent(context: Context): String =
        SiteRules.userAgent(desktopMode, mobileUserAgent(context))
}
