package com.example.dashcam

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface

/** Minimal EGL wrapper: one GLES2 context that can render to window surfaces (preview / encoder). */
class EglCore {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    val context: EGLContext

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) {
            "eglChooseConfig failed"
        }
        config = configs[0]!!
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
    }

    fun createWindowSurface(surface: Any): EGLSurface {
        val s = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(s != null && s != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: 0x" + Integer.toHexString(EGL14.eglGetError()) }
        return s
    }

    fun createPbuffer(w: Int, h: Int): EGLSurface =
        EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0
        )

    fun makeCurrent(s: EGLSurface) {
        if (!EGL14.eglMakeCurrent(display, s, s, context)) {
            throw IllegalStateException("eglMakeCurrent failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
        }
    }

    fun swap(s: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, s)

    fun setPresentationTime(s: EGLSurface, nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(display, s, nsecs)
    }

    fun releaseSurface(s: EGLSurface) {
        EGL14.eglDestroySurface(display, s)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
