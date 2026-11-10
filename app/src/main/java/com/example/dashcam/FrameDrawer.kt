package com.example.dashcam

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** GLES2 programs for drawing the camera (external OES) texture and the 2D overlay texture. */
class FrameDrawer {
    private val oesProg = program(VS, FS_OES)
    private val texProg = program(VS, FS_2D)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private val fullQuad = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val camTex = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    // Bitmaps are uploaded top row first, so flip V for the overlay.
    private val bmpTex = floats(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)

    fun createOesTexture(): Int = genTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
    fun create2dTexture(): Int = genTexture(GLES20.GL_TEXTURE_2D)

    fun drawCamera(tex: Int, texMatrix: FloatArray, mvp: FloatArray) {
        draw(oesProg, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex, fullQuad, camTex, mvp, texMatrix)
    }

    /** Draws a 2D texture over the band of the frame between NDC y = -1 and [topNdc]. */
    fun drawOverlay(tex: Int, quad: FloatBuffer) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // bitmap is premultiplied
        draw(texProg, GLES20.GL_TEXTURE_2D, tex, quad, bmpTex, identity, identity)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private val colorProg = program(VS_COLOR, FS_COLOR)
    private val rect = floats(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

    /** Fills a rectangle given in NDC (−1..1) with an ARGB colour (blended). */
    fun drawRect(x0: Float, y0: Float, x1: Float, y1: Float, argb: Int) {
        rect.position(0); rect.put(x0).put(y0).put(x1).put(y0).put(x0).put(y1).put(x1).put(y1); rect.position(0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(colorProg)
        val aPos = GLES20.glGetAttribLocation(colorProg, "aPos")
        GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProg, "uColor"),
            (argb shr 16 and 0xFF) / 255f, (argb shr 8 and 0xFF) / 255f, (argb and 0xFF) / 255f, (argb ushr 24) / 255f)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, rect)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun release() {
        GLES20.glDeleteProgram(colorProg)
        GLES20.glDeleteProgram(oesProg)
        GLES20.glDeleteProgram(texProg)
    }

    private fun draw(
        prog: Int, target: Int, tex: Int, pos: FloatBuffer, uv: FloatBuffer,
        mvp: FloatArray, texMatrix: FloatArray
    ) {
        GLES20.glUseProgram(prog)
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aTex = GLES20.glGetAttribLocation(prog, "aTex")
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uTexM"), 1, false, texMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "sTex"), 0)
        pos.position(0); uv.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 8, pos)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 8, uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        GLES20.glBindTexture(target, 0)
    }

    companion object {
        private const val VS = """
            uniform mat4 uMvp;
            uniform mat4 uTexM;
            attribute vec4 aPos;
            attribute vec4 aTex;
            varying vec2 vTex;
            void main() {
                gl_Position = uMvp * aPos;
                vTex = (uTexM * aTex).xy;
            }"""
        private const val FS_OES = """#extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }"""
        private const val VS_COLOR = """
            attribute vec4 aPos;
            void main() { gl_Position = aPos; }"""
        private const val FS_COLOR = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }"""
        private const val FS_2D = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }"""

        fun floats(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .apply { put(v); position(0) }

        private fun genTexture(target: Int): Int {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            GLES20.glBindTexture(target, t[0])
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindTexture(target, 0)
            return t[0]
        }

        private fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader compile: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }

        private fun program(vs: String, fs: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "program link: " + GLES20.glGetProgramInfoLog(p) }
            return p
        }
    }
}
