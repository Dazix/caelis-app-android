package com.dazix.caelis.data.studio

import android.content.Context
import androidx.core.content.edit
import com.dazix.caelis.domain.studio.Aspect
import com.dazix.caelis.domain.studio.Resolution
import com.dazix.caelis.domain.studio.StudioOptions

/** Studio's output options, by enum name so a reordering of the choices cannot change a stored value. */
class StudioSettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("studio_options", Context.MODE_PRIVATE)

    fun load(): StudioOptions {
        val d = StudioOptions()
        return StudioOptions(
            fps = prefs.getInt(KEY_FPS, d.fps),
            resolution = enumOr(prefs.getString(KEY_RESOLUTION, null), d.resolution),
            aspect = enumOr(prefs.getString(KEY_ASPECT, null), d.aspect),
            framing = enumOr(prefs.getString(KEY_FRAMING, null), d.framing),
            zoom = prefs.getFloat(KEY_ZOOM, d.zoom),
            cropX = prefs.getFloat(KEY_CROP_X, d.cropX),
            cropY = prefs.getFloat(KEY_CROP_Y, d.cropY),
            deflicker = prefs.getBoolean(KEY_DEFLICKER, d.deflicker),
            joinWithoutAudio = prefs.getBoolean(KEY_JOIN_MUTE, d.joinWithoutAudio),
        ).sanitized()
    }

    fun save(options: StudioOptions) {
        prefs.edit {
            putInt(KEY_FPS, options.fps)
            putString(KEY_RESOLUTION, options.resolution.name)
            putString(KEY_ASPECT, options.aspect.name)
            putString(KEY_FRAMING, options.framing.name)
            putFloat(KEY_ZOOM, options.zoom)
            putFloat(KEY_CROP_X, options.cropX)
            putFloat(KEY_CROP_Y, options.cropY)
            putBoolean(KEY_DEFLICKER, options.deflicker)
            putBoolean(KEY_JOIN_MUTE, options.joinWithoutAudio)
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback

    private companion object {
        const val KEY_FPS = "fps"
        const val KEY_RESOLUTION = "resolution"
        const val KEY_ASPECT = "aspect"
        const val KEY_FRAMING = "framing"
        const val KEY_ZOOM = "zoom"
        const val KEY_CROP_X = "crop_x"
        const val KEY_CROP_Y = "crop_y"
        const val KEY_DEFLICKER = "deflicker"
        const val KEY_JOIN_MUTE = "join_without_audio"
    }
}
