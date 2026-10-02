package il.org.hatzolahair.crm

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import il.org.hatzolahair.crm.databinding.ActivityMainBinding
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val io = Executors.newSingleThreadExecutor()
    private val startedAt = SystemClock.uptimeMillis()

    // ---- page / chrome state
    private var firstContentShown = false
    private var mainFrameFailed = false
    private var wantBottomNav = false
    private var systemBarsInset = androidx.core.graphics.Insets.NONE
    private var imeBottom = 0

    // ---- uploads (file chooser + camera)
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var chooserParams: WebChromeClient.FileChooserParams? = null
    private var cameraFile: File? = null
    private var permissionContinuation: ((Boolean) -> Unit)? = null

    // ---- app lock
    private var locked = false
    private var authenticating = false
    private var backgroundedAt = 0L
    private var leftForExternalTask = false

    @Volatile
    private var awaitingBlob = false

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val fileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileCallback ?: return@registerForActivityResult
            fileCallback = null
            val capture = cameraFile
            cameraFile = null

            var uris: Array<Uri>? = null
            if (result.resultCode == RESULT_OK) {
                uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                if (uris.isNullOrEmpty() && capture != null && capture.length() > 0) {
                    // The camera app wrote straight to our file: shrink it, then hand it over.
                    io.execute {
                        FileTransfer.downscaleJpeg(capture, Config.CAPTURE_MAX_EDGE)
                        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", capture)
                        runOnUiThread { callback.onReceiveValue(arrayOf(uri)) }
                    }
                    return@registerForActivityResult
                }
            }
            callback.onReceiveValue(uris)
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val next = permissionContinuation
            permissionContinuation = null
            next?.invoke(granted)
        }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition {
            !firstContentShown && SystemClock.uptimeMillis() - startedAt < 3_000
        }

        prefs = Prefs(this)
        applySecureFlag()
        FileTransfer.wipe(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        WindowCompat.getInsetsController(window, b.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }

        setUpInsets()
        setUpWebView()
        setUpChrome()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (b.webView.canGoBack() && !locked) {
                    b.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        locked = prefs.biometricLock
        b.lockView.isVisible = locked

        if (savedInstanceState != null) {
            b.webView.restoreState(savedInstanceState)
        } else {
            openFromIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        b.webView.saveState(outState)
    }

    override fun onStart() {
        super.onStart()
        if (prefs.biometricLock && !locked && backgroundedAt != 0L && !leftForExternalTask &&
            SystemClock.elapsedRealtime() - backgroundedAt > Config.LOCK_GRACE_MS
        ) {
            locked = true
            b.lockView.isVisible = true
        }
        leftForExternalTask = false
        if (locked) authenticate()
        registerNetworkCallback()
    }

    override fun onResume() {
        super.onResume()
        b.webView.onResume()
        applySecureFlag()
    }

    override fun onPause() {
        b.webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onStop() {
        backgroundedAt = SystemClock.elapsedRealtime()
        unregisterNetworkCallback()
        super.onStop()
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        io.shutdown()
        b.webView.apply {
            stopLoading()
            webChromeClient = null
            removeJavascriptInterface(BRIDGE_NAME)
            (parent as? android.view.ViewGroup)?.removeView(this)
            destroy()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ window insets

    private fun setUpInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            systemBarsInset = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            applyInsets()
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun applyInsets() {
        val bottom = max(systemBarsInset.bottom, imeBottom)
        b.root.setPadding(systemBarsInset.left, systemBarsInset.top, systemBarsInset.right, 0)
        b.bottomBar.setPadding(0, 0, 0, bottom)
        // With the keyboard open the tab bar only steals space.
        b.bottomNav.isVisible = wantBottomNav && imeBottom == 0
        b.bottomBar.isVisible = b.bottomNav.isVisible || bottom > 0
    }

    private fun applySecureFlag() {
        if (prefs.secureScreen) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // ------------------------------------------------------------------ native chrome

    private fun setUpChrome() {
        b.swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.hai_blue))
        b.swipeRefresh.setOnRefreshListener { reloadPage() }

        b.retryButton.setOnClickListener { reloadPage() }
        b.unlockButton.setOnClickListener { authenticate() }

        b.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> openPath("/dashboard")
                R.id.nav_cases -> openPath("/cases")
                R.id.nav_inquiries -> openPath("/inquiries")
                R.id.nav_intake -> openPath("/intake")
                R.id.nav_more -> {
                    showMore()
                    return@setOnItemSelectedListener false
                }
                else -> Unit
            }
            true
        }
        b.bottomNav.setOnItemReselectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> openPath("/dashboard")
                R.id.nav_cases -> openPath("/cases")
                R.id.nav_inquiries -> openPath("/inquiries")
                R.id.nav_intake -> openPath("/intake")
                R.id.nav_more -> showMore()
                else -> Unit
            }
        }
    }

    /** Keeps the tab bar in step with the page, including Next.js client-side navigations. */
    private fun syncChrome(url: String?) {
        val uri = url?.let { Uri.parse(it) }
        val path = uri?.takeIf { Config.isCrmHost(it) }?.path.orEmpty()
        val item = if (uri != null && Config.isCrmHost(uri)) itemForPath(path) else null
        wantBottomNav = item != null
        if (item != null) b.bottomNav.menu.findItem(item)?.isChecked = true
        applyInsets()
    }

    private fun itemForPath(path: String): Int? = when {
        path.startsWith("/dashboard") -> R.id.nav_dashboard
        path.startsWith("/cases") -> R.id.nav_cases
        path.startsWith("/inquiries") -> R.id.nav_inquiries
        path.startsWith("/intake") -> R.id.nav_intake
        path == "/logistics" || path.startsWith("/logistics/cases") || path.startsWith("/logistics/equipment") ||
            path.startsWith("/staff") || path.startsWith("/equipment") ||
            path.startsWith("/flight-day-bookings") || path.startsWith("/tools") ||
            path.startsWith("/settings") || path.startsWith("/admin") -> R.id.nav_more
        else -> null
    }

    private fun showMore() {
        MoreSheet(this, prefs, object : MoreSheet.Actions {
            override fun openPath(path: String) = this@MainActivity.openPath(path)
            override fun reload() = reloadPage()
            override fun openInBrowser() = openExternally(Uri.parse(currentUrl()))
            override fun copyLink() {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("link", currentUrl()))
                Toast.makeText(this@MainActivity, R.string.copied, Toast.LENGTH_SHORT).show()
            }

            override fun shareLink() {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, currentUrl())
                    putExtra(Intent.EXTRA_SUBJECT, b.webView.title.orEmpty())
                }
                leftForExternalTask = true
                startActivity(Intent.createChooser(send, getString(R.string.chooser_share)))
            }

            override fun signOut() = confirmSignOut()

            override fun setBiometricLock(enabled: Boolean): Boolean {
                if (enabled && !canAuthenticate()) {
                    Toast.makeText(this@MainActivity, R.string.biometric_unavailable, Toast.LENGTH_LONG).show()
                    return false
                }
                prefs.biometricLock = enabled
                return true
            }

            override fun setSecureScreen(enabled: Boolean) {
                prefs.secureScreen = enabled
                applySecureFlag()
            }
        }).show()
    }

    private fun confirmSignOut() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.more_sign_out)
            .setMessage(R.string.sign_out_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.more_sign_out) { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                b.webView.clearCache(true)
                b.webView.clearHistory()
                FileTransfer.wipe(this)
                openPath(Config.START_PATH)
            }
            .show()
    }

    // ------------------------------------------------------------------ navigation helpers

    private fun currentUrl(): String = b.webView.url ?: (Config.BASE_URL + Config.START_PATH)

    fun openPath(path: String) {
        b.webView.loadUrl(Config.BASE_URL + path)
    }

    private fun reloadPage() {
        b.errorView.isVisible = false
        if (b.webView.url.isNullOrBlank()) openPath(Config.START_PATH) else b.webView.reload()
    }

    private fun openFromIntent(intent: Intent?) {
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data != null && Config.isInternal(data)) {
            b.webView.loadUrl(data.toString())
        } else if (b.webView.url.isNullOrBlank()) {
            openPath(Config.START_PATH)
        }
    }

    private fun openExternally(uri: Uri) {
        leftForExternalTask = true
        try {
            val tabs = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setDefaultColorSchemeParams(
                    CustomTabColorSchemeParams.Builder()
                        .setToolbarColor(ContextCompat.getColor(this, R.color.hai_bar))
                        .build(),
                )
                .build()
            tabs.launchUrl(this, uri)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openSystemScheme(uri: Uri) {
        leftForExternalTask = true
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    /** Decides where a link goes. Returns true when the app handled it (WebView must not load it). */
    private fun routeLink(uri: Uri, mainFrame: Boolean): Boolean {
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                if (Config.isInternal(uri)) {
                    false
                } else {
                    if (mainFrame) openExternally(uri)
                    mainFrame
                }
            }
            "blob", "about", "data", "javascript" -> false
            "tel", "mailto", "sms", "smsto", "geo", "whatsapp", "tg" -> {
                openSystemScheme(uri)
                true
            }
            else -> true // unknown schemes (intent:, file:, ...) are never opened
        }
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val web = b.webView
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // Google's sign-in refuses embedded WebViews; dropping the "wv" marker keeps it working.
            userAgentString = userAgentString.replace("; wv", "") + " HatzolahAirApp/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookie(web, true)
        }
        web.addJavascriptInterface(DownloadBridge(), BRIDGE_NAME)
        web.setBackgroundColor(ContextCompat.getColor(this, R.color.hai_surface))
        web.webViewClient = CrmWebViewClient()
        web.webChromeClient = CrmChromeClient()
        web.setDownloadListener { url, userAgent, disposition, mime, _ ->
            startDownload(url, userAgent, disposition, mime)
        }
    }

    private inner class CrmWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            routeLink(request.url, request.isForMainFrame)

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            mainFrameFailed = false
            syncChrome(url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            b.swipeRefresh.isRefreshing = false
            b.progress.isVisible = false
            if (!mainFrameFailed) b.errorView.isVisible = false
            firstContentShown = true
            syncChrome(url)
            CookieManager.getInstance().flush()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            syncChrome(url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) showError(offline = !isOnline())
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && response.statusCode in 502..504) showError(offline = false)
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            // The page process died (usually memory). Rebuild the screen rather than crash.
            recreate()
            return true
        }
    }

    private inner class CrmChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            b.progress.isVisible = newProgress in 1..99
            b.progress.setProgressCompat(newProgress, true)
        }

        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            chooserParams = params

            val acceptsImages = params.acceptTypes.isEmpty() ||
                params.acceptTypes.any { it.isBlank() || it.startsWith("image") || it == "*/*" }
            val cameraAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
            val wantsCamera = acceptsImages && cameraAvailable

            if (wantsCamera && !hasPermission(Manifest.permission.CAMERA)) {
                permissionContinuation = { granted ->
                    if (!granted) {
                        Toast.makeText(this@MainActivity, R.string.camera_permission_needed, Toast.LENGTH_SHORT).show()
                    }
                    launchFileChooser(withCamera = granted)
                }
                permissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                launchFileChooser(withCamera = wantsCamera)
            }
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                val origin = request.origin
                val allowedOrigin = origin.scheme == "https" && origin.host == Config.HOST
                val wantsCamera = PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources
                val onlyVideo = request.resources.all { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
                if (!allowedOrigin || !wantsCamera || !onlyVideo) {
                    request.deny()
                    return@runOnUiThread
                }
                if (hasPermission(Manifest.permission.CAMERA)) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                } else {
                    permissionContinuation = { granted ->
                        if (granted) request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else request.deny()
                    }
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            // window.open / target="_blank": catch the target URL in a throw-away WebView and
            // route it ourselves (same tab for CRM pages, Custom Tab for everything else).
            val temp = WebView(view.context)
            var routed = false
            fun route(url: String?) {
                if (routed || url.isNullOrBlank() || url == "about:blank") return
                routed = true
                val uri = Uri.parse(url)
                if (uri.scheme == "blob") {
                    startDownload(url, null, null, null)
                } else if (!routeLink(uri, mainFrame = true)) {
                    b.webView.loadUrl(url)
                }
                temp.post { temp.destroy() }
            }
            temp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    route(request.url.toString())
                    return true
                }

                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    route(url)
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = temp
            resultMsg.sendToTarget()
            return true
        }
    }

    // ------------------------------------------------------------------ errors / connectivity

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showError(offline: Boolean) {
        mainFrameFailed = true
        b.swipeRefresh.isRefreshing = false
        b.progress.isVisible = false
        b.errorIcon.setImageResource(if (offline) R.drawable.ic_cloud_off else R.drawable.ic_refresh)
        b.errorTitle.setText(if (offline) R.string.offline_title else R.string.error_title)
        b.errorBody.setText(if (offline) R.string.offline_body else R.string.error_body)
        b.errorView.isVisible = true
        firstContentShown = true
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Back online: recover on its own if the offline screen is up.
                runOnUiThread { if (b.errorView.isVisible) reloadPage() }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: Exception) {
            // best effort
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(callback)
        } catch (_: Exception) {
            // already gone
        }
    }

    // ------------------------------------------------------------------ uploads

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun launchFileChooser(withCamera: Boolean) {
        val params = chooserParams
        if (fileCallback == null || params == null) return

        val initial = mutableListOf<Intent>()
        cameraFile = null
        if (withCamera && hasPermission(Manifest.permission.CAMERA)) {
            try {
                val photo = File(FileTransfer.uploadsDir(this), "photo_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photo)
                val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    clipData = ClipData.newRawUri("photo", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                if (capture.resolveActivity(packageManager) != null) {
                    initial += capture
                    cameraFile = photo
                }
            } catch (_: Exception) {
                // fall back to the file picker alone
            }
        }

        val chooser = Intent.createChooser(params.createIntent(), getString(R.string.chooser_file)).apply {
            if (initial.isNotEmpty()) putExtra(Intent.EXTRA_INITIAL_INTENTS, initial.toTypedArray())
        }
        leftForExternalTask = true
        try {
            fileLauncher.launch(chooser)
        } catch (_: ActivityNotFoundException) {
            fileCallback?.onReceiveValue(null)
            fileCallback = null
        }
    }

    // ------------------------------------------------------------------ downloads

    private fun startDownload(url: String, userAgent: String?, disposition: String?, mime: String?) {
        Toast.makeText(this, R.string.downloading, Toast.LENGTH_SHORT).show()
        if (url.startsWith("blob:")) {
            // A blob only exists inside the page, so ask the page to hand its bytes over.
            val name = android.webkit.URLUtil.guessFileName(url, disposition, mime)
            awaitingBlob = true
            val js = """
                (function(){
                  var x = new XMLHttpRequest();
                  x.open('GET', ${quoteJs(url)}, true);
                  x.responseType = 'blob';
                  x.onload = function(){
                    var r = new FileReader();
                    r.onloadend = function(){
                      var s = String(r.result || '');
                      $BRIDGE_NAME.save(s.substring(s.indexOf(',') + 1), x.response.type || '', ${quoteJs(name)});
                    };
                    r.readAsDataURL(x.response);
                  };
                  x.onerror = function(){ $BRIDGE_NAME.fail(); };
                  x.send();
                })();
            """.trimIndent()
            b.webView.evaluateJavascript(js, null)
            return
        }
        if (!url.startsWith("https:")) {
            onDownloadFailed()
            return
        }
        io.execute {
            val result = FileTransfer.download(this, url, userAgent ?: b.webView.settings.userAgentString, disposition, mime)
            runOnUiThread {
                if (result != null) openDownloaded(result) else onDownloadFailed()
            }
        }
    }

    private fun quoteJs(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ") + "'"

    private fun onDownloadFailed() {
        Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show()
    }

    private fun openDownloaded(result: FileTransfer.Downloaded) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", result.file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, result.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        leftForExternalTask = true
        try {
            startActivity(Intent.createChooser(view, result.file.name))
        } catch (_: ActivityNotFoundException) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = result.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(send, result.file.name))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.no_app_for_file, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Receives blob downloads from the page. Only accepts data we just asked for. */
    inner class DownloadBridge {
        @JavascriptInterface
        fun save(base64: String, mimeType: String, name: String) {
            if (!awaitingBlob) return
            awaitingBlob = false
            io.execute {
                val result = FileTransfer.saveBase64(this@MainActivity, base64, mimeType, name)
                runOnUiThread {
                    if (result != null) openDownloaded(result) else onDownloadFailed()
                }
            }
        }

        @JavascriptInterface
        fun fail() {
            if (!awaitingBlob) return
            awaitingBlob = false
            runOnUiThread { onDownloadFailed() }
        }
    }

    // ------------------------------------------------------------------ biometric lock

    private fun canAuthenticate(): Boolean =
        BiometricManager.from(this).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    private fun authenticate() {
        if (authenticating) return
        if (!canAuthenticate()) {
            // The device lost its screen lock; locking would trap the user out.
            unlock()
            return
        }
        authenticating = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authenticating = false
                    unlock()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authenticating = false // stays locked; the Unlock button tries again
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.unlock_prompt_title))
                .setSubtitle(getString(R.string.unlock_prompt_subtitle))
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build(),
        )
    }

    private fun unlock() {
        locked = false
        b.lockView.isVisible = false
    }

    private companion object {
        const val BRIDGE_NAME = "HaiAndroid"
        const val AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
