package il.org.hatzolahair.crm

import android.net.Uri

object Config {
    const val HOST = "app.hatzolahair.org.il"
    const val BASE_URL = "https://$HOST"
    const val START_PATH = "/dashboard"

    /** How long the app may sit in the background before the unlock screen comes back. */
    const val LOCK_GRACE_MS = 30_000L

    /** Longest edge for photos taken with the in-app camera flow, so uploads stay small. */
    const val CAPTURE_MAX_EDGE = 2200

    /**
     * Pages that stay inside the app's WebView: the CRM itself plus the sign-in providers it uses.
     * Everything else (Drive links, the public website, ...) opens in a Custom Tab.
     */
    fun isInternal(uri: Uri): Boolean {
        if (uri.scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return host == HOST ||
            host == "accounts.google.com" ||
            host.endsWith(".clerk.accounts.dev") ||
            host == "clerk.hatzolahair.org.il" ||
            host == "accounts.hatzolahair.org.il"
    }

    fun isCrmHost(uri: Uri): Boolean = uri.host?.lowercase() == HOST
}
