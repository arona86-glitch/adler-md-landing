package il.org.hatzolahair.crm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/**
 * Everything the app writes (documents, camera photos) lives in its private cache — patient
 * documents never land in the shared Downloads folder. The cache is wiped on every launch.
 */
object FileTransfer {

    class Downloaded(val file: File, val mimeType: String)

    fun downloadsDir(context: Context): File = File(context.cacheDir, "downloads").apply { mkdirs() }

    fun uploadsDir(context: Context): File = File(context.cacheDir, "uploads").apply { mkdirs() }

    fun wipe(context: Context) {
        File(context.cacheDir, "downloads").deleteRecursively()
        File(context.cacheDir, "uploads").deleteRecursively()
    }

    fun safeName(raw: String): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.')
        return cleaned.ifBlank { "document" }.take(120)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        if (!file.exists()) return file
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (file.exists()) file = File(dir, "$base ($i)$ext").also { i++ }
        return file
    }

    private fun mimeFromName(name: String, fallback: String): String {
        if (fallback != "application/octet-stream" && fallback.isNotBlank()) return fallback
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: fallback
    }

    /** Blocking — call from a background thread. Returns null on any failure. */
    fun download(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ): Downloaded? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
                if (!userAgent.isNullOrBlank()) setRequestProperty("User-Agent", userAgent)
            }
            if (conn.responseCode !in 200..299) return null
            val headerMime = conn.contentType?.substringBefore(';')?.trim()
            val mime = if (!headerMime.isNullOrBlank()) headerMime else (mimeType ?: "application/octet-stream")
            val disposition = conn.getHeaderField("Content-Disposition") ?: contentDisposition
            val name = safeName(URLUtil.guessFileName(url, disposition, mime))
            val out = uniqueFile(downloadsDir(context), name)
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            Downloaded(out, mimeFromName(out.name, mime))
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Used for blob: downloads, which the page hands over as base64 through the JS bridge. */
    fun saveBase64(context: Context, base64: String, mimeType: String, suggestedName: String): Downloaded? {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            var name = safeName(suggestedName)
            val mime = mimeType.ifBlank { mimeFromName(name, "application/octet-stream") }
            if (!name.contains('.')) {
                MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { name = "$name.$it" }
            }
            val out = uniqueFile(downloadsDir(context), name)
            out.writeBytes(bytes)
            Downloaded(out, mimeFromName(out.name, mime))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Shrinks a camera photo in place (keeping its orientation) so it fits the CRM's upload
     * limits. Best effort: on any failure the original file is left untouched.
     */
    fun downscaleJpeg(file: File, maxEdge: Int) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val longest = max(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return
            if (longest <= maxEdge && file.length() < 1_500_000) return

            var sample = 1
            while (longest / (sample * 2) >= maxEdge) sample *= 2
            val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return

            val matrix = Matrix()
            val scale = maxEdge.toFloat() / max(bitmap.width, bitmap.height)
            if (scale < 1f) matrix.postScale(scale, scale)
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }
            val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            file.outputStream().use { result.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (result !== bitmap) bitmap.recycle()
            result.recycle()
        } catch (_: Throwable) {
            // keep the original photo
        }
    }
}
