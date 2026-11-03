package com.samosaai.masala.ui.character

import androidx.compose.ui.graphics.Color

/**
 * Sam's twelve faces (expressions board): eyes and brows do most of the work, each face has one job.
 * Path data on the 200 × 200 grid, built with the same helpers as the design so the shapes match.
 */
enum class Expression { HAPPY, DELIGHTED, CURIOUS, THINKING, LISTENING, SURPRISED, WINK, PROUD, SORRY, CONFUSED, SLEEPY, FOCUSED }

/** One face: what changes from the resting Sam. Blank paths draw nothing. */
data class SamFace(
    val tilt: Float = 0f,
    val leaf: String = SamArt.LEAF,
    val leafFill: Color = SamArt.Leaf,
    val bodyFill: Color = SamArt.Gold,
    val shadeFill: Color = SamArt.Facet,
    val whites: String = SamFaces.whites(),
    val pupils: String = SamFaces.pupils(),
    val shine: String = SamFaces.shine(),
    val lids: String = "",
    val brows: String = "",
    val mouth: String = "M90 130 Q100 139 110 130",
    val mouthFilled: Boolean = false,
    val cheek: Float = 0.55f,
    val extra: String = "",
    val extraFill: Color? = null,
    val extraStroke: Color? = null,
)

object SamFaces {
    private fun n(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

    /** An ellipse as path data (the design's `ell`). */
    fun ell(cx: Float, cy: Float, rx: Float, ry: Float) =
        "M${n(cx - rx)} ${n(cy)}a${n(rx)} ${n(ry)} 0 1 0 ${n(2 * rx)} 0a${n(rx)} ${n(ry)} 0 1 0 ${n(-2 * rx)} 0"

    fun two(cx1: Float, cx2: Float, cy: Float, rx: Float, ry: Float) = ell(cx1, cy, rx, ry) + ell(cx2, cy, rx, ry)
    fun whites(dy: Float = 0f, rx: Float = 13f, ry: Float = 16f) = two(80f, 120f, 104 + dy, rx, ry)
    fun pupils(dx: Float = 2f, dy: Float = 3f, r: Float = 7f) = two(80 + dx, 120 + dx, 104 + dy, r, r)
    fun shine(dx: Float = 5f, dy: Float = -1f) = two(80 + dx, 120 + dx, 104 + dy, 2.5f, 2.5f)

    private fun star(x: Float, y: Float, s: Float): String {
        val q = s * 0.25f
        return "M${n(x)} ${n(y - s)} L${n(x + q)} ${n(y - q)} L${n(x + s)} ${n(y)} L${n(x + q)} ${n(y + q)} L${n(x)} ${n(y + s)} " +
            "L${n(x - q)} ${n(y + q)} L${n(x - s)} ${n(y)} L${n(x - q)} ${n(y - q)}Z"
    }

    const val DROOP = "M100 26 Q84 30 76 20 Q90 12 100 26Z"
    private val Cream = Color(0xFFF6E3B4)
    private val Indigo = Color(0xFF9DB4FF)
    private val Mint = Color(0xFF8FE3B5)

    val CHEEKS = two(64f, 136f, 125f, 7f, 4.5f)

    fun of(e: Expression): SamFace = when (e) {
        Expression.HAPPY -> SamFace()
        Expression.DELIGHTED -> SamFace(tilt = -4f, whites = "", pupils = "", shine = "", lids = "M68 108 Q80 94 92 108M108 108 Q120 94 132 108", mouth = "M86 127 Q100 148 114 127Z", mouthFilled = true, cheek = 0.85f)
        Expression.CURIOUS -> SamFace(tilt = 8f, pupils = pupils(5f, 0f), shine = shine(8f, -3f), brows = "M68 82 Q80 72 92 80M110 88 L130 88", mouth = ell(102f, 133f, 4f, 5f), mouthFilled = true)
        Expression.THINKING -> SamFace(
            tilt = -6f, pupils = pupils(6f, -6f), shine = shine(9f, -9f), brows = "M70 86 Q80 84 90 86M110 82 Q120 76 130 82", mouth = "M92 134 L108 132",
            extra = ell(150f, 58f, 4f, 4f) + ell(162f, 44f, 5f, 5f) + ell(176f, 28f, 6f, 6f), extraFill = Cream,
        )
        Expression.LISTENING -> SamFace(
            tilt = 6f, whites = whites(-2f, 14f, 18f), pupils = pupils(0f, -2f), shine = shine(3f, -6f), brows = "M68 80 Q80 74 92 80M108 80 Q120 74 132 80",
            mouth = ell(100f, 134f, 5f, 4f), mouthFilled = true, extra = "M178 96 Q186 106 178 116M186 86 Q198 106 186 126", extraStroke = Indigo,
        )
        Expression.SURPRISED -> SamFace(whites = whites(-2f, 15f, 19f), pupils = pupils(0f, -2f, 5f), shine = shine(3f, -7f), brows = "M66 74 Q80 66 94 74M106 74 Q120 66 134 74", mouth = ell(100f, 136f, 7f, 9f), mouthFilled = true)
        Expression.WINK -> SamFace(tilt = -5f, whites = ell(80f, 104f, 13f, 16f), pupils = ell(82f, 107f, 7f, 7f), shine = ell(85f, 103f, 2.5f, 2.5f), lids = "M108 106 Q120 98 132 106", brows = "M108 92 Q120 86 132 90", mouth = "M90 130 Q104 141 114 126")
        Expression.PROUD -> SamFace(tilt = -8f, whites = "", pupils = "", shine = "", lids = "M68 104 Q80 96 92 104M108 104 Q120 96 132 104", mouth = "M88 128 Q100 141 112 128", extra = star(160f, 54f, 14f), extraFill = Color(0xFFF3DCA4))
        Expression.SORRY -> SamFace(
            tilt = 4f, leaf = DROOP, whites = whites(2f, 13f, 15f), pupils = pupils(0f, 4f, 6.5f), shine = shine(3f, 1f),
            brows = "M68 86 Q80 84 90 78M110 78 Q120 84 132 86", mouth = "M90 137 Q100 129 110 137", extra = "M152 66 Q160 81 152 89 Q144 81 152 66Z", extraFill = Indigo,
        )
        Expression.CONFUSED -> SamFace(tilt = 10f, pupils = pupils(3f, 0f), brows = "M68 80 Q80 72 92 80M108 88 L132 84", mouth = "M88 133 Q94 127 100 133 T112 133")
        Expression.SLEEPY -> SamFace(
            leaf = DROOP, leafFill = Color(0xFF5E8F73), bodyFill = Color(0xFFB89B5E), shadeFill = Color(0xFFA98D53),
            whites = two(80f, 120f, 108f, 13f, 8f), pupils = two(82f, 122f, 110f, 6f, 5f), shine = "",
            lids = "M66 104 Q80 100 94 104M106 104 Q120 100 134 104", mouth = "M94 133 Q100 136 106 133", cheek = 0.25f,
            extra = "M146 52 h12 l-12 14 h12M166 34 h8 l-8 10 h8", extraStroke = Mint,
        )
        Expression.FOCUSED -> SamFace(whites = whites(2f, 13f, 11f), pupils = pupils(0f, 5f), shine = shine(3f, 1f), brows = "M66 86 L92 94M108 94 L134 86", mouth = "M92 133 L108 133", cheek = 0.35f)
    }
}
