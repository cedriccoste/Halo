package fr.cedriccoste.retouchelot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.opengl.GLES20
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Retouche des vidéos en arrière-plan.
 * Chaque vidéo est décodée, passée image par image dans le shader des réglages (HaloProgram),
 * ré-encodée avec son audio d'origine, puis écrite à la place de l'originale (ou en copie).
 * Le service tourne au premier plan : le calcul continue quand on quitte l'appli.
 */
@OptIn(UnstableApi::class)
class VideoService : Service() {

    class Job(val id: String, val mode: String, val params: JSONObject) {
        @Volatile var state = "wait"      // wait | run | ok | err
        @Volatile var err = ""
        @Volatile var percent = 0
        @Volatile var name = ""
    }

    companion object {
        val jobs = CopyOnWriteArrayList<Job>()
        @Volatile var running = false
        private const val CHANNEL = "halo_video"
        private const val NOTIF_ID = 7

        /** Identifiants : "123" = photo, "v123" = vidéo. */
        fun uriFor(id: String): Uri =
            if (id.startsWith("v")) ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.substring(1).toLong())
            else ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toLong())

        fun backupDir(ctx: Context): File = File(ctx.filesDir, "originales").apply { mkdirs() }

        fun findBackup(ctx: Context, id: String): File? =
            backupDir(ctx).listFiles()?.firstOrNull { it.name.startsWith("${id}__") }

        fun statusJson(): String {
            val arr = JSONArray()
            var index = 0
            var percent = 0
            var name = ""
            jobs.forEachIndexed { i, j ->
                arr.put(JSONObject().put("id", j.id).put("state", j.state).put("err", j.err).put("percent", j.percent))
                if (j.state == "run") { index = i; percent = j.percent; name = j.name }
            }
            return JSONObject()
                .put("running", running).put("index", index).put("total", jobs.size)
                .put("percent", percent).put("name", name).put("jobs", arr).toString()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Retouche des vidéos", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, NOTIF_ID, notif("Préparation…", 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!running) {
            running = true
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "halo:video").apply { acquire(3 * 60 * 60 * 1000L) }
            next()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { transformer?.cancel() } catch (_: Exception) { }
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        jobs.forEach { if (it.state == "run" || it.state == "wait") { it.state = "err"; it.err = "interrompu" } }
        running = false
        super.onDestroy()
    }

    private fun notif(text: String, percent: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Halo — retouche vidéo")
            .setContentText(text)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotif(job: Job) {
        val i = jobs.indexOf(job) + 1
        val nm = getSystemService(NotificationManager::class.java)
        try { nm.notify(NOTIF_ID, notif("Vidéo $i / ${jobs.size} — ${job.percent} %", job.percent)) } catch (_: Exception) { }
    }

    private fun finish() {
        running = false
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(job: Job, e: Throwable) {
        job.err = e.message ?: e.javaClass.simpleName
        job.state = "err"
        main.post { next() }
    }

    /** Passe à la vidéo suivante (toujours appelé sur le fil principal). */
    private fun next() {
        val job = jobs.firstOrNull { it.state == "wait" } ?: return finish()
        job.state = "run"
        updateNotif(job)
        Thread {
            try {
                val uri = uriFor(job.id)
                val name = contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
                } ?: "video_${job.id}.mp4"
                job.name = name
                // on repart toujours de l'originale : la sauvegarde si elle existe déjà
                var backup = findBackup(this, job.id)
                if (backup == null && job.mode == "replace") {
                    val f = File(backupDir(this), "${job.id}__" + name.replace('/', '_'))
                    val tmp = File(f.path + ".part")
                    contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                    tmp.renameTo(f)
                    backup = f
                }
                val input = backup?.let { Uri.fromFile(it) } ?: uri
                main.post { startTransform(job, uri, input, backup, name) }
            } catch (e: Throwable) {
                fail(job, e)
            }
        }.start()
    }

    private fun startTransform(job: Job, target: Uri, input: Uri, backup: File?, name: String) {
        try {
            val out = File(cacheDir, "halo_video_out.mp4")
            out.delete()
            val item = EditedMediaItem.Builder(MediaItem.fromUri(input))
                .setEffects(Effects(emptyList(), listOf(HaloEffect(job.params))))
                .build()
            // on garde les couleurs telles qu'elles sont dans le fichier : même rendu que l'aperçu
            val frames = DefaultVideoFrameProcessor.Factory.Builder()
                .setSdrWorkingColorSpace(DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_ORIGINAL)
                .build()
            var polling = true
            val t = Transformer.Builder(this)
                .setVideoFrameProcessorFactory(frames)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        polling = false
                        Thread {
                            try {
                                try { copyDates(input, backup, out) } catch (_: Throwable) { }
                                publish(job, target, name, out)
                                job.percent = 100
                                job.state = "ok"
                            } catch (e: Throwable) {
                                job.err = e.message ?: e.javaClass.simpleName
                                job.state = "err"
                            } finally {
                                out.delete()
                                main.post { next() }
                            }
                        }.start()
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        polling = false
                        out.delete()
                        job.err = exportException.message ?: "erreur d'encodage"
                        job.state = "err"
                        next()
                    }
                })
                .build()
            transformer = t
            t.start(item, out.absolutePath)
            val holder = ProgressHolder()
            val tick = object : Runnable {
                override fun run() {
                    if (!polling) return
                    if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        job.percent = holder.progress.coerceIn(0, 99)
                        updateNotif(job)
                    }
                    main.postDelayed(this, 500)
                }
            }
            main.postDelayed(tick, 500)
        } catch (e: Throwable) {
            fail(job, e)
        }
    }

    /** Écrit la vidéo retouchée à la place de l'originale, ou en copie dans Movies/Retouches. */
    private fun publish(job: Job, target: Uri, name: String, out: File) {
        if (job.mode == "replace") {
            contentResolver.openOutputStream(target, "wt")?.use { o -> out.inputStream().use { it.copyTo(o, 1 shl 20) } }
                ?: throw IllegalStateException("écriture impossible")
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name.substringBeforeLast('.') + ".mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Retouches")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val dest = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("création impossible")
        contentResolver.openOutputStream(dest)?.use { o -> out.inputStream().use { it.copyTo(o, 1 shl 20) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(dest, values, null, null)
    }

    // ------------------------------------------------------------ date de tournage

    /** Position du bloc « mvhd » (en-tête du film MP4), cherché à la fin puis au début du fichier. */
    private fun findMvhd(ch: FileChannel): Long {
        val size = ch.size()
        val win = minOf(size, 8L shl 20).toInt()
        for (start in longArrayOf(size - win, 0L)) {
            val buf = ByteBuffer.allocate(win)
            ch.position(start)
            while (buf.hasRemaining() && ch.read(buf) > 0) { }
            val a = buf.array()
            val n = buf.position()
            var i = 0
            while (i < n - 4) {
                if (a[i] == 'm'.code.toByte() && a[i + 1] == 'v'.code.toByte() && a[i + 2] == 'h'.code.toByte() && a[i + 3] == 'd'.code.toByte()) return start + i
                i++
            }
        }
        return -1
    }

    /** Recopie la date de création de la vidéo d'origine dans la vidéo retouchée. */
    private fun copyDates(input: Uri, backup: File?, out: File) {
        var creation = 0L
        var modification = 0L
        val pfd = if (backup == null) contentResolver.openFileDescriptor(input, "r") else null
        val src = if (backup != null) FileInputStream(backup) else FileInputStream(pfd!!.fileDescriptor)
        src.use { s ->
            val ch = s.channel
            val pos = findMvhd(ch)
            if (pos < 0) return
            val b = ByteBuffer.allocate(20)
            ch.position(pos + 4)
            while (b.hasRemaining() && ch.read(b) > 0) { }
            b.flip()
            val version = b.get().toInt()
            b.position(4)
            if (version == 1) { creation = b.long; modification = b.long }
            else { creation = b.int.toLong() and 0xFFFFFFFFL; modification = b.int.toLong() and 0xFFFFFFFFL }
        }
        pfd?.close()
        if (creation == 0L) return
        RandomAccessFile(out, "rw").use { raf ->
            val ch = raf.channel
            val pos = findMvhd(ch)
            if (pos < 0) return
            raf.seek(pos + 4)
            val version = raf.readByte().toInt()
            raf.seek(pos + 8)
            if (version == 1) { raf.writeLong(creation); raf.writeLong(modification) }
            else { raf.writeInt(creation.toInt()); raf.writeInt(modification.toInt()) }
        }
    }
}

// ---------------------------------------------------------------- shader des réglages

/** Les réglages de Halo appliqués à chaque image de la vidéo (mêmes formules que pour les photos). */
@OptIn(UnstableApi::class)
class HaloEffect(private val p: JSONObject) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = HaloProgram(p, useHdr)
}

@OptIn(UnstableApi::class)
class HaloProgram(private val p: JSONObject, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {

    private val prog: GlProgram = try {
        GlProgram(VS, FS)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }
    private var w = 1
    private var h = 1

    init {
        prog.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        w = inputWidth
        h = inputHeight
        return Size(inputWidth, inputHeight)
    }

    private fun v(key: String): Float = (p.optDouble(key, 0.0) / 100.0).toFloat()

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            prog.use()
            prog.setSamplerTexIdUniform("uTex", inputTexId, 0)
            val tr = maxOf(1f, maxOf(w, h) / 3000f)
            prog.setFloatsUniform("uTexel", floatArrayOf(tr / w, tr / h))
            prog.setFloatUniform("uOutAsp", w.toFloat() / h)
            prog.setFloatUniform("uExp", v("exposure") * 2f)
            prog.setFloatUniform("uBri", v("brilliance"))
            prog.setFloatUniform("uHi", v("highlights"))
            prog.setFloatUniform("uSh", v("shadows"))
            prog.setFloatUniform("uCon", v("contrast"))
            prog.setFloatUniform("uLum", v("brightness"))
            prog.setFloatUniform("uBlk", v("blackpoint"))
            prog.setFloatUniform("uSat", v("saturation"))
            prog.setFloatUniform("uVib", v("vibrance"))
            prog.setFloatUniform("uWarm", v("warmth"))
            prog.setFloatUniform("uTint", v("tint"))
            prog.setFloatUniform("uSharp", v("sharpness"))
            prog.setFloatUniform("uVig", v("vignette"))
            prog.setFloatUniform("uMono", p.optDouble("mono", 0.0).toFloat())
            prog.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            prog.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        private const val VS = """
attribute vec4 aFramePosition;
varying vec2 vUv;
void main() {
  gl_Position = aFramePosition;
  vUv = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}"""

        // Copie fidèle du shader photo (assets/index.html), sans réduction du bruit ni définition.
        private const val FS = """
precision highp float;
varying vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uTexel;
uniform float uOutAsp;
uniform float uExp,uBri,uHi,uSh,uCon,uLum,uBlk,uSat,uVib,uWarm,uTint,uSharp,uVig,uMono;
const vec3 LW = vec3(.2126,.7152,.0722);
float luma(vec3 c){ return dot(c, LW); }
void main(){
  vec3 src = texture2D(uTex, vUv).rgb;
  vec3 col = src;
  if(uSharp>0.){
    vec3 n4 = (texture2D(uTex,vUv+vec2(uTexel.x,0.)).rgb + texture2D(uTex,vUv-vec2(uTexel.x,0.)).rgb
             + texture2D(uTex,vUv+vec2(0.,uTexel.y)).rgb + texture2D(uTex,vUv-vec2(0.,uTexel.y)).rgb)*.25;
    col += uSharp*1.6*(src-n4);
  }
  vec3 lin = pow(max(col,0.), vec3(2.2));
  lin *= exp2(uExp);
  lin *= vec3(1.+.2*uWarm, 1.-.15*uTint, 1.-.2*uWarm);
  col = pow(max(lin,0.), vec3(1./2.2));
  float L = luma(col), nL = L;
  float sh = uSh + .55*uBri, hi = uHi - .5*uBri;
  float sm = 1.-smoothstep(0.,.6,nL);
  nL += sh*.38*sm*sqrt(max(nL,0.))*(sh>0.?1.:.8);
  float hm = smoothstep(.4,1.,nL);
  nL += hi*.32*hm*(hi<0.? nL : (1.-nL)+.15);
  nL = pow(clamp(nL,0.,4.), exp(-uLum*.55));
  float r = nL/max(L,1e-4);
  col = mix(col*r, col + (nL-L), .35);
  float k = uBlk*.12;
  col = k>0. ? (col-k)/(1.-k) : col*(1.+k)-k;
  col = clamp(col,0.,1.);
  if(uCon>0.) col = mix(col, col*col*(3.-2.*col), uCon);
  else col = mix(col, .5+(col-.5)*.55, -uCon);
  L = luma(col);
  col = mix(vec3(L), col, 1.+uSat);
  float mx = max(col.r,max(col.g,col.b)), mn = min(col.r,min(col.g,col.b));
  col = mix(vec3(L), col, 1.+uVib*(1.-smoothstep(0.,.7,mx-mn)));
  col = mix(col, vec3(luma(col)), uMono);
  vec2 dv = vUv-.5; dv.x *= uOutAsp;
  float rr = length(dv)/length(vec2(uOutAsp,1.)*.5);
  col *= 1.-uVig*.6*smoothstep(.3,1.,rr);
  gl_FragColor = vec4(clamp(col,0.,1.),1.);
}"""
    }
}
