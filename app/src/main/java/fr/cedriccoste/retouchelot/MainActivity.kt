package fr.cedriccoste.retouchelot

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.Size
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Halo — l'interface (éditeur) est une page web locale (assets/index.html)
 * affichée dans une WebView. Ce fichier fait le lien avec Android :
 *  - liste des photos du téléphone (MediaStore)
 *  - lecture des photos et vignettes pour l'éditeur
 *  - demande d'autorisation de modifier les photos (une seule confirmation pour le lot)
 *  - écriture des photos retouchées (remplacement ou copie) en recopiant date, lieu GPS, etc.
 *  - sauvegarde des originales 30 jours + restauration
 *  - mises à jour automatiques depuis GitHub (cedriccoste/halo, dernière « Release »)
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private val backupDays = 30L

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            js("window.onPermissions && window.onPermissions(${hasMediaPermission()})")
        }

    private val writeLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            js("window.onWriteResult && window.onWriteResult(${res.resultCode == Activity.RESULT_OK})")
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread { cleanupBackups() }.start()

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/media/", MediaHandler())
            .build()

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.appBack && window.appBack()) ? 'y' : 'n'") { r ->
                    if (r == null || !r.contains("y")) finish()
                }
            }
        })
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    // ---------------------------------------------------------------- autorisations

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasMediaPermission(): Boolean = when {
        Build.VERSION.SDK_INT >= 34 ->
            granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun permissionsToRequest(): Array<String> {
        val l = mutableListOf<String>()
        when {
            Build.VERSION.SDK_INT >= 34 -> {
                l += Manifest.permission.READ_MEDIA_IMAGES
                l += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            }
            Build.VERSION.SDK_INT >= 33 -> l += Manifest.permission.READ_MEDIA_IMAGES
            else -> l += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        // indispensable pour lire (et donc recopier) la position GPS des photos
        l += Manifest.permission.ACCESS_MEDIA_LOCATION
        return l.toTypedArray()
    }

    // ---------------------------------------------------------------- MediaStore

    private val collection: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private fun uriFor(id: String): Uri = ContentUris.withAppendedId(collection, id.toLong())

    private fun queryString(uri: Uri, column: String): String? =
        contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    private fun queryLong(uri: Uri, column: String): Long? =
        contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    /** Flux de l'image d'origine, position GPS comprise (non masquée). */
    private fun openOriginal(uri: Uri): InputStream? {
        if (granted(Manifest.permission.ACCESS_MEDIA_LOCATION)) {
            try {
                return contentResolver.openInputStream(MediaStore.setRequireOriginal(uri))
            } catch (_: Exception) { }
        }
        return contentResolver.openInputStream(uri)
    }

    private fun listPhotosJson(offset: Int, limit: Int): String {
        val out = JSONArray()
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val args = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        }
        contentResolver.query(collection, proj, args, null)?.use { c ->
            while (c.moveToNext()) {
                val taken = if (c.isNull(2)) 0L else c.getLong(2)
                val added = if (c.isNull(3)) 0L else c.getLong(3) * 1000L
                out.put(JSONObject().apply {
                    put("id", c.getLong(0).toString())
                    put("name", c.getString(1) ?: "photo.jpg")
                    put("date", if (taken > 0) taken else added)
                })
            }
        }
        return out.toString()
    }

    // ---------------------------------------------------------------- sauvegardes des originales

    private val backupDir: File get() = File(filesDir, "originales").apply { mkdirs() }

    private fun findBackup(id: String): File? =
        backupDir.listFiles()?.firstOrNull { it.name.startsWith("${id}__") }

    /** Copie l'originale (avec GPS) avant la première modification. */
    private fun ensureBackup(id: String, uri: Uri, name: String): File {
        findBackup(id)?.let { return it }
        val f = File(backupDir, "${id}__" + name.replace('/', '_'))
        val input = openOriginal(uri) ?: throw IllegalStateException("lecture impossible")
        input.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return f
    }

    private fun cleanupBackups() {
        val limit = System.currentTimeMillis() - backupDays * 24L * 3600L * 1000L
        backupDir.listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
    }

    // ---------------------------------------------------------------- EXIF

    private val exifTags = arrayOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_GPS_VERSION_ID,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
    )

    /** Recopie date, lieu, appareil… de l'originale vers la photo retouchée. */
    private fun copyExif(from: File, to: File) {
        try {
            val src = ExifInterface(from.absolutePath)
            val dst = ExifInterface(to.absolutePath)
            for (tag in exifTags) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dst.setAttribute(ExifInterface.TAG_SOFTWARE, "Halo")
            dst.saveAttributes()
        } catch (_: Exception) {
            // si l'EXIF échoue, la photo est quand même enregistrée
        }
    }

    // ---------------------------------------------------------------- enregistrement

    /** @return "ok" (remplacée), "ok:copy" (copie créée) ; lève une exception en cas d'échec */
    private fun doSave(id: String, b64: String, mode: String): String {
        val uri = uriFor(id)
        val name = queryString(uri, MediaStore.Images.Media.DISPLAY_NAME) ?: "photo_$id.jpg"
        val mime = queryString(uri, MediaStore.Images.Media.MIME_TYPE) ?: "image/jpeg"
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val original = ensureBackup(id, uri, name)
        val tmp = File(cacheDir, "out_$id.jpg")
        tmp.writeBytes(bytes)
        copyExif(original, tmp)

        // un fichier HEIC/PNG ne peut pas recevoir un JPEG : on crée une copie à la place
        val replace = mode == "replace" && (mime == "image/jpeg" || mime == "image/jpg")
        try {
            if (replace) {
                contentResolver.openOutputStream(uri, "wt")?.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                    ?: throw IllegalStateException("écriture impossible")
                return "ok"
            }
            val jpgName = name.substringBeforeLast('.') + ".jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, jpgName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Retouches")
                put(MediaStore.Images.Media.IS_PENDING, 1)
                queryLong(uri, MediaStore.Images.Media.DATE_TAKEN)?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
            }
            val dest = contentResolver.insert(collection, values) ?: throw IllegalStateException("création impossible")
            contentResolver.openOutputStream(dest)?.use { o -> tmp.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(dest, values, null, null)
            return "ok:copy"
        } finally {
            tmp.delete()
        }
    }

    private fun doRestore(id: String): String {
        val f = findBackup(id) ?: return "err:aucune sauvegarde"
        val uri = uriFor(id)
        contentResolver.openOutputStream(uri, "wt")?.use { o -> f.inputStream().use { it.copyTo(o) } }
            ?: return "err:écriture impossible"
        return "ok"
    }

    // ---------------------------------------------------------------- mises à jour (GitHub Releases)

    private val releaseApi = "https://api.github.com/repos/cedriccoste/halo/releases/latest"
    @Volatile private var pendingUpdateUrl: String? = null

    @Suppress("DEPRECATION")
    private fun localVersionCode(): Long = packageManager.getPackageInfo(packageName, 0).longVersionCode

    @Suppress("DEPRECATION")
    private fun localVersionName(): String = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"

    /** Cherche une version plus récente ; répond à window.onUpdateInfo(info | false | null). */
    private fun checkUpdate() {
        Thread {
            try {
                val c = (URL(releaseApi).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Accept", "application/vnd.github+json")
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                if (c.responseCode != 200) {
                    js("window.onUpdateInfo && window.onUpdateInfo(null)")
                    return@Thread
                }
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val tag = j.optString("tag_name")                       // ex. v1.12
                val remoteCode = tag.substringAfterLast('.').toLongOrNull() ?: 0L
                var url: String? = null
                val assets = j.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
                    }
                }
                if (remoteCode > localVersionCode() && url != null) {
                    pendingUpdateUrl = url
                    val info = JSONObject()
                        .put("version", tag.removePrefix("v"))
                        .put("notes", j.optString("body").take(600))
                    js("window.onUpdateInfo && window.onUpdateInfo($info)")
                } else {
                    js("window.onUpdateInfo && window.onUpdateInfo(false)")
                }
            } catch (e: Exception) {
                js("window.onUpdateInfo && window.onUpdateInfo(null)")
            }
        }.start()
    }

    /** Télécharge l'APK puis ouvre l'installeur Android. Progression : window.onUpdateProgress(0..1 | -1 erreur | -2 autorisation). */
    private fun installUpdate() {
        val url = pendingUpdateUrl ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }
            js("window.onUpdateProgress && window.onUpdateProgress(-2)")
            return
        }
        Thread {
            try {
                val dir = File(cacheDir, "updates").apply { mkdirs() }
                val f = File(dir, "halo.apk")
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 30000
                }
                val total = c.contentLengthLong
                c.inputStream.use { input ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0 && done - last > total / 40) {
                                last = done
                                js("window.onUpdateProgress && window.onUpdateProgress(${done.toDouble() / total})")
                            }
                        }
                    }
                }
                val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                js("window.onUpdateProgress && window.onUpdateProgress(1)")
                runOnUiThread { startActivity(intent) }
            } catch (e: Exception) {
                js("window.onUpdateProgress && window.onUpdateProgress(-1)")
            }
        }.start()
    }

    // ---------------------------------------------------------------- fichiers servis à l'éditeur

    /** /media/thumb/{id}  /media/full/{id}  /media/orig/{id} (sauvegarde si elle existe) */
    private inner class MediaHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            return try {
                val parts = path.substringBefore('?').split('/')
                if (parts.size < 2) return notFound()
                val kind = parts[0]
                val id = parts[1]
                val uri = uriFor(id)
                when (kind) {
                    "thumb" -> {
                        val bmp = contentResolver.loadThumbnail(uri, Size(480, 480), null)
                        val bos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 88, bos)
                        bmp.recycle()
                        WebResourceResponse("image/jpeg", null, ByteArrayInputStream(bos.toByteArray()))
                    }
                    "orig" -> {
                        val b = findBackup(id)
                        if (b != null) WebResourceResponse("image/jpeg", null, b.inputStream())
                        else WebResourceResponse(contentResolver.getType(uri) ?: "image/jpeg", null, contentResolver.openInputStream(uri))
                    }
                    "full" -> WebResourceResponse(contentResolver.getType(uri) ?: "image/jpeg", null, contentResolver.openInputStream(uri))
                    else -> notFound()
                }
            } catch (e: Exception) {
                notFound()
            }
        }

        private fun notFound() =
            WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, ByteArrayInputStream(ByteArray(0)))
    }

    // ---------------------------------------------------------------- pont JavaScript

    private inner class Bridge {
        @JavascriptInterface
        fun hasPermission(): Boolean = hasMediaPermission()

        @JavascriptInterface
        fun requestPermission() = runOnUiThread { permLauncher.launch(permissionsToRequest()) }

        @JavascriptInterface
        fun listPhotos(offset: Int, limit: Int): String =
            try { listPhotosJson(offset, limit) } catch (e: Exception) { "[]" }

        /** Une seule boîte de dialogue Android pour autoriser la modification de tout le lot. */
        @JavascriptInterface
        fun requestWrite(idsJson: String) {
            val arr = JSONArray(idsJson)
            val uris = (0 until arr.length()).map { uriFor(arr.getString(it)) }
            runOnUiThread {
                try {
                    val pi = MediaStore.createWriteRequest(contentResolver, uris)
                    writeLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } catch (e: Exception) {
                    js("window.onWriteResult && window.onWriteResult(false)")
                }
            }
        }

        @JavascriptInterface
        fun saveImage(id: String, b64: String, mode: String): String =
            try { doSave(id, b64, mode) } catch (e: Exception) { "err:" + (e.message ?: e.javaClass.simpleName) }

        @JavascriptInterface
        fun listBackups(): String {
            val out = JSONArray()
            backupDir.listFiles()?.forEach { f ->
                val id = f.name.substringBefore("__")
                out.put(JSONObject().apply {
                    put("id", id)
                    put("name", f.name.substringAfter("__"))
                    put("date", f.lastModified())
                })
            }
            return out.toString()
        }

        @JavascriptInterface
        fun restoreBackup(id: String): String =
            try { doRestore(id) } catch (e: Exception) { "err:" + (e.message ?: e.javaClass.simpleName) }

        @JavascriptInterface
        fun getVersion(): String = localVersionName()

        @JavascriptInterface
        fun checkUpdate() = this@MainActivity.checkUpdate()

        @JavascriptInterface
        fun installUpdate() = this@MainActivity.installUpdate()
    }
}
