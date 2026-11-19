package ru.kai.bb

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Оболочка вокруг WebView: настройки, навигация, загрузка файлов, разрешения на камеру/микрофон.
 * Всё состояние страницы живёт в WebView, поэтому он создаётся один раз и переживает повороты.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebBridge(val context: Context, val prefs: SitePrefs) {

    val web: WebView = WebView(context)

    var onProgress: (Int) -> Unit = {}
    var onNav: (canBack: Boolean, canForward: Boolean, url: String, title: String) -> Unit =
        { _, _, _, _ -> }
    var onPageError: (String?) -> Unit = { _ -> }
    var onToast: (String) -> Unit = {}
    var onDownload: (DownloadRequest) -> Unit = {}
    var onFileChooser: ((ValueCallback<Array<Uri>>?, Boolean, Array<String>) -> Unit)? = null
    var onWebPermission: ((PermissionRequest) -> Unit)? = null

    private var pdfHandledFor: String? = null
    private var lastTitle: String = ""

    init {
        setup()
        Companion.current = this
    }

    private fun setup() {
        web.setBackgroundColor(Color.WHITE)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = prefs.textZoom
            userAgentString = prefs.userAgent(context)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                onToast("Этот файл сайт отдаёт только внутри страницы")
            } else {
                onDownload(
                    DownloadRequest(
                        url = url,
                        userAgent = userAgent,
                        contentDisposition = contentDisposition,
                        mimeType = mimeType,
                        referer = web.url,
                    ),
                )
            }
        }

        web.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = handleUrl(request.url)

            @Deprecated("старый API нужен для Android 5–6")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                url != null && handleUrl(Uri.parse(url))

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                onProgress(0)
                sync()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                sync()
                if (!url.isNullOrBlank() && Site.isOwnHost(url)) prefs.lastUrl = url
                checkPdf(view, url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                sync()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    onPageError(error?.description?.toString().orEmpty())
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?,
            ) {
                handler?.cancel()
                onPageError("Сертификат сайта не принят")
            }
        }

        web.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgress(newProgress)
                sync()
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                lastTitle = title.orEmpty()
                sync()
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                val handler = onFileChooser
                if (handler == null) {
                    filePathCallback?.onReceiveValue(null)
                    return false
                }
                val multiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                handler(filePathCallback, multiple, fileChooserParams?.acceptTypes ?: emptyArray())
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val handler = onWebPermission
                if (handler == null) request.deny() else handler(request)
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback?,
            ) {
                callback?.invoke(origin, false, false)
            }
        }
    }

    /** Ссылки наружу (почта, телефон, приложения) отдаём системе; http(s) держим внутри. */
    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase().orEmpty()
        return when (scheme) {
            "http", "https" -> false
            "about", "javascript", "data", "blob" -> false
            else -> {
                val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val ok = runCatching { context.startActivity(intent) }.isSuccess
                if (!ok) onToast("Нет приложения для этой ссылки")
                true
            }
        }
    }

    /**
     * PDF WebView не рисует: страница «белая», а пользователь думает, что приложение сломалось.
     * Определяем тип документа и сразу скачиваем файл.
     */
    private fun checkPdf(view: WebView?, url: String?) {
        if (view == null || url == null || pdfHandledFor == url) return
        view.evaluateJavascript(
            "(function(){try{return document.contentType||''}catch(e){return ''}})()",
        ) { raw ->
            val contentType = raw?.trim()?.trim('"').orEmpty()
            if (contentType.contains("application/pdf", ignoreCase = true)) {
                pdfHandledFor = url
                onDownload(
                    DownloadRequest(
                        url = url,
                        userAgent = view.settings.userAgentString,
                        mimeType = "application/pdf",
                        referer = view.url,
                    ),
                )
                onToast("PDF скачивается — откроется в просмотрщике")
            }
        }
    }

    private fun sync() {
        val title = if (lastTitle.isNotBlank()) lastTitle else "Blackboard"
        onNav(web.canGoBack(), web.canGoForward(), web.url.orEmpty(), title)
    }

    fun load(url: String) {
        onPageError(null)
        web.loadUrl(url)
    }

    fun reload() {
        onPageError(null)
        web.reload()
    }

    fun goBack(): Boolean = if (web.canGoBack()) {
        web.goBack(); true
    } else false

    fun goForward(): Boolean = if (web.canGoForward()) {
        web.goForward(); true
    } else false

    fun applyTextZoom(value: Int) {
        prefs.textZoom = value
        web.settings.textZoom = prefs.textZoom
    }

    fun applyViewMode(desktop: Boolean) {
        prefs.desktopMode = desktop
        web.settings.userAgentString = prefs.userAgent(context)
        web.reload()
    }

    /** Выход: чистим куки и локальное хранилище сайта, возвращаемся на форму входа. */
    fun logout() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        runCatching { WebStorage.getInstance().deleteAllData() }
        runCatching { web.clearCache(true) }
        prefs.lastUrl = null
        load(Site.ENTRY_URL)
    }

    fun openInBrowser() {
        val url = web.url ?: Site.HOME_URL
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isFailure) onToast("Не нашлось браузера")
    }

    fun flushSession() {
        runCatching { CookieManager.getInstance().flush() }
    }

    companion object {
        @Volatile
        var current: WebBridge? = null
            private set
    }
}
