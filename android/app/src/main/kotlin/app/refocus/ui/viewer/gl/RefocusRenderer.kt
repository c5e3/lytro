package app.refocus.ui.viewer.gl

import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.util.Log
import app.refocus.core.lf.Half
import app.refocus.core.lf.LightField
import app.refocus.core.lf.RenderParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

enum class ViewMode { Photo, Depth, Overlay }

/** Where the image sits in the view: zoom ≥ 1 and pan in view pixels. */
data class ViewTransform(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {

    /** Displayed image rectangle (left, top, width, height) in view pixels. */
    fun rect(viewW: Float, viewH: Float, imgW: Int, imgH: Int): FloatArray {
        val base = minOf(viewW / imgW, viewH / imgH)
        val w = imgW * base * zoom
        val h = imgH * base * zoom
        return floatArrayOf(viewW / 2 + panX - w / 2, viewH / 2 + panY - h / 2, w, h)
    }

    /** Keeps the image from being dragged away from the view. */
    fun clamped(viewW: Float, viewH: Float, imgW: Int, imgH: Int): ViewTransform {
        val z = zoom.coerceIn(1f, MAX_ZOOM)
        val r = copy(zoom = z).rect(viewW, viewH, imgW, imgH)
        val mx = maxOf(0f, (r[2] - viewW) / 2)
        val my = maxOf(0f, (r[3] - viewH) / 2)
        return ViewTransform(z, panX.coerceIn(-mx, mx), panY.coerceIn(-my, my))
    }

    /** View pixel → image pixel, or null outside the image. */
    fun toImage(x: Float, y: Float, viewW: Float, viewH: Float, imgW: Int, imgH: Int): Pair<Float, Float>? {
        val r = rect(viewW, viewH, imgW, imgH)
        val ix = (x - r[0]) / r[2] * imgW
        val iy = (y - r[1]) / r[3] * imgH
        return if (ix in 0f..imgW.toFloat() && iy in 0f..imgH.toFloat()) ix to iy else null
    }

    companion object { const val MAX_ZOOM = 6f }
}

/**
 * Two-pass OpenGL ES 3 renderer:
 *  1. shift-and-add refocus of the sub-aperture views (a texture array) into a
 *     native-resolution texture, only when focus/aperture/viewpoint change;
 *  2. draw that texture (or the depth heatmap / overlay) to the screen with
 *     bicubic upscaling and the current zoom/pan.
 */
class RefocusRenderer(private val onFrame: () -> Unit = {}) : GLSurfaceView.Renderer {

    @Volatile var lightField: LightField? = null
    @Volatile var params = RenderParams()
    @Volatile var mode = ViewMode.Photo
    @Volatile var transform = ViewTransform()
    @Volatile var background = floatArrayOf(0f, 0f, 0f)
    /** Bump to re-upload the depth map after it was computed. */
    @Volatile var depthVersion = 0

    private var uploaded: LightField? = null
    private var uploadedDepth = -1
    private var hasDepth = false
    private var renderedParams: RenderParams? = null

    private var refocusProgram = 0
    private var displayProgram = 0
    private var viewsTex = 0
    private var depthTex = 0
    private var imageTex = 0
    private var fbo = 0
    private var vao = 0
    private var surfaceW = 1
    private var surfaceH = 1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        refocusProgram = program(VERTEX_FULL, FRAGMENT_REFOCUS)
        displayProgram = program(VERTEX_RECT, FRAGMENT_DISPLAY)
        val ids = IntArray(1)
        glGenVertexArrays(1, ids, 0); vao = ids[0]
        // context (re)created: everything must be uploaded again
        uploaded = null
        uploadedDepth = -1
        renderedParams = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        val bg = background
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, surfaceW, surfaceH)
        glClearColor(bg[0], bg[1], bg[2], 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        val lf = lightField ?: return
        if (uploaded !== lf) upload(lf)
        if (uploadedDepth != depthVersion) uploadDepth(lf)

        val p = params
        if (p != renderedParams) {
            refocus(lf, p)
            renderedParams = p
        }
        display(lf)
        onFrame()
    }

    // ------------------------------------------------------------------ upload
    private fun upload(lf: LightField) {
        val n = lf.pixels
        val buf: ShortBuffer = ByteBuffer.allocateDirect(lf.count * n * 3 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (k in 0 until lf.count) {
            val r = lf.viewOffset(k, 0)
            val base = k * n * 3
            for (j in 0 until n) {
                buf.put(base + 3 * j, Half.fromFloat(lf.views[r + j]))
                buf.put(base + 3 * j + 1, Half.fromFloat(lf.views[r + n + j]))
                buf.put(base + 3 * j + 2, Half.fromFloat(lf.views[r + 2 * n + j]))
            }
        }
        buf.position(0)
        deleteTextures()
        val ids = IntArray(3)
        glGenTextures(3, ids, 0)
        viewsTex = ids[0]; depthTex = ids[1]; imageTex = ids[2]

        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glBindTexture(GL_TEXTURE_2D_ARRAY, viewsTex)
        glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGB16F, lf.width, lf.height, lf.count, 0, GL_RGB, GL_HALF_FLOAT, buf)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_MIRRORED_REPEAT)
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_MIRRORED_REPEAT)

        glBindTexture(GL_TEXTURE_2D, imageTex)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, lf.width, lf.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        texParams2D()
        val f = IntArray(1)
        if (fbo == 0) { glGenFramebuffers(1, f, 0); fbo = f[0] }
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, imageTex, 0)
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) Log.e(TAG, "framebuffer incomplete")
        glBindFramebuffer(GL_FRAMEBUFFER, 0)

        // 1×1 placeholder until the depth map exists
        glBindTexture(GL_TEXTURE_2D, depthTex)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R16F, 1, 1, 0, GL_RED, GL_HALF_FLOAT,
            ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder()).asShortBuffer().put(0, Half.fromFloat(0.5f)))
        texParams2D()
        hasDepth = false
        uploadedDepth = -1
        uploaded = lf
        renderedParams = null
    }

    private fun uploadDepth(lf: LightField) {
        val d = lf.depth
        val range = lf.depthRange
        if (d != null && range != null) {
            val (lo, hi) = range
            val buf = ByteBuffer.allocateDirect(lf.pixels * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            for (i in 0 until lf.pixels) buf.put(i, Half.fromFloat(((d[i] - lo) / (hi - lo + 1e-9f)).coerceIn(0f, 1f)))
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
            glBindTexture(GL_TEXTURE_2D, depthTex)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R16F, lf.width, lf.height, 0, GL_RED, GL_HALF_FLOAT, buf)
            texParams2D()
            hasDepth = true
        }
        uploadedDepth = depthVersion
    }

    private fun texParams2D() {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }

    private fun deleteTextures() {
        if (viewsTex != 0) glDeleteTextures(3, intArrayOf(viewsTex, depthTex, imageTex), 0)
        viewsTex = 0; depthTex = 0; imageTex = 0
    }

    // ------------------------------------------------------------------ passes
    private val viewUniform = FloatArray(4 * MAX_VIEWS)

    private fun refocus(lf: LightField, p: RenderParams) {
        val weights = p.viewWeights(lf.offsets)
        var count = 0
        for (k in 0 until lf.count) {
            if (weights[k] <= 0f || count >= MAX_VIEWS) continue
            viewUniform[4 * count] = p.slope * lf.offsets[2 * k]
            viewUniform[4 * count + 1] = p.slope * lf.offsets[2 * k + 1]
            viewUniform[4 * count + 2] = weights[k]
            viewUniform[4 * count + 3] = k.toFloat()
            count++
        }
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glViewport(0, 0, lf.width, lf.height)
        glUseProgram(refocusProgram)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D_ARRAY, viewsTex)
        glUniform1i(glGetUniformLocation(refocusProgram, "uViews"), 0)
        glUniform1i(glGetUniformLocation(refocusProgram, "uCount"), count)
        glUniform4fv(glGetUniformLocation(refocusProgram, "uView"), count, viewUniform, 0)
        glUniform2f(glGetUniformLocation(refocusProgram, "uSize"), lf.width.toFloat(), lf.height.toFloat())
        glUniform1f(glGetUniformLocation(refocusProgram, "uGamma"), lf.gamma)
        glBindVertexArray(vao)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    private fun display(lf: LightField) {
        glViewport(0, 0, surfaceW, surfaceH)
        val r = transform.rect(surfaceW.toFloat(), surfaceH.toFloat(), lf.width, lf.height)
        // to normalised device coordinates (y up)
        val x0 = r[0] / surfaceW * 2 - 1
        val x1 = (r[0] + r[2]) / surfaceW * 2 - 1
        val y0 = 1 - r[1] / surfaceH * 2
        val y1 = 1 - (r[1] + r[3]) / surfaceH * 2
        glUseProgram(displayProgram)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, imageTex)
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, depthTex)
        glUniform1i(glGetUniformLocation(displayProgram, "uImage"), 0)
        glUniform1i(glGetUniformLocation(displayProgram, "uDepth"), 1)
        glUniform4f(glGetUniformLocation(displayProgram, "uRect"), x0, y0, x1, y1)
        glUniform2f(glGetUniformLocation(displayProgram, "uSize"), lf.width.toFloat(), lf.height.toFloat())
        val m = if (!hasDepth) 0 else when (mode) { ViewMode.Photo -> 0; ViewMode.Depth -> 1; ViewMode.Overlay -> 2 }
        glUniform1i(glGetUniformLocation(displayProgram, "uMode"), m)
        glBindVertexArray(vao)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
    }

    // ----------------------------------------------------------------- shaders
    private fun program(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = glCreateShader(type)
            glShaderSource(s, src)
            glCompileShader(s)
            val ok = IntArray(1)
            glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) error("shader: " + glGetShaderInfoLog(s))
            return s
        }
        val p = glCreateProgram()
        glAttachShader(p, compile(GL_VERTEX_SHADER, vs))
        glAttachShader(p, compile(GL_FRAGMENT_SHADER, fs))
        glLinkProgram(p)
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("program: " + glGetProgramInfoLog(p))
        return p
    }

    companion object {
        private const val TAG = "RefocusRenderer"
        const val MAX_VIEWS = 64

        private const val VERTEX_FULL = """#version 300 es
            out vec2 vUv;
            void main() {
                vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
                vUv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }"""

        private const val VERTEX_RECT = """#version 300 es
            uniform vec4 uRect; // x0, yTop, x1, yBottom in NDC
            out vec2 vUv;
            void main() {
                vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
                vUv = vec2(p.x, p.y);
                gl_Position = vec4(mix(uRect.x, uRect.z, p.x), mix(uRect.y, uRect.w, p.y), 0.0, 1.0);
            }"""

        // Shift-and-add: every active view is sampled at p - shift (Catmull-Rom,
        // 9 bilinear taps) and accumulated with its aperture weight.
        private const val FRAGMENT_REFOCUS = """#version 300 es
            precision highp float;
            precision highp sampler2DArray;
            uniform sampler2DArray uViews;
            uniform int uCount;
            uniform vec4 uView[64];
            uniform vec2 uSize;
            uniform float uGamma;
            in vec2 vUv;
            out vec4 fragColor;

            vec3 catmullRom(float layer, vec2 pos) {
                vec2 t1 = floor(pos - 0.5) + 0.5;
                vec2 f = pos - t1;
                vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
                vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
                vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
                vec2 w3 = f * f * (-0.5 + 0.5 * f);
                vec2 w12 = w1 + w2;
                vec2 t0 = (t1 - 1.0) / uSize;
                vec2 t3 = (t1 + 2.0) / uSize;
                vec2 t12 = (t1 + w2 / w12) / uSize;
                vec3 r = vec3(0.0);
                r += texture(uViews, vec3(t0.x, t0.y, layer)).rgb * w0.x * w0.y;
                r += texture(uViews, vec3(t12.x, t0.y, layer)).rgb * w12.x * w0.y;
                r += texture(uViews, vec3(t3.x, t0.y, layer)).rgb * w3.x * w0.y;
                r += texture(uViews, vec3(t0.x, t12.y, layer)).rgb * w0.x * w12.y;
                r += texture(uViews, vec3(t12.x, t12.y, layer)).rgb * w12.x * w12.y;
                r += texture(uViews, vec3(t3.x, t12.y, layer)).rgb * w3.x * w12.y;
                r += texture(uViews, vec3(t0.x, t3.y, layer)).rgb * w0.x * w3.y;
                r += texture(uViews, vec3(t12.x, t3.y, layer)).rgb * w12.x * w3.y;
                r += texture(uViews, vec3(t3.x, t3.y, layer)).rgb * w3.x * w3.y;
                return r;
            }

            void main() {
                vec2 p = vUv * uSize;
                vec3 acc = vec3(0.0);
                float total = 0.0;
                for (int i = 0; i < 64; i++) {
                    if (i >= uCount) break;
                    vec4 v = uView[i];
                    acc += v.z * catmullRom(v.w, p - v.xy);
                    total += v.z;
                }
                vec3 lin = clamp(acc / max(total, 1e-6), 0.0, 1.0);
                fragColor = vec4(pow(lin, vec3(uGamma)), 1.0);
            }"""

        // Bicubic upscale of the refocused image; depth heatmap (turbo) and overlay.
        private const val FRAGMENT_DISPLAY = """#version 300 es
            precision highp float;
            uniform sampler2D uImage;
            uniform sampler2D uDepth;
            uniform vec2 uSize;
            uniform int uMode;
            in vec2 vUv;
            out vec4 fragColor;

            vec3 catmullRom(vec2 uv) {
                vec2 pos = uv * uSize;
                vec2 t1 = floor(pos - 0.5) + 0.5;
                vec2 f = pos - t1;
                vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
                vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
                vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
                vec2 w3 = f * f * (-0.5 + 0.5 * f);
                vec2 w12 = w1 + w2;
                vec2 t0 = (t1 - 1.0) / uSize;
                vec2 t3 = (t1 + 2.0) / uSize;
                vec2 t12 = (t1 + w2 / w12) / uSize;
                vec3 r = vec3(0.0);
                r += texture(uImage, vec2(t0.x, t0.y)).rgb * w0.x * w0.y;
                r += texture(uImage, vec2(t12.x, t0.y)).rgb * w12.x * w0.y;
                r += texture(uImage, vec2(t3.x, t0.y)).rgb * w3.x * w0.y;
                r += texture(uImage, vec2(t0.x, t12.y)).rgb * w0.x * w12.y;
                r += texture(uImage, vec2(t12.x, t12.y)).rgb * w12.x * w12.y;
                r += texture(uImage, vec2(t3.x, t12.y)).rgb * w3.x * w12.y;
                r += texture(uImage, vec2(t0.x, t3.y)).rgb * w0.x * w3.y;
                r += texture(uImage, vec2(t12.x, t3.y)).rgb * w12.x * w3.y;
                r += texture(uImage, vec2(t3.x, t3.y)).rgb * w3.x * w3.y;
                return clamp(r, 0.0, 1.0);
            }

            vec3 turbo(float x) {
                const vec4 kR4 = vec4(0.13572138, 4.61539260, -42.66032258, 132.13108234);
                const vec4 kG4 = vec4(0.09140261, 2.19418839, 4.84296658, -14.18503333);
                const vec4 kB4 = vec4(0.10667330, 12.64194608, -60.58204836, 110.36276771);
                const vec2 kR2 = vec2(-152.94239396, 59.28637943);
                const vec2 kG2 = vec2(4.27729857, 2.82956604);
                const vec2 kB2 = vec2(-89.90310912, 27.34824973);
                x = clamp(x, 0.0, 1.0);
                vec4 v4 = vec4(1.0, x, x * x, x * x * x);
                vec2 v2 = v4.zw * v4.z;
                return clamp(vec3(dot(v4, kR4) + dot(v2, kR2), dot(v4, kG4) + dot(v2, kG2), dot(v4, kB4) + dot(v2, kB2)), 0.0, 1.0);
            }

            void main() {
                vec3 photo = catmullRom(vUv);
                if (uMode == 0) { fragColor = vec4(photo, 1.0); return; }
                vec3 heat = turbo(texture(uDepth, vUv).r);
                fragColor = vec4(uMode == 1 ? heat : 0.45 * photo + 0.55 * heat, 1.0);
            }"""
    }
}
