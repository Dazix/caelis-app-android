package com.dazix.caelis.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.dazix.caelis.domain.solar.RiseSetGlyph

/**
 * The app's own icon set, drawn in code.
 *
 * `androidx.compose.material:material-icons-*` is deliberately not a dependency: the generic glyph
 * set has nothing for "interval ramp" or "shutter", and the handful of shapes this app needs are
 * plain geometry. Every icon is authored on the same 24dp grid with the same 2.2dp stroke, so the set
 * reads as one family rather than as glyphs borrowed from three places.
 *
 * All of them are monochrome and are tinted by `Icon`, which is what keeps them correct in both
 * colour schemes. None of them ever carries meaning on its own: every icon-only control in the app
 * has a content description, and every icon next to a label is passed `contentDescription = null` so
 * a screen reader reads the label once instead of twice.
 */
object AppIcons {

    private const val STROKE = 2.2f

    /** Shutter: what the app actually presses. Stands for the session as a whole. */
    val Session: ImageVector by lazy {
        icon("Session") {
            strokePath {
                circle(centerX = 12f, centerY = 12f, radius = 8.4f)
            }
            fillPath {
                circle(centerX = 12f, centerY = 12f, radius = 3.6f)
            }
        }
    }

    /** Two sliders with their thumbs: the session's numbers. */
    val Setup: ImageVector by lazy {
        icon("Setup") {
            strokePath {
                moveTo(3.4f, 8.5f)
                lineTo(20.6f, 8.5f)
                moveTo(3.4f, 15.5f)
                lineTo(20.6f, 15.5f)
            }
            fillPath {
                circle(centerX = 9f, centerY = 8.5f, radius = 3f)
                circle(centerX = 16f, centerY = 15.5f, radius = 3f)
            }
        }
    }

    /** A rising interval: the ramp curve, with the two intervals it runs between marked. */
    val Ramp: ImageVector by lazy {
        icon("Ramp") {
            strokePath {
                moveTo(3.6f, 18.4f)
                lineTo(9.4f, 14.2f)
                lineTo(15f, 9.2f)
                lineTo(20.4f, 5.6f)
            }
            fillPath {
                circle(centerX = 3.6f, centerY = 18.4f, radius = 2.1f)
                circle(centerX = 20.4f, centerY = 5.6f, radius = 2.1f)
            }
        }
    }

    /** The universal "and the rest": the optional per-device extras. */
    val Advanced: ImageVector by lazy {
        icon("Advanced") {
            fillPath {
                circle(centerX = 5.2f, centerY = 12f, radius = 2f)
                circle(centerX = 12f, centerY = 12f, radius = 2f)
                circle(centerX = 18.8f, centerY = 12f, radius = 2f)
            }
        }
    }

    /** Three vertical dots: overflow / options menu. */
    val MoreVert: ImageVector by lazy {
        icon("MoreVert") {
            fillPath {
                circle(centerX = 12f, centerY = 5.2f, radius = 2f)
                circle(centerX = 12f, centerY = 12f, radius = 2f)
                circle(centerX = 12f, centerY = 18.8f, radius = 2f)
            }
        }
    }

    /** A shield: everything the system has to allow before the app can tap. */
    val Permissions: ImageVector by lazy {
        icon("Permissions") {
            fillPath {
                moveTo(12f, 2.2f)
                lineTo(4f, 5.4f)
                lineTo(4f, 11.6f)
                curveTo(4f, 16.6f, 8f, 20.8f, 12f, 22f)
                curveTo(16f, 20.8f, 20f, 16.6f, 20f, 11.6f)
                lineTo(20f, 5.4f)
                close()
            }
        }
    }

    /** Start. */
    val Play: ImageVector by lazy {
        icon("Play") {
            fillPath {
                moveTo(8f, 5.2f)
                lineTo(19f, 12f)
                lineTo(8f, 18.8f)
                close()
            }
        }
    }

    /** Stop: a rounded square, so it is not mistaken for the shutter. */
    val Stop: ImageVector by lazy {
        icon("Stop") {
            fillPath {
                moveTo(8.4f, 6.6f)
                lineTo(15.6f, 6.6f)
                arcTo(1.8f, 1.8f, 0f, false, true, 17.4f, 8.4f)
                lineTo(17.4f, 15.6f)
                arcTo(1.8f, 1.8f, 0f, false, true, 15.6f, 17.4f)
                lineTo(8.4f, 17.4f)
                arcTo(1.8f, 1.8f, 0f, false, true, 6.6f, 15.6f)
                lineTo(6.6f, 8.4f)
                arcTo(1.8f, 1.8f, 0f, false, true, 8.4f, 6.6f)
                close()
            }
        }
    }

    /** Pause. */
    val Pause: ImageVector by lazy {
        icon("Pause") {
            fillPath {
                roundedBar(left = 7.4f, right = 10.6f)
                roundedBar(left = 13.4f, right = 16.6f)
            }
        }
    }

    /** A crosshair: designating where on screen the shutter is. */
    val Calibrate: ImageVector by lazy {
        icon("Calibrate") {
            strokePath {
                circle(centerX = 12f, centerY = 12f, radius = 6f)
                moveTo(12f, 1.8f)
                lineTo(12f, 5f)
                moveTo(12f, 19f)
                lineTo(12f, 22.2f)
                moveTo(1.8f, 12f)
                lineTo(5f, 12f)
                moveTo(19f, 12f)
                lineTo(22.2f, 12f)
            }
            fillPath {
                circle(centerX = 12f, centerY = 12f, radius = 1.8f)
            }
        }
    }

    /** Up one level. The app has exactly one level, so this is always "back to the session". */
    val Back: ImageVector by lazy {
        icon("Back") {
            strokePath {
                moveTo(14.6f, 4.8f)
                lineTo(7.4f, 12f)
                lineTo(14.6f, 19.2f)
            }
        }
    }

    /** Forward: next day arrow. */
    val Forward: ImageVector by lazy {
        icon("Forward") {
            strokePath {
                moveTo(9.4f, 4.8f)
                lineTo(16.6f, 12f)
                lineTo(9.4f, 19.2f)
            }
        }
    }

    /** Downward chevron: expandable accordion / section. */
    val ExpandMore: ImageVector by lazy {
        icon("ExpandMore") {
            strokePath {
                moveTo(4.8f, 9.4f)
                lineTo(12f, 16.6f)
                lineTo(19.2f, 9.4f)
            }
        }
    }

    /** Granted / verified. Always paired with words, never the only signal. */
    val Check: ImageVector by lazy {
        icon("Check") {
            strokePath {
                moveTo(4.8f, 12.6f)
                lineTo(9.6f, 17.4f)
                lineTo(19.2f, 6.6f)
            }
        }
    }

    /** Still missing. Always paired with words, never the only signal. */
    val Pending: ImageVector by lazy {
        icon("Pending") {
            strokePath {
                circle(centerX = 12f, centerY = 12f, radius = 8.4f)
                moveTo(12f, 7.4f)
                lineTo(12f, 13f)
            }
            fillPath {
                circle(centerX = 12f, centerY = 16.4f, radius = 1.3f)
            }
        }
    }

    /** Empties the search field. */
    val Clear: ImageVector by lazy {
        icon("Clear") {
            strokePath {
                moveTo(7f, 7f)
                lineTo(17f, 17f)
                moveTo(17f, 7f)
                lineTo(7f, 17f)
            }
        }
    }

    /** Magnifying glass search icon. */
    val Search: ImageVector by lazy {
        icon("Search") {
            strokePath {
                circle(centerX = 10.5f, centerY = 10.5f, radius = 5.8f)
                moveTo(14.8f, 14.8f)
                lineTo(20.2f, 20.2f)
            }
        }
    }

    /** Star icon for single celestial bodies. */
    val Star: ImageVector by lazy {
        icon("Star") {
            strokePath {
                moveTo(12f, 3.2f)
                lineTo(14.7f, 8.7f)
                lineTo(20.8f, 9.6f)
                lineTo(16.4f, 13.9f)
                lineTo(17.4f, 20.0f)
                lineTo(12f, 17.1f)
                lineTo(6.6f, 20.0f)
                lineTo(7.6f, 13.9f)
                lineTo(3.2f, 9.6f)
                lineTo(9.3f, 8.7f)
                close()
            }
        }
    }

    /** 4-pointed celestial sparkle for asterisms and specials. */
    val AutoAwesome: ImageVector by lazy {
        icon("AutoAwesome") {
            strokePath {
                moveTo(12f, 3.2f)
                lineTo(13.8f, 9.8f)
                lineTo(20.4f, 11.6f)
                lineTo(13.8f, 13.4f)
                lineTo(12f, 20.0f)
                lineTo(10.2f, 13.4f)
                lineTo(3.6f, 11.6f)
                lineTo(10.2f, 9.8f)
                close()
            }
        }
    }

    /** Sensors: phone tracking wave icon. */
    val Sensors: ImageVector by lazy {
        icon("Sensors") {
            strokePath {
                circle(centerX = 12f, centerY = 12f, radius = 3f)
                // Left wave
                moveTo(6.5f, 6.5f)
                arcTo(7.8f, 7.8f, 0f, false, false, 6.5f, 17.5f)
                // Right wave
                moveTo(17.5f, 6.5f)
                arcTo(7.8f, 7.8f, 0f, false, true, 17.5f, 17.5f)
            }
        }
    }

    /** Sun: central disk with 8 radial rays. Stands for Sun AR and solar trajectory. */
    val Sun: ImageVector by lazy {
        icon("Sun") {
            strokePath {
                circle(centerX = 12f, centerY = 12f, radius = 4.2f)
                // 4 cardinal rays
                moveTo(12f, 2.4f); lineTo(12f, 5.2f)
                moveTo(12f, 18.8f); lineTo(12f, 21.6f)
                moveTo(2.4f, 12f); lineTo(5.2f, 12f)
                moveTo(18.8f, 12f); lineTo(21.6f, 12f)
                // 4 diagonal rays
                moveTo(5.2f, 5.2f); lineTo(7.2f, 7.2f)
                moveTo(16.8f, 16.8f); lineTo(18.8f, 18.8f)
                moveTo(5.2f, 18.8f); lineTo(7.2f, 16.8f)
                moveTo(16.8f, 7.2f); lineTo(18.8f, 5.2f)
            }
        }
    }

    /** Sun and Moon: radiant sun combined with a crescent moon. Stands for Sun & Moon AR ephemeris. */
    val SunAndMoon: ImageVector by lazy {
        icon("SunAndMoon") {
            strokePath {
                // Sun disk arc on left
                moveTo(11.5f, 7.8f)
                arcTo(4.2f, 4.2f, 0f, false, false, 11.5f, 16.2f)

                // Sun rays (left, top-left, bottom-left, top, bottom)
                moveTo(2.4f, 12f); lineTo(5.2f, 12f)
                moveTo(4.8f, 5.5f); lineTo(7.0f, 7.5f)
                moveTo(4.8f, 18.5f); lineTo(7.0f, 16.5f)
                moveTo(10.5f, 2.4f); lineTo(10.5f, 5.0f)
                moveTo(10.5f, 19.0f); lineTo(10.5f, 21.6f)

                // Crescent Moon on right
                moveTo(13.5f, 4.8f)
                arcTo(7.4f, 7.4f, 0f, false, true, 13.5f, 19.2f)
                arcTo(5.2f, 5.2f, 0f, false, false, 13.5f, 4.8f)
            }
        }
    }

    /**
     * Sunrise: half a sun with rays on the horizon, an arrow above it pointing up. The same drawing
     * as Sun AR's rise pill ([RiseSetGlyph]), so a rise looks the same everywhere in the app.
     */
    val Sunrise: ImageVector by lazy { riseSetIcon("Sunrise", RiseSetGlyph.Body.SUN, rising = true) }

    /** Sunset: as [Sunrise], with the arrow pointing down. */
    val Sunset: ImageVector by lazy { riseSetIcon("Sunset", RiseSetGlyph.Body.SUN, rising = false) }

    /** The sun alone, disc and eight rays, drawn like the rise/set icons. Heads the Sun section of Sun AR's sheet. */
    val SunBody: ImageVector by lazy { glyphIcon("SunBody", RiseSetGlyph.body(RiseSetGlyph.Body.SUN)) }

    /** The moon alone, the filled crescent of the rise/set pills. Heads the Moon section of Sun AR's sheet. */
    val MoonBody: ImageVector by lazy { glyphIcon("MoonBody", RiseSetGlyph.body(RiseSetGlyph.Body.MOON)) }

    private fun riseSetIcon(name: String, body: RiseSetGlyph.Body, rising: Boolean): ImageVector =
        glyphIcon(name, RiseSetGlyph.drawing(body, rising))

    private fun glyphIcon(name: String, drawing: RiseSetGlyph.Drawing): ImageVector =
        icon(name) {
            if (drawing.strokes.isNotEmpty()) {
                path(
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = RiseSetGlyph.STROKE,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    for (stroke in drawing.strokes) polyline(stroke)
                }
            }
            if (drawing.fills.isNotEmpty()) {
                fillPath {
                    for (outline in drawing.fills) {
                        polyline(outline)
                        close()
                    }
                }
            }
        }

    private fun androidx.compose.ui.graphics.vector.PathBuilder.polyline(points: FloatArray) {
        moveTo(points[0], points[1])
        for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
    }

    /** Explore / Compass: Material Design 3 Explore (Filled) icon. */
    val Explore: ImageVector by lazy {
        icon("Explore") {
            fillPath(pathFillType = PathFillType.EvenOdd) {
                circle(centerX = 12f, centerY = 12f, radius = 10f)
                moveTo(14.19f, 14.19f)
                lineTo(6f, 18f)
                lineTo(9.81f, 9.81f)
                lineTo(18f, 6f)
                close()
                circle(centerX = 12f, centerY = 12f, radius = 1.1f)
            }
        }
    }

    val Compass: ImageVector get() = Explore

    /** Calendar: date selector icon. */
    val Calendar: ImageVector by lazy {
        icon("Calendar") {
            strokePath {
                moveTo(5.2f, 4.6f)
                lineTo(18.8f, 4.6f)
                arcTo(2f, 2f, 0f, false, true, 20.8f, 6.6f)
                lineTo(20.8f, 19.4f)
                arcTo(2f, 2f, 0f, false, true, 18.8f, 21.4f)
                lineTo(5.2f, 21.4f)
                arcTo(2f, 2f, 0f, false, true, 3.2f, 19.4f)
                lineTo(3.2f, 6.6f)
                arcTo(2f, 2f, 0f, false, true, 5.2f, 4.6f)
                close()
                moveTo(3.2f, 9.6f)
                lineTo(20.8f, 9.6f)
                moveTo(7.8f, 2.4f); lineTo(7.8f, 5.6f)
                moveTo(16.2f, 2.4f); lineTo(16.2f, 5.6f)
            }
        }
    }

    /** Schedule: clock / time selector icon. */
    val Schedule: ImageVector by lazy {
        icon("Schedule") {
            strokePath {
                circle(centerX = 12f, centerY = 12f, radius = 8.8f)
                moveTo(12f, 7.2f)
                lineTo(12f, 12f)
                lineTo(15.5f, 14f)
            }
        }
    }

    /** ScreenAwake: display stay awake / keep screen on icon. */
    val ScreenAwake: ImageVector by lazy {
        icon("ScreenAwake") {
            strokePath {
                moveTo(6.5f, 3.2f)
                lineTo(17.5f, 3.2f)
                arcTo(2f, 2f, 0f, false, true, 19.5f, 5.2f)
                lineTo(19.5f, 18.8f)
                arcTo(2f, 2f, 0f, false, true, 17.5f, 20.8f)
                lineTo(6.5f, 20.8f)
                arcTo(2f, 2f, 0f, false, true, 4.5f, 18.8f)
                lineTo(4.5f, 5.2f)
                arcTo(2f, 2f, 0f, false, true, 6.5f, 3.2f)
                close()
                moveTo(10.5f, 18.2f)
                lineTo(13.5f, 18.2f)
                // Center glow / sun dot
                moveTo(12f, 10f)
                lineTo(12f, 14f)
                moveTo(10f, 12f)
                lineTo(14f, 12f)
            }
        }
    }

    /** RedVision: night vision / astrophotography eye mode icon. */
    val RedVision: ImageVector by lazy {
        icon("RedVision") {
            strokePath {
                moveTo(2.5f, 12f)
                curveTo(4.5f, 7.5f, 8f, 5f, 12f, 5f)
                curveTo(16f, 5f, 19.5f, 7.5f, 21.5f, 12f)
                curveTo(19.5f, 16.5f, 16f, 19f, 12f, 19f)
                curveTo(8f, 19f, 4.5f, 16.5f, 2.5f, 12f)
                close()
                circle(centerX = 12f, centerY = 12f, radius = 3.2f)
            }
        }
    }

    /** ResetCalibration: undo / refresh calibration rotation icon. */
    val ResetCalibration: ImageVector by lazy {
        icon("ResetCalibration") {
            strokePath {
                moveTo(3.5f, 12f)
                arcTo(8.5f, 8.5f, 0f, true, true, 6f, 18f)
                moveTo(3.5f, 7f)
                lineTo(3.5f, 12f)
                lineTo(8.5f, 12f)
            }
        }
    }

    /** NightSky: starry night / constellation icon for Night Sky AR. */
    val NightSky: ImageVector by lazy {
        icon("NightSky") {
            strokePath {
                moveTo(12f, 3.2f)
                lineTo(13.8f, 9.8f)
                lineTo(20.4f, 11.6f)
                lineTo(13.8f, 13.4f)
                lineTo(12f, 20.0f)
                lineTo(10.2f, 13.4f)
                lineTo(3.6f, 11.6f)
                lineTo(10.2f, 9.8f)
                close()
            }
            fillPath {
                circle(centerX = 18.5f, centerY = 5.5f, radius = 1.4f)
                circle(centerX = 5.5f, centerY = 18.5f, radius = 1.2f)
            }
        }
    }

    /** MilkyWay: galactic core and spiral nebular arms. */
    val MilkyWay: ImageVector by lazy {
        icon("MilkyWay") {
            strokePath {
                // Outer elliptical spiral orbit
                moveTo(3.6f, 15.6f)
                curveTo(2.4f, 10.8f, 6.2f, 5.2f, 12f, 4.4f)
                curveTo(17.8f, 3.6f, 21.6f, 8.4f, 20.4f, 13.2f)
                curveTo(19.2f, 17.6f, 14.8f, 20.4f, 9.8f, 19.6f)
                // Inner galactic swirl
                moveTo(8.5f, 14.2f)
                curveTo(7.8f, 11.4f, 9.6f, 8.4f, 13.2f, 8.0f)
                curveTo(16.4f, 7.6f, 17.8f, 10.4f, 16.6f, 13.4f)
            }
            fillPath {
                // Radiant galactic nucleus
                circle(centerX = 12.6f, centerY = 11.8f, radius = 2.0f)
            }
        }
    }

    /** Planet: celestial sphere with ring. */
    val Planet: ImageVector by lazy {
        icon("Planet") {
            strokePath {
                circle(centerX = 12f, centerY = 12f, radius = 6.2f)
                moveTo(2.4f, 13.6f)
                curveTo(5.2f, 17.2f, 18.8f, 17.2f, 21.6f, 13.6f)
                moveTo(21.6f, 10.4f)
                curveTo(18.8f, 6.8f, 5.2f, 6.8f, 2.4f, 10.4f)
            }
        }
    }

    /** DeepSky: Messier deep sky objects (galaxies, nebulae, star clusters). */
    val DeepSky: ImageVector by lazy {
        icon("DeepSky") {
            strokePath {
                moveTo(12f, 4.5f)
                curveTo(18.5f, 4.5f, 21.5f, 8.5f, 21.5f, 12f)
                curveTo(21.5f, 15.5f, 18.5f, 19.5f, 12f, 19.5f)
                curveTo(5.5f, 19.5f, 2.5f, 15.5f, 2.5f, 12f)
                curveTo(2.5f, 8.5f, 5.5f, 4.5f, 12f, 4.5f)
                close()
            }
            fillPath {
                circle(centerX = 12f, centerY = 12f, radius = 2.4f)
                circle(centerX = 7.5f, centerY = 10.5f, radius = 1.0f)
                circle(centerX = 16.5f, centerY = 13.5f, radius = 1.0f)
            }
        }
    }

    /** MeteorShower: shooting star / meteor shower radiant glyph. */
    val MeteorShower: ImageVector by lazy {
        icon("MeteorShower") {
            strokePath {
                circle(centerX = 12f, centerY = 12f, radius = 3.5f)
                moveTo(16f, 8f)
                lineTo(21f, 3f)
                moveTo(8f, 16f)
                lineTo(3f, 21f)
                moveTo(8f, 8f)
                lineTo(3f, 3f)
                moveTo(16f, 16f)
                lineTo(21f, 21f)
            }
            fillPath {
                circle(centerX = 12f, centerY = 12f, radius = 1.8f)
            }
        }
    }

    /** History: clock circle with clock hands. */
    val History: ImageVector by lazy {
        icon("History") {
            strokePath {
                circle(centerX = 12f, centerY = 12f, radius = 8.4f)
                moveTo(12f, 7.5f)
                lineTo(12f, 12f)
                lineTo(15.2f, 14.2f)
            }
        }
    }

    /** Planetarium: opaque celestial dome / sky vault. */
    val Planetarium: ImageVector by lazy {
        icon("Planetarium") {
            strokePath {
                // Horizon baseline
                moveTo(3.0f, 18.5f)
                lineTo(21.0f, 18.5f)
                // Half-dome arc
                moveTo(4.2f, 18.5f)
                arcTo(7.8f, 7.8f, 0f, false, true, 19.8f, 18.5f)
                // Dome zenith finial / compass needle
                moveTo(12.0f, 6.0f)
                lineTo(12.0f, 10.5f)
            }
            fillPath {
                circle(centerX = 12.0f, centerY = 4.8f, radius = 1.2f)
            }
        }
    }

    /** Atmosphere: atmospheric horizon glow layer. */
    val Atmosphere: ImageVector by lazy {
        icon("Atmosphere") {
            strokePath {
                // Horizon baseline
                moveTo(2.4f, 18.5f)
                lineTo(21.6f, 18.5f)
                // Outer atmospheric scattering glow dome
                moveTo(3.8f, 18.5f)
                arcTo(8.2f, 8.2f, 0f, false, true, 20.2f, 18.5f)
                // Inner dense atmospheric glow layer
                moveTo(7.2f, 18.5f)
                arcTo(4.8f, 4.8f, 0f, false, true, 16.8f, 18.5f)
            }
        }
    }

    /** Constellation: connected stellar nodes / stick figures. */
    val Constellation: ImageVector by lazy {
        icon("Constellation") {
            strokePath {
                moveTo(5.2f, 16.8f)
                lineTo(9.6f, 9.2f)
                lineTo(16.4f, 12.4f)
                lineTo(19.2f, 5.6f)
            }
            fillPath {
                circle(centerX = 5.2f, centerY = 16.8f, radius = 2.0f)
                circle(centerX = 9.6f, centerY = 9.2f, radius = 2.0f)
                circle(centerX = 16.4f, centerY = 12.4f, radius = 2.0f)
                circle(centerX = 19.2f, centerY = 5.6f, radius = 2.0f)
            }
        }
    }

    /** Map: a folded three-panel map, the virtual places button on both AR screens. */
    val Map: ImageVector by lazy {
        icon("Map") {
            strokePath {
                moveTo(3.5f, 6.2f)
                lineTo(9.0f, 4.0f)
                lineTo(15.0f, 6.2f)
                lineTo(20.5f, 4.0f)
                lineTo(20.5f, 17.8f)
                lineTo(15.0f, 20.0f)
                lineTo(9.0f, 17.8f)
                lineTo(3.5f, 20.0f)
                close()
                moveTo(9.0f, 4.0f)
                lineTo(9.0f, 17.8f)
                moveTo(15.0f, 6.2f)
                lineTo(15.0f, 20.0f)
            }
        }
    }

    /** Place: a map pin, marking a virtual place in the location chip and the places list. */
    val Place: ImageVector by lazy {
        icon("Place") {
            strokePath {
                moveTo(12f, 21.0f)
                curveTo(12f, 21.0f, 5.0f, 14.6f, 5.0f, 9.6f)
                arcTo(7.0f, 7.0f, 0f, false, true, 19.0f, 9.6f)
                curveTo(19.0f, 14.6f, 12f, 21.0f, 12f, 21.0f)
                close()
                circle(centerX = 12f, centerY = 9.6f, radius = 2.4f)
            }
        }
    }

    /** Edit: a pencil, editing a saved place. */
    val Edit: ImageVector by lazy {
        icon("Edit") {
            strokePath {
                moveTo(4.0f, 20.0f)
                lineTo(4.6f, 16.2f)
                lineTo(15.8f, 5.0f)
                lineTo(19.0f, 8.2f)
                lineTo(7.8f, 19.4f)
                close()
                moveTo(13.4f, 7.4f)
                lineTo(16.6f, 10.6f)
            }
        }
    }

    /** Landscape: photorealistic ground landscape and tree line horizon. */
    val Landscape: ImageVector by lazy {
        icon("Landscape") {
            strokePath {
                // Rolling hills / ground horizon line
                moveTo(2.4f, 18.0f)
                curveTo(6.5f, 14.5f, 10.5f, 17.5f, 15.0f, 14.5f)
                lineTo(21.6f, 17.0f)
                // Tree crown silhouette
                moveTo(15.0f, 8.5f)
                lineTo(12.5f, 14.5f)
                lineTo(17.5f, 14.5f)
                close()
                // Distant smaller tree
                moveTo(8.5f, 11.5f)
                lineTo(6.5f, 15.5f)
                lineTo(10.5f, 15.5f)
                close()
            }
        }
    }

    /** Grid: 3D celestial coordinate grid and horizon. */
    val Grid: ImageVector by lazy {
        icon("Grid") {
            strokePath {
                circle(centerX = 12f, centerY = 12f, radius = 8.5f)
                moveTo(3.5f, 12f)
                lineTo(20.5f, 12f)
                moveTo(12f, 3.5f)
                lineTo(12f, 20.5f)
                moveTo(6.0f, 6.0f)
                curveTo(9.5f, 8.5f, 14.5f, 8.5f, 18.0f, 6.0f)
                moveTo(6.0f, 18.0f)
                curveTo(9.5f, 15.5f, 14.5f, 15.5f, 18.0f, 18.0f)
            }
        }
    }

    /** Layers: stacked planes for toggling visual overlays. */
    val Layers: ImageVector by lazy {
        icon("Layers") {
            strokePath {
                // Top layer plane
                moveTo(12f, 3.5f)
                lineTo(20.5f, 7.8f)
                lineTo(12f, 12.1f)
                lineTo(3.5f, 7.8f)
                close()
                // Middle layer plane
                moveTo(3.5f, 12.2f)
                lineTo(12f, 16.5f)
                lineTo(20.5f, 12.2f)
                // Bottom layer plane
                moveTo(3.5f, 16.6f)
                lineTo(12f, 20.9f)
                lineTo(20.5f, 16.6f)
            }
        }
    }

    /** Brush: artistic paintbrush for constellation illustrations / artwork layer. */
    val Brush: ImageVector by lazy {
        icon("Brush") {
            strokePath {
                // Brush handle & ferrule
                moveTo(20.5f, 3.5f)
                lineTo(14.5f, 9.5f)
                lineTo(12.5f, 7.5f)
                lineTo(18.5f, 1.5f)
                close()
                // Brush bristle body
                moveTo(14.5f, 9.5f)
                curveTo(12.5f, 11.5f, 9.0f, 13.0f, 6.0f, 15.0f)
                curveTo(4.5f, 16.0f, 3.5f, 18.0f, 3.5f, 20.5f)
                curveTo(6.0f, 20.5f, 8.0f, 19.5f, 9.0f, 18.0f)
                curveTo(11.0f, 15.0f, 12.5f, 11.5f, 14.5f, 9.5f)
            }
            fillPath {
                circle(centerX = 5.5f, centerY = 18.5f, radius = 1.6f)
            }
        }
    }

    /** Settings: classic gear / cog icon for global application configuration. */
    val Settings: ImageVector by lazy {
        icon("Settings") {
            fillPath {
                moveTo(19.14f, 12.94f)
                curveTo(19.18f, 12.64f, 19.2f, 12.33f, 19.2f, 12f)
                curveTo(19.2f, 11.68f, 19.18f, 11.36f, 19.13f, 11.06f)
                lineTo(21.16f, 9.48f)
                curveTo(21.34f, 9.34f, 21.39f, 9.07f, 21.28f, 8.87f)
                lineTo(19.36f, 5.55f)
                curveTo(19.24f, 5.33f, 18.99f, 5.26f, 18.77f, 5.33f)
                lineTo(16.38f, 6.29f)
                curveTo(15.88f, 5.91f, 15.35f, 5.59f, 14.76f, 5.35f)
                lineTo(14.4f, 2.81f)
                curveTo(14.36f, 2.57f, 14.16f, 2.4f, 13.92f, 2.4f)
                lineTo(10.08f, 2.4f)
                curveTo(9.84f, 2.4f, 9.65f, 2.57f, 9.61f, 2.81f)
                lineTo(9.25f, 5.35f)
                curveTo(8.66f, 5.59f, 8.12f, 5.92f, 7.63f, 6.29f)
                lineTo(5.24f, 5.33f)
                curveTo(5.02f, 5.25f, 4.77f, 5.33f, 4.65f, 5.55f)
                lineTo(2.73f, 8.87f)
                curveTo(2.61f, 9.08f, 2.65f, 9.34f, 2.85f, 9.48f)
                lineTo(4.88f, 11.06f)
                curveTo(4.83f, 11.36f, 4.8f, 11.69f, 4.8f, 12f)
                curveTo(4.8f, 12.31f, 4.83f, 12.64f, 4.88f, 12.94f)
                lineTo(2.85f, 14.52f)
                curveTo(2.67f, 14.66f, 2.62f, 14.93f, 2.73f, 15.13f)
                lineTo(4.65f, 18.45f)
                curveTo(4.77f, 18.67f, 5.02f, 18.74f, 5.24f, 18.67f)
                lineTo(7.63f, 17.71f)
                curveTo(8.13f, 18.09f, 8.66f, 18.41f, 9.25f, 18.65f)
                lineTo(9.61f, 21.19f)
                curveTo(9.66f, 21.43f, 9.85f, 21.6f, 10.09f, 21.6f)
                lineTo(13.93f, 21.6f)
                curveTo(14.17f, 21.6f, 14.37f, 21.43f, 14.4f, 21.19f)
                lineTo(14.76f, 18.65f)
                curveTo(15.35f, 18.41f, 15.89f, 18.09f, 16.38f, 17.71f)
                lineTo(18.77f, 18.67f)
                curveTo(18.99f, 18.75f, 19.24f, 18.67f, 19.36f, 18.45f)
                lineTo(21.28f, 15.13f)
                curveTo(21.39f, 14.91f, 21.34f, 14.66f, 21.15f, 14.52f)
                lineTo(19.14f, 12.94f)
                close()
                // Center hole
                moveTo(12f, 15.5f)
                curveTo(10.07f, 15.5f, 8.5f, 13.93f, 8.5f, 12f)
                curveTo(8.5f, 10.07f, 10.07f, 8.5f, 12f, 8.5f)
                curveTo(13.93f, 8.5f, 15.5f, 10.07f, 15.5f, 12f)
                curveTo(15.5f, 13.93f, 13.93f, 15.5f, 12f, 15.5f)
                close()
            }
        }
    }

    /** Focus Area / Region: circle with center crosshair / target. */
    val FocusArea: ImageVector by lazy {
        icon("FocusArea") {
            strokePath {
                circle(centerX = 12f, centerY = 12f, radius = 8.5f)
                moveTo(12f, 2f)
                lineTo(12f, 5.5f)
                moveTo(12f, 18.5f)
                lineTo(12f, 22f)
                moveTo(2f, 12f)
                lineTo(5.5f, 12f)
                moveTo(18.5f, 12f)
                lineTo(22f, 12f)
            }
            fillPath {
                circle(centerX = 12f, centerY = 12f, radius = 2f)
            }
        }
    }

    /** CenterFocus: crosshair targeting / center celestial body in viewport. */
    val CenterFocus: ImageVector by lazy {
        icon("CenterFocus") {
            strokePath {
                // 4 corner brackets
                moveTo(3.5f, 7.5f); lineTo(3.5f, 4.5f); lineTo(6.5f, 4.5f)
                moveTo(17.5f, 4.5f); lineTo(20.5f, 4.5f); lineTo(20.5f, 7.5f)
                moveTo(20.5f, 16.5f); lineTo(20.5f, 19.5f); lineTo(17.5f, 19.5f)
                moveTo(6.5f, 19.5f); lineTo(3.5f, 19.5f); lineTo(3.5f, 16.5f)
            }
            fillPath {
                circle(centerX = 12f, centerY = 12f, radius = 2.4f)
            }
        }
    }

    /** TerrainHorizon: mountain ridge silhouette with base horizon line. */
    val TerrainHorizon: ImageVector by lazy {
        icon("TerrainHorizon") {
            strokePath {
                moveTo(2.4f, 19f)
                lineTo(21.6f, 19f)
                moveTo(3f, 19f)
                lineTo(9f, 9.5f)
                lineTo(13.5f, 15f)
                lineTo(17.5f, 7.5f)
                lineTo(21.5f, 19f)
            }
        }
    }

    /** TerrainLock: locked mountain ridge with target crosshair. */
    val TerrainLock: ImageVector by lazy {
        icon("TerrainLock") {
            strokePath {
                moveTo(2.4f, 19f)
                lineTo(21.6f, 19f)
                moveTo(3f, 19f)
                lineTo(9f, 9.5f)
                lineTo(13.5f, 15f)
                lineTo(17.5f, 7.5f)
                lineTo(21.5f, 19f)
                // Lock shackle and body
                moveTo(10f, 6.5f)
                arcTo(2f, 2f, 0f, false, true, 14f, 6.5f)
                lineTo(14f, 8f)
                lineTo(10f, 8f)
                close()
            }
            fillPath {
                circle(centerX = 12f, centerY = 11.5f, radius = 2.2f)
            }
        }
    }

    /** Trash / Delete: bin with lid and vertical ribs. */
    val Delete: ImageVector by lazy {
        icon("Delete") {
            strokePath {
                // Lid line
                moveTo(3.5f, 6f)
                lineTo(20.5f, 6f)
                // Lid handle
                moveTo(9f, 6f)
                lineTo(9f, 3.5f)
                lineTo(15f, 3.5f)
                lineTo(15f, 6f)
                // Bin body
                moveTo(5.5f, 6f)
                lineTo(6.5f, 19.5f)
                arcTo(1.5f, 1.5f, 0f, false, false, 8f, 21f)
                lineTo(16f, 21f)
                arcTo(1.5f, 1.5f, 0f, false, false, 17.5f, 19.5f)
                lineTo(18.5f, 6f)
                // Vertical ribs
                moveTo(10f, 10f)
                lineTo(10f, 17f)
                moveTo(14f, 10f)
                lineTo(14f, 17f)
            }
        }
    }

    /** Info: circle outline with central 'i' stem and dot. */
    val Info: ImageVector by lazy {
        icon("Info") {
            strokePath {
                circle(centerX = 12f, centerY = 12f, radius = 9f)
                moveTo(12f, 10.5f)
                lineTo(12f, 16.5f)
            }
            fillPath {
                circle(centerX = 12f, centerY = 7.5f, radius = 1.2f)
            }
        }
    }

    /** Warning: triangle outline with an exclamation stem and dot. */
    val Warning: ImageVector by lazy {
        icon("Warning") {
            strokePath {
                moveTo(12f, 3.5f)
                lineTo(21.5f, 20f)
                lineTo(2.5f, 20f)
                close()
                moveTo(12f, 9.5f)
                lineTo(12f, 14f)
            }
            fillPath {
                circle(centerX = 12f, centerY = 16.9f, radius = 1.2f)
            }
        }
    }

    private fun icon(name: String, paths: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(paths).build()

    private fun ImageVector.Builder.fillPath(
        pathFillType: PathFillType = PathFillType.NonZero,
        block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ) = path(fill = SolidColor(Color.Black), pathFillType = pathFillType, pathBuilder = block)

    private fun ImageVector.Builder.strokePath(
        block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ) = path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = STROKE,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    )

    /** A full circle as two half arcs, which is the only exact way to write one in path data. */
    private fun androidx.compose.ui.graphics.vector.PathBuilder.circle(
        centerX: Float,
        centerY: Float,
        radius: Float,
    ) {
        moveTo(centerX, centerY - radius)
        arcTo(radius, radius, 0f, true, true, centerX, centerY + radius)
        arcTo(radius, radius, 0f, true, true, centerX, centerY - radius)
        close()
    }

    /** One of the two pause bars: full-height, rounded ends. */
    private fun androidx.compose.ui.graphics.vector.PathBuilder.roundedBar(
        left: Float,
        right: Float,
    ) {
        val radius = (right - left) / 2f
        moveTo(left, 7.6f)
        arcTo(radius, radius, 0f, true, true, right, 7.6f)
        lineTo(right, 16.4f)
        arcTo(radius, radius, 0f, true, true, left, 16.4f)
        close()
    }
}
