package com.example.dashcam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The road-facing lenses a phone offers. Some phones expose the ultra-wide as its own camera ID
 * (e.g. Moto), others as zoom below 1x on the main camera (e.g. Pixel); both are listed.
 */
object Lenses {
    data class Lens(val key: String, val label: String, val cameraId: String, val zoom: Float?, val relative: Float)

    @Volatile var lastProbe = ""

    private fun equivFocal(ch: CameraCharacteristics): Float? {
        val f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return null
        val s = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val diag = sqrt(s.width * s.width + s.height * s.height)
        return if (diag > 0) f * 43.27f / diag else null
    }

    fun backLenses(ctx: Context): List<Lens> {
        val mgr = ctx.getSystemService(CameraManager::class.java)
        val listed = try { mgr.cameraIdList.toList() } catch (_: Exception) { emptyList() }
        // Some phones (e.g. Moto) leave the ultra-wide out of the public list but still let apps
        // open it by ID, so probe a few unlisted IDs too.
        val hidden = (0..9).map { it.toString() }.filter { it !in listed }.filter { id ->
            try { mgr.getCameraCharacteristics(id); true } catch (_: Exception) { false }
        }
        val back = (listed + hidden).filter {
            try {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } catch (_: Exception) { false }
        }
        lastProbe = "listed=$listed hidden=$hidden"
        if (back.isEmpty()) return emptyList()
        val mainId = back.first()
        val mainEq = equivFocal(mgr.getCameraCharacteristics(mainId))
        val out = ArrayList<Lens>()
        for (id in back) {
            val ch = mgr.getCameraCharacteristics(id)
            val eq = equivFocal(ch)
            val rel = if (id == mainId || eq == null || mainEq == null) 1f else eq / mainEq
            val kind = when {
                id == mainId -> "Main"
                rel < 0.9f -> "Ultra-wide"
                rel > 1.1f -> "Telephoto"
                else -> "Camera"
            }
            val mm = eq?.let { String.format(Locale.US, ", ~%d mm", it.toInt()) } ?: ""
            out += Lens("id:$id", String.format(Locale.US, "%s %.1fx (camera %s%s)", kind, rel, id, mm), id, null, rel)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val r = mgr.getCameraCharacteristics(mainId).get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (r != null && r.lower < 0.95f && out.none { abs(it.relative - r.lower) < 0.1f }) {
                out += Lens("zoom:${r.lower}", String.format(Locale.US, "Ultra-wide %.1fx (main camera zoom)", r.lower),
                    mainId, r.lower, r.lower)
            }
        }
        return out
    }

    /** "widest" (default), "main", or a specific key from [backLenses]. Falls back to the main lens. */
    fun resolve(ctx: Context, key: String): Lens? {
        val all = backLenses(ctx)
        if (all.isEmpty()) return null
        return when (key) {
            "widest" -> all.minBy { it.relative }
            "main" -> all.first()
            else -> all.firstOrNull { it.key == key } ?: all.first()
        }
    }
}
