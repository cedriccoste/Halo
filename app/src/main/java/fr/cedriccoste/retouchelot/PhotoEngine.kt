package fr.cedriccoste.retouchelot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.SystemClock
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Calcul des photos en pleine résolution, directement sur la puce graphique du téléphone.
 *
 * L'éditeur (assets/index.html) envoie son propre shader et la liste de ses réglages
 * (« uniforms ») : le rendu est donc le même que l'aperçu, mais sans passer par la WebView,
 * beaucoup plus lente pour décoder, calculer et compresser une grande photo.
 */
object PhotoEngine {

    const val MAX_SIDE = 8192

    // même repère que l'éditeur : vUv.y = 0 en haut de l'image ; la première ligne lue est le haut de la photo
    private const val VS = "#version 300 es\n" +
        "in vec2 p; out vec2 vUv;\n" +
        "void main(){ vUv = vec2(p.x*.5+.5, p.y*.5+.5); gl_Position = vec4(p,0.,1.); }"

    fun matrixFor(orientation: Int): Matrix? = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { setScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { setRotate(180f) }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { setScale(1f, -1f) }
        ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply { setRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { setRotate(90f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply { setRotate(-90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { setRotate(-90f) }
        else -> null
    }

    /** Taille de la photo une fois redressée et limitée à MAX_SIDE (ce que l'éditeur doit prévoir). */
    fun plannedSize(rawW: Int, rawH: Int, orientation: Int): Pair<Int, Int> {
        var w = rawW
        var h = rawH
        if (orientation in 5..8) { w = rawH; h = rawW }
        val k = minOf(1.0, MAX_SIDE.toDouble() / maxOf(w, h))
        return Pair(Math.round(w * k).toInt(), Math.round(h * k).toInt())
    }

    private fun load(src: File): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        var bmp = BitmapFactory.decodeFile(src.absolutePath, opts) ?: throw IllegalStateException("décodage impossible")
        val orientation = ExifInterface(src.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        matrixFor(orientation)?.let { m ->
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r !== bmp) { bmp.recycle(); bmp = r }
        }
        if (maxOf(bmp.width, bmp.height) > MAX_SIDE) {
            val k = MAX_SIDE.toDouble() / maxOf(bmp.width, bmp.height)
            val r = Bitmap.createScaledBitmap(bmp, Math.round(bmp.width * k).toInt(), Math.round(bmp.height * k).toInt(), true)
            if (r !== bmp) { bmp.recycle(); bmp = r }
        }
        return bmp
    }

    /** Luminosité moyenne de la moitié haute et de la moitié basse (échantillonnage grossier). */
    private fun halves(w: Int, h: Int, lumaAt: (Int, Int) -> Int): IntArray {
        val sum = LongArray(2)
        val cnt = IntArray(2)
        for (j in 0 until 24) {
            val y = (j + 0.5) * h / 24
            for (i in 0 until 24) {
                val x = (i + 0.5) * w / 24
                val k = if (j < 12) 0 else 1
                sum[k] += lumaAt(x.toInt(), y.toInt()).toLong()
                cnt[k]++
            }
        }
        return intArrayOf((sum[0] / cnt[0]).toInt(), (sum[1] / cnt[1]).toInt())
    }

    private fun shader(type: Int, source: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, source)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("shader : " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    private fun texParams(mip: Boolean) {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, if (mip) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun checkGl(step: String) {
        val e = GLES30.glGetError()
        if (e != GLES30.GL_NO_ERROR) throw IllegalStateException("GL $step : 0x" + Integer.toHexString(e))
    }

    /**
     * Calcule la photo `src` avec les réglages de `job` et écrit le JPEG dans `out`.
     * @return les durées de chaque étape, en millisecondes (pour le diagnostic)
     */
    fun render(src: File, job: JSONObject, out: File): String {
        val t0 = SystemClock.elapsedRealtime()
        val bmp = load(src)
        val sw = bmp.width
        val sh = bmp.height
        if (sw != job.getInt("srcW") || sh != job.getInt("srcH")) {
            bmp.recycle()
            throw IllegalStateException("taille inattendue ${sw}x$sh")
        }
        val srcHalves = halves(sw, sh) { x, y -> val c = bmp.getPixel(x, y); (((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 6 + (c and 255)) / 10 }
        val ow = job.getInt("outW")
        val oh = job.getInt("outH")
        val t1 = SystemClock.elapsedRealtime()

        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) { bmp.recycle(); throw IllegalStateException("EGL indisponible") }
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(
            dpy, intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            ), 0, cfgs, 0, 1, n, 0
        )
        if (n[0] < 1) { bmp.recycle(); throw IllegalStateException("EGL : aucune configuration") }
        val ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) { bmp.recycle(); throw IllegalStateException("EGL : contexte refusé") }
        val surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        var t2 = t1
        val outBmp: Bitmap
        try {
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) throw IllegalStateException("EGL : activation refusée")

            val maxTex = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTex, 0)
            if (maxOf(sw, sh, ow, oh) > maxTex[0]) throw IllegalStateException("photo trop grande pour la puce graphique")

            val prog = GLES30.glCreateProgram()
            GLES30.glAttachShader(prog, shader(GLES30.GL_VERTEX_SHADER, VS))
            GLES30.glAttachShader(prog, shader(GLES30.GL_FRAGMENT_SHADER, job.getString("fs")))
            GLES30.glLinkProgram(prog)
            val linked = IntArray(1)
            GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) throw IllegalStateException("shader : " + GLES30.glGetProgramInfoLog(prog))
            GLES30.glUseProgram(prog)

            val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
            quad.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
            val pLoc = GLES30.glGetAttribLocation(prog, "p")
            GLES30.glEnableVertexAttribArray(pLoc)
            GLES30.glVertexAttribPointer(pLoc, 2, GLES30.GL_FLOAT, false, 0, quad)

            val tex = IntArray(3)
            GLES30.glGenTextures(3, tex, 0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)

            // 0 : la photo
            val mip = job.optBoolean("mip", false)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()                                   // libère la mémoire dès que la puce a l'image
            if (mip) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            texParams(mip)
            checkGl("photo")

            // 1 : la zone peinte (ou un pixel vide)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[1])
            val maskB64 = job.optString("mask", "")
            if (maskB64.isNotEmpty()) {
                val bytes = Base64.decode(maskB64, Base64.DEFAULT)
                val mask = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                    ?: throw IllegalStateException("zone illisible")
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, mask, 0)
                mask.recycle()
            } else {
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(4))
            }
            texParams(false)

            // 2 : l'image de sortie
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[2])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, ow, oh, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            texParams(false)
            val fbo = IntArray(1)
            GLES30.glGenFramebuffers(1, fbo, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[2], 0)
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) throw IllegalStateException("GL : image de sortie refusée")
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

            // les réglages, tels que l'éditeur les a calculés : [nom, type, valeurs…]
            val us = job.getJSONArray("uniforms")
            for (i in 0 until us.length()) {
                val u = us.getJSONArray(i)
                val loc = GLES30.glGetUniformLocation(prog, u.getString(0))
                if (loc < 0) continue
                when (u.getString(1)) {
                    "1i" -> GLES30.glUniform1i(loc, u.getInt(2))
                    "2f" -> GLES30.glUniform2f(loc, u.getDouble(2).toFloat(), u.getDouble(3).toFloat())
                    else -> GLES30.glUniform1f(loc, u.getDouble(2).toFloat())
                }
            }

            GLES30.glViewport(0, 0, ow, oh)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            checkGl("calcul")

            val px = ByteBuffer.allocateDirect(ow * oh * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, ow, oh, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
            checkGl("lecture")
            // garde-fous avant de toucher à la photo : image vide ou à l'envers = on abandonne (l'ancien moteur prend le relais)
            val outHalves = halves(ow, oh) { x, y -> val o = (y * ow + x) * 4; ((px.get(o).toInt() and 255) * 3 + (px.get(o + 1).toInt() and 255) * 6 + (px.get(o + 2).toInt() and 255)) / 10 }
            if (outHalves[0] == 0 && outHalves[1] == 0 && (srcHalves[0] > 8 || srcHalves[1] > 8)) throw IllegalStateException("image vide")
            if (job.optBoolean("plain", false)) {
                val a = srcHalves[0] - srcHalves[1]
                val b = outHalves[0] - outHalves[1]
                if (Math.abs(a) > 20 && Math.abs(b) > 6 && (a > 0) != (b > 0)) throw IllegalStateException("image retournée")
            }
            px.rewind()
            outBmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            outBmp.copyPixelsFromBuffer(px)
            t2 = SystemClock.elapsedRealtime()
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, surf)
            EGL14.eglDestroyContext(dpy, ctx)
        }

        BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { o ->
            if (!outBmp.compress(Bitmap.CompressFormat.JPEG, 95, o)) throw IllegalStateException("compression impossible")
        }
        outBmp.recycle()
        val t3 = SystemClock.elapsedRealtime()
        return "px=${sw}x$sh,dec=${t1 - t0},gl=${t2 - t1},jpg=${t3 - t2}"
    }
}
