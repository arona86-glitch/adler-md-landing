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

    /** Sign-in providers whose pages must stay in the WebView so the login cookies/state survive. */
    private val authSuffixes = listOf(
        ".accounts.dev", // Clerk development instances: <name>.accounts.dev and <name>.clerk.accounts.dev
        ".clerk.com",
        ".clerk.dev",
        ".google.com", // accounts.google.com and its helpers
        ".gstatic.com",
        ".googleusercontent.com",
        ".youtube.com", // accounts.youtube.com is part of Google's sign-in cookie sync
        ".apple.com",
        ".microsoftonline.com",
        ".live.com",
    )

    /**
     * Pages that stay inside the app's WebView: the CRM itself plus the whole sign-in chain
     * (Clerk -> Google/Apple/Microsoft -> back). Everything else (Drive links, the public website,
     * ...) opens in a Custom Tab.
     */
    fun isInternal(uri: Uri): Boolean {
        if (uri.scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        if (host == HOST) return true
        if (host.endsWith(".hatzolahair.org.il") && host != "www.hatzolahair.org.il") return true
        return authSuffixes.any { host.endsWith(it) }
    }

    fun isCrmHost(uri: Uri): Boolean = uri.host?.lowercase() == HOST
}
