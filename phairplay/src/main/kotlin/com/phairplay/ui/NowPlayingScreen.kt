package com.phairplay.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.phairplay.R
import com.phairplay.airplay.NowPlayingInfo
import com.phairplay.util.Logger
import com.phairplay.util.SafeBitmapDecoder

/**
 * NowPlayingScreen — full-screen card shown while AirPlay audio plays without video (system audio
 * from a Mac, Apple Music, podcasts). Mirroring shows the video [StreamingScreen] instead; this
 * screen fills the otherwise-black surface for audio-only sessions.
 *
 * Layout (centered): album art (or an AirPlay-glyph placeholder when the sender sends no artwork),
 * track title, artist, album, and a "♪ Audio from <sender>" footer. Built programmatically to match
 * the other overlay views ([PhotoScreen]/[StreamingScreen]).
 */
class NowPlayingScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val artwork: ImageView
    private val titleView: TextView
    private val artistView: TextView
    private val albumView: TextView
    private val senderView: TextView
    private val defaultBackground: Int

    init {
        defaultBackground = color(R.color.background_dark)
        setBackgroundColor(defaultBackground)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        artwork = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ART_SIZE_DP), dp(ART_SIZE_DP))
            scaleType = ImageView.ScaleType.CENTER_CROP
            // Rounded surface so the placeholder glyph (and any non-square art) sits on a card.
            background = GradientDrawable().apply {
                setColor(color(R.color.background_surface))
                cornerRadius = dp(16).toFloat()
            }
            clipToOutline = true
        }

        titleView = textView(TITLE_SP, R.color.text_primary, bold = true).apply {
            setPadding(0, dp(28), 0, 0)
        }
        artistView = textView(ARTIST_SP, R.color.text_secondary).apply {
            setPadding(0, dp(8), 0, 0)
        }
        albumView = textView(ALBUM_SP, R.color.text_tertiary).apply {
            setPadding(0, dp(4), 0, 0)
        }
        senderView = textView(SENDER_SP, R.color.protocol_airplay).apply {
            setPadding(0, dp(36), 0, 0)
        }

        column.addView(artwork)
        column.addView(titleView)
        column.addView(artistView)
        column.addView(albumView)
        column.addView(senderView)

        addView(
            column,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        )
    }

    /** Updates the card to reflect [info]. Falls back to a placeholder glyph when no artwork. */
    fun update(info: NowPlayingInfo) {
        // The artwork comes from the sender and may announce a picture that is huge
        // when decoded (audit finding SEC-02). SafeBitmapDecoder refuses anything
        // outside the decode budget instead of allocating it.
        val bitmap = info.artwork?.let { SafeBitmapDecoder.decode(it) }
        if (bitmap != null) {
            updateArtworkBackground(bitmap)
            artwork.scaleType = ImageView.ScaleType.CENTER_CROP
            artwork.setImageBitmap(bitmap)
            artwork.setColorFilter(null)
        } else {
            transitionBackgroundTo(GradientDrawable().apply {
                colors = intArrayOf(defaultBackground, Color.BLACK)
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
            })
            // No artwork from the sender (typical for raw system audio) — show the AirPlay glyph.
            artwork.scaleType = ImageView.ScaleType.CENTER_INSIDE
            artwork.setImageResource(R.drawable.ic_airplay)
            artwork.setColorFilter(color(R.color.text_secondary))
            // The artwork was there but could not be decoded — that is worth a line in
            // the log. The bitmap is null in this branch anyway, so only the sender's
            // bytes are checked.
            if (info.artwork != null) {
                Logger.w("NowPlayingScreen: artwork bytes (${info.artwork.size}B) failed to decode")
            }
        }

        titleView.text = info.title ?: context.getString(R.string.now_playing_audio)
        artistView.setTextVisible(info.artist)
        albumView.setTextVisible(info.album)
        senderView.text = context.getString(R.string.now_playing_from, info.senderName)
    }

    /** Releases the (potentially large) artwork bitmap when the card is hidden. */
    fun clear() {
        artwork.setImageDrawable(null)
        setBackgroundColor(defaultBackground)
    }

    /** Samples the supplied cover and uses its dominant colors for a dark, readable backdrop. */
    private fun updateArtworkBackground(bitmap: android.graphics.Bitmap) {
        val bins = Array(HUE_BINS) { FloatArray(4) } // weight, red, green, blue
        var totalWeight = 0f
        var red = 0f
        var green = 0f
        var blue = 0f
        val hsv = FloatArray(3)
        val sampleCount = 24
        for (y in 0 until sampleCount) {
            val py = ((y + 0.5f) * bitmap.height / sampleCount).toInt().coerceIn(0, bitmap.height - 1)
            for (x in 0 until sampleCount) {
                val px = ((x + 0.5f) * bitmap.width / sampleCount).toInt().coerceIn(0, bitmap.width - 1)
                val pixel = bitmap.getPixel(px, py)
                if (Color.alpha(pixel) < 128) continue
                Color.colorToHSV(pixel, hsv)
                val weight = (0.15f + hsv[1]) * (0.2f + hsv[2])
                totalWeight += weight
                red += Color.red(pixel) * weight
                green += Color.green(pixel) * weight
                blue += Color.blue(pixel) * weight
                if (hsv[1] > 0.18f) {
                    val bin = (hsv[0] / 360f * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
                    bins[bin][0] += weight
                    bins[bin][1] += Color.red(pixel) * weight
                    bins[bin][2] += Color.green(pixel) * weight
                    bins[bin][3] += Color.blue(pixel) * weight
                }
            }
        }
        if (totalWeight <= 0f) return
        val average = Color.rgb((red / totalWeight).toInt(), (green / totalWeight).toInt(), (blue / totalWeight).toInt())
        val dominantBin = bins.maxBy { it[0] }
        val accent = if (dominantBin[0] > 0f) {
            Color.rgb((dominantBin[1] / dominantBin[0]).toInt(), (dominantBin[2] / dominantBin[0]).toInt(), (dominantBin[3] / dominantBin[0]).toInt())
        } else average
        transitionBackgroundTo(GradientDrawable().apply {
            colors = intArrayOf(darken(accent, 0.28f), darken(average, 0.17f), defaultBackground)
            orientation = GradientDrawable.Orientation.TL_BR
        })
    }

    private fun darken(color: Int, value: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] *= 0.72f
        hsv[2] = value
        return Color.HSVToColor(hsv)
    }

    private fun transitionBackgroundTo(drawable: android.graphics.drawable.Drawable) {
        // Keep transitions shallow when metadata updates arrive during a track.
        val current = background
        val previous = if (current is TransitionDrawable) {
            current.getDrawable(1)
        } else {
            current ?: ColorDrawable(defaultBackground)
        }
        background = TransitionDrawable(arrayOf(previous, drawable)).apply {
            // Keep the previous background fully opaque while the new colors fade in.
            // Cross-fading both layers lowers their combined alpha on some Android TV
            // renderers, briefly exposing the Activity (Lite Home) underneath.
            isCrossFadeEnabled = false
            startTransition(BACKGROUND_TRANSITION_MS)
        }
    }

    private fun TextView.setTextVisible(value: String?) {
        if (value.isNullOrBlank()) {
            visibility = View.GONE
        } else {
            text = value
            visibility = View.VISIBLE
        }
    }

    private fun textView(sizeSp: Float, colorRes: Int, bold: Boolean = false) = TextView(context).apply {
        setTextColor(color(colorRes))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        gravity = Gravity.CENTER
        maxLines = 2
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun color(res: Int): Int = try { context.getColor(res) } catch (_: Exception) { Color.WHITE }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val ART_SIZE_DP = 360
        private const val TITLE_SP = 30f
        private const val ARTIST_SP = 22f
        private const val ALBUM_SP = 17f
        private const val SENDER_SP = 16f
        private const val HUE_BINS = 12
        private const val BACKGROUND_TRANSITION_MS = 600
    }
}
