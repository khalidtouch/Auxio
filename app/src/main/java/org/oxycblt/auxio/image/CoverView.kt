/*
 * Copyright (c) 2023 Auxio Project
 * CoverView.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.image

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.AttrRes
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.core.content.res.getIntOrThrow
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.children
import androidx.core.view.updateMarginsRelative
import androidx.core.widget.ImageViewCompat
import coil.ImageLoader
import coil.request.ImageRequest
import coil.util.CoilUtils
import com.google.android.material.R as MR
import com.google.android.material.shape.MaterialShapeDrawable
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.Album
import org.oxycblt.auxio.music.Artist
import org.oxycblt.auxio.music.Genre
import org.oxycblt.auxio.music.Playlist
import org.oxycblt.auxio.music.Song
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.util.getAttrColorCompat
import org.oxycblt.auxio.util.getColorCompat
import org.oxycblt.auxio.util.getDimen
import org.oxycblt.auxio.util.getDimenPixels
import org.oxycblt.auxio.util.getDrawableCompat
import org.oxycblt.auxio.util.getInteger

/**
 * Auxio's extension of [ImageView] that enables cover art loading and playing indicator and
 * selection badge. In practice, it's three [ImageView]'s in a [FrameLayout] trenchcoat. By default,
 * all of this functionality is enabled. The playback indicator and selection badge selectively
 * disabled with the "playbackIndicatorEnabled" and "selectionBadgeEnabled" attributes, and image
 * itself can be overridden if populated like a normal [FrameLayout].
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class CoverView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, @AttrRes defStyleAttr: Int = 0) :
    FrameLayout(context, attrs, defStyleAttr) {
    @Inject lateinit var imageLoader: ImageLoader
    @Inject lateinit var uiSettings: UISettings

    private val image: ImageView

    data class PlaybackIndicator(
        val view: ImageView,
        val playingDrawable: AnimationDrawable,
        val pausedDrawable: Drawable
    )
    private val playbackIndicator: PlaybackIndicator?
    private val selectionBadge: ImageView?

    @DimenRes private val iconSizeRes: Int?
    @DimenRes private val cornerRadiusRes: Int?

    private var fadeAnimator: ValueAnimator? = null
    private val indicatorMatrix = Matrix()
    private val indicatorMatrixSrc = RectF()
    private val indicatorMatrixDst = RectF()

    init {
        // Obtain some StyledImageView attributes to use later when theming the custom view.
        @SuppressLint("CustomViewStyleable")
        val styledAttrs = context.obtainStyledAttributes(attrs, R.styleable.CoverView)

        val sizing = styledAttrs.getIntOrThrow(R.styleable.CoverView_sizing)
        iconSizeRes = SIZING_ICON_SIZE[sizing]
        cornerRadiusRes =
            if (uiSettings.roundMode) {
                SIZING_CORNER_RADII[sizing]
            } else {
                null
            }

        val playbackIndicatorEnabled =
            styledAttrs.getBoolean(R.styleable.CoverView_enablePlaybackIndicator, true)
        val selectionBadgeEnabled =
            styledAttrs.getBoolean(R.styleable.CoverView_enableSelectionBadge, true)

        styledAttrs.recycle()

        image = ImageView(context, attrs)

        // Initialize the playback indicator if enabled.
        playbackIndicator =
            if (playbackIndicatorEnabled) {
                PlaybackIndicator(
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.MATRIX
                        ImageViewCompat.setImageTintList(
                            this, context.getColorCompat(R.color.sel_on_cover_bg))
                    },
                    context.getDrawableCompat(R.drawable.ic_playing_indicator_24)
                        as AnimationDrawable,
                    context.getDrawableCompat(R.drawable.ic_paused_indicator_24))
            } else {
                null
            }

        // Initialize the selection badge if enabled.
        selectionBadge =
            if (selectionBadgeEnabled) {
                ImageView(context).apply {
                    imageTintList = context.getAttrColorCompat(MR.attr.colorOnPrimary)
                    setImageResource(R.drawable.ic_check_20)
                    setBackgroundResource(R.drawable.ui_selection_badge_bg)
                }
            } else {
                null
            }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()

        // The image isn't added if other children have populated the body. This is by design.
        if (childCount == 0) {
            addView(image)
        }

        playbackIndicator?.run { addView(view) }

        // Add backgrounds to each child for visual consistency
        for (child in children) {
            child.apply {
                // If there are rounded corners, we want to make sure view content will be cropped
                // with it.
                clipToOutline = this != image
                background =
                    MaterialShapeDrawable().apply {
                        fillColor = context.getColorCompat(R.color.sel_cover_bg)
                        setCornerSize(cornerRadiusRes?.let(context::getDimen) ?: 0f)
                    }
            }
        }

        // The selection badge has it's own background we don't want overridden, add it after
        // all other elements.
        selectionBadge?.let {
            addView(
                it,
                // Position the selection badge to the bottom right.
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    // Override the layout params of the indicator so that it's in the
                    // bottom left corner.
                    gravity = Gravity.BOTTOM or Gravity.END
                    val spacing = context.getDimenPixels(R.dimen.spacing_tiny)
                    updateMarginsRelative(bottom = spacing, end = spacing)
                })
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        // AnimatedVectorDrawable cannot be placed in a StyledDrawable, we must replicate the
        // behavior with a matrix.
        val playbackIndicator = (playbackIndicator ?: return).view
        val iconSize = iconSizeRes?.let(context::getDimenPixels) ?: (measuredWidth / 2)
        playbackIndicator.apply {
            imageMatrix =
                indicatorMatrix.apply {
                    reset()
                    drawable?.let { drawable ->
                        // First scale the icon up to the desired size.
                        indicatorMatrixSrc.set(
                            0f,
                            0f,
                            drawable.intrinsicWidth.toFloat(),
                            drawable.intrinsicHeight.toFloat())
                        indicatorMatrixDst.set(0f, 0f, iconSize.toFloat(), iconSize.toFloat())
                        indicatorMatrix.setRectToRect(
                            indicatorMatrixSrc, indicatorMatrixDst, Matrix.ScaleToFit.CENTER)

                        // Then actually center it into the icon.
                        indicatorMatrix.postTranslate(
                            (measuredWidth - iconSize) / 2f, (measuredHeight - iconSize) / 2f)
                    }
                }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        invalidateRootAlpha()
        invalidatePlaybackIndicatorAlpha(playbackIndicator ?: return)
        invalidateSelectionIndicatorAlpha(selectionBadge ?: return)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        invalidateRootAlpha()
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        invalidateRootAlpha()
        invalidatePlaybackIndicatorAlpha(playbackIndicator ?: return)
    }

    override fun setActivated(activated: Boolean) {
        super.setActivated(activated)
        invalidateSelectionIndicatorAlpha(selectionBadge ?: return)
    }

    /**
     * Set if the playback indicator should be indicated ongoing or paused playback.
     *
     * @param isPlaying Whether playback is ongoing or paused.
     */
    fun setPlaying(isPlaying: Boolean) {
        playbackIndicator?.run {
            if (isPlaying) {
                playingDrawable.start()
                view.setImageDrawable(playingDrawable)
            } else {
                playingDrawable.stop()
                view.setImageDrawable(pausedDrawable)
            }
        }
    }

    private fun invalidateRootAlpha() {
        alpha = if (isEnabled || isSelected) 1f else 0.5f
    }

    private fun invalidatePlaybackIndicatorAlpha(playbackIndicator: PlaybackIndicator) {
        // Occasionally content can bleed through the rounded corners and result in a seam
        // on the playing indicator, prevent that from occurring by disabling the visibility of
        // all views below the playback indicator.
        for (child in children) {
            child.alpha =
                when (child) {
                    // Selection badge is above the playback indicator, do nothing
                    selectionBadge -> child.alpha
                    playbackIndicator.view -> if (isSelected) 1f else 0f
                    else -> if (isSelected) 0f else 1f
                }
        }
    }

    private fun invalidateSelectionIndicatorAlpha(selectionBadge: ImageView) {
        // Set up a target transition for the selection indicator.
        val targetAlpha: Float
        val targetDuration: Long

        if (isActivated) {
            // View is "activated" (i.e marked as selected), so show the selection indicator.
            targetAlpha = 1f
            targetDuration = context.getInteger(R.integer.anim_fade_enter_duration).toLong()
        } else {
            // View is not "activated", hide the selection indicator.
            targetAlpha = 0f
            targetDuration = context.getInteger(R.integer.anim_fade_exit_duration).toLong()
        }

        if (selectionBadge.alpha == targetAlpha) {
            // Nothing to do.
            return
        }

        if (!isLaidOut) {
            // Not laid out, initialize it without animation before drawing.
            selectionBadge.alpha = targetAlpha
            return
        }

        if (fadeAnimator != null) {
            // Cancel any previous animation.
            fadeAnimator?.cancel()
            fadeAnimator = null
        }

        fadeAnimator =
            ValueAnimator.ofFloat(selectionBadge.alpha, targetAlpha).apply {
                duration = targetDuration
                addUpdateListener { selectionBadge.alpha = it.animatedValue as Float }
                start()
            }
    }

    /**
     * Bind a [Song]'s image to this view.
     *
     * @param song The [Song] to bind to the view.
     */
    fun bind(song: Song) =
        bind(
            listOf(song),
            context.getString(R.string.desc_album_cover, song.album.name),
            R.drawable.ic_album_24)

    /**
     * Bind an [Album]'s image to this view.
     *
     * @param album The [Album] to bind to the view.
     */
    fun bind(album: Album) =
        bind(
            album.songs,
            context.getString(R.string.desc_album_cover, album.name),
            R.drawable.ic_album_24)

    /**
     * Bind an [Artist]'s image to this view.
     *
     * @param artist The [Artist] to bind to the view.
     */
    fun bind(artist: Artist) =
        bind(
            artist.songs,
            context.getString(R.string.desc_artist_image, artist.name),
            R.drawable.ic_artist_24)

    /**
     * Bind a [Genre]'s image to this view.
     *
     * @param genre The [Genre] to bind to the view.
     */
    fun bind(genre: Genre) =
        bind(
            genre.songs,
            context.getString(R.string.desc_genre_image, genre.name),
            R.drawable.ic_genre_24)

    /**
     * Bind a [Playlist]'s image to this view.
     *
     * @param playlist the [Playlist] to bind.
     */
    fun bind(playlist: Playlist) =
        bind(
            playlist.songs,
            context.getString(R.string.desc_playlist_image, playlist.name),
            R.drawable.ic_playlist_24)

    /**
     * Bind the covers of a generic list of [Song]s.
     *
     * @param songs The [Song]s to bind.
     * @param desc The content description to describe the bound data.
     * @param errorRes The resource of the error drawable to use if the cover cannot be loaded.
     */
    fun bind(songs: List<Song>, desc: String, @DrawableRes errorRes: Int) {
        val request =
            ImageRequest.Builder(context)
                .data(songs)
                .error(StyledDrawable(context, context.getDrawableCompat(errorRes), iconSizeRes))
                .transformations(
                    RoundedCornersTransformation(cornerRadiusRes?.let(context::getDimen) ?: 0f))
                .target(image)
                .build()
        // Dispose of any previous image request and load a new image.
        CoilUtils.dispose(image)
        imageLoader.enqueue(request)
        contentDescription = desc
    }

    /**
     * Since the error drawable must also share a view with an image, any kind of transform or tint
     * must occur within a custom dialog, which is implemented here.
     */
    private class StyledDrawable(
        context: Context,
        private val inner: Drawable,
        @DimenRes iconSizeRes: Int?
    ) : Drawable() {
        init {
            // Re-tint the drawable to use the analogous "on surface" color for
            // StyledImageView.
            DrawableCompat.setTintList(inner, context.getColorCompat(R.color.sel_on_cover_bg))
        }

        private val dimen = iconSizeRes?.let(context::getDimenPixels)

        override fun draw(canvas: Canvas) {
            // Resize the drawable such that it's always 1/4 the size of the image and
            // centered in the middle of the canvas.
            val adj = dimen?.let { (bounds.width() - it) / 2 } ?: (bounds.width() / 4)
            inner.bounds.set(adj, adj, bounds.width() - adj, bounds.height() - adj)
            inner.draw(canvas)
        }

        // Required drawable overrides. Just forward to the wrapped drawable.

        override fun setAlpha(alpha: Int) {
            inner.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            inner.colorFilter = colorFilter
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    companion object {
        val SIZING_CORNER_RADII =
            arrayOf(
                R.dimen.size_corners_small, R.dimen.size_corners_small, R.dimen.size_corners_medium)
        val SIZING_ICON_SIZE = arrayOf(R.dimen.size_icon_small, R.dimen.size_icon_medium, null)
    }
}
