/*
 * Copyright (c) 2023 Auxio Project
 * CoverExtractor.kt is part of Auxio.
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
 
package org.oxycblt.auxio.image.extractor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.util.Size as AndroidSize
import androidx.core.graphics.drawable.toDrawable
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.metadata.flac.PictureFrame
import androidx.media3.extractor.metadata.id3.ApicFrame
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.SourceResult
import coil.size.Dimension
import coil.size.Size
import coil.size.pxOrElse
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.inject.Inject
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.asDeferred
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import org.oxycblt.auxio.image.CoverMode
import org.oxycblt.auxio.image.ImageSettings
import org.oxycblt.auxio.list.Sort
import org.oxycblt.auxio.music.Album
import org.oxycblt.auxio.music.Song
import org.oxycblt.auxio.util.logE

/**
 * Provides functionality for extracting album cover information. Meant for internal use only.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class CoverExtractor
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val imageSettings: ImageSettings,
    private val mediaSourceFactory: MediaSource.Factory
) {
    /**
     * Extract an image (in the form of [FetchResult]) to represent the given [Song]s.
     *
     * @param songs The [Song]s to load.
     * @param size The [Size] of the image to load.
     * @return If four distinct album covers could be extracted from the [Song]s, a [DrawableResult]
     *   will be returned of a mosaic composed of four album covers ordered by
     *   [computeCoverOrdering]. Otherwise, a [SourceResult] of one album cover will be returned.
     */
    suspend fun extract(songs: List<Song>, size: Size): FetchResult? {
        val albums = computeCoverOrdering(songs)
        val streams = mutableListOf<InputStream>()
        for (album in albums) {
            openCoverInputStream(album)?.let(streams::add)
            // We don't immediately check for mosaic feasibility from album count alone, as that
            // does not factor in InputStreams failing to load. Instead, only check once we
            // definitely have image data to use.
            if (streams.size == 4) {
                // Make sure we free the InputStreams once we've transformed them into a mosaic.
                return createMosaic(streams, size).also {
                    withContext(Dispatchers.IO) { streams.forEach(InputStream::close) }
                }
            }
        }

        // Not enough covers for a mosaic, take the first one (if that even exists)
        val first = streams.firstOrNull() ?: return null

        // All but the first stream will be unused, free their resources
        withContext(Dispatchers.IO) {
            for (i in 1 until streams.size) {
                streams[i].close()
            }
        }

        return SourceResult(
            source = ImageSource(first.source().buffer(), context),
            mimeType = null,
            dataSource = DataSource.DISK)
    }

    /**
     * Creates an [Album] list representing the order that album covers would be used in [extract].
     *
     * @param songs A hypothetical list of [Song]s that would be used in [extract].
     * @return A list of [Album]s first ordered by the "representation" within the [Song]s, and then
     *   by their names. "Representation" is defined by how many [Song]s were found to be linked to
     *   the given [Album] in the given [Song] list.
     */
    fun computeCoverOrdering(songs: List<Song>): List<Album> {
        // TODO: Start short-circuiting in more places
        if (songs.isEmpty()) return listOf()
        if (songs.size == 1) return listOf(songs.first().album)

        val sortedMap =
            sortedMapOf<Album, Int>(Sort.Mode.ByName.getAlbumComparator(Sort.Direction.ASCENDING))
        for (song in songs) {
            sortedMap[song.album] = (sortedMap[song.album] ?: 0) + 1
        }
        return sortedMap.keys.sortedByDescending { sortedMap[it] }
    }

    private suspend fun openCoverInputStream(album: Album) =
        try {
            when (imageSettings.coverMode) {
                CoverMode.OFF -> null
                CoverMode.MEDIA_STORE -> extractMediaStoreCover(album)
                CoverMode.QUALITY -> extractQualityCover(album)
            }
        } catch (e: Exception) {
            logE("Unable to extract album cover due to an error: $e")
            null
        }

    private suspend fun extractQualityCover(album: Album) =
        extractAospMetadataCover(album)
            ?: extractExoplayerCover(album) ?: extractMediaStoreCover(album)

    private fun extractAospMetadataCover(album: Album): InputStream? =
        MediaMetadataRetriever().run {
            // This call is time-consuming but it also doesn't seem to hold up the main thread,
            // so it's probably fine not to wrap it.rmt
            setDataSource(context, album.songs[0].uri)

            // Get the embedded picture from MediaMetadataRetriever, which will return a full
            // ByteArray of the cover without any compression artifacts.
            // If its null [i.e there is no embedded cover], than just ignore it and move on
            return embeddedPicture?.let { ByteArrayInputStream(it) }.also { release() }
        }

    private suspend fun extractExoplayerCover(album: Album): InputStream? {
        val tracks =
            MetadataRetriever.retrieveMetadata(
                    mediaSourceFactory, MediaItem.fromUri(album.songs[0].uri))
                .asDeferred()
                .await()

        // The metadata extraction process of ExoPlayer results in a dump of all metadata
        // it found, which must be iterated through.
        val metadata = tracks[0].getFormat(0).metadata

        if (metadata == null || metadata.length() == 0) {
            // No (parsable) metadata. This is also expected.
            return null
        }

        var stream: ByteArrayInputStream? = null

        for (i in 0 until metadata.length()) {
            // We can only extract pictures from two tags with this method, ID3v2's APIC or
            // Vorbis picture comments.
            val pic: ByteArray?
            val type: Int

            when (val entry = metadata.get(i)) {
                is ApicFrame -> {
                    pic = entry.pictureData
                    type = entry.pictureType
                }
                is PictureFrame -> {
                    pic = entry.pictureData
                    type = entry.pictureType
                }
                else -> continue
            }

            if (type == MediaMetadata.PICTURE_TYPE_FRONT_COVER) {
                stream = ByteArrayInputStream(pic)
                break
            } else if (stream == null) {
                stream = ByteArrayInputStream(pic)
            }
        }

        return stream
    }

    private suspend fun extractMediaStoreCover(album: Album) =
        // Eliminate any chance that this blocking call might mess up the loading process
        withContext(Dispatchers.IO) { context.contentResolver.openInputStream(album.coverUri) }

    /** Derived from phonograph: https://github.com/kabouzeid/Phonograph */
    private fun createMosaic(streams: List<InputStream>, size: Size): FetchResult {
        // Use whatever size coil gives us to create the mosaic.
        val mosaicSize = AndroidSize(size.width.mosaicSize(), size.height.mosaicSize())
        val mosaicFrameSize =
            Size(Dimension(mosaicSize.width / 2), Dimension(mosaicSize.height / 2))

        val mosaicBitmap =
            Bitmap.createBitmap(mosaicSize.width, mosaicSize.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(mosaicBitmap)

        var x = 0
        var y = 0

        // For each stream, create a bitmap scaled to 1/4th of the mosaics combined size
        // and place it on a corner of the canvas.
        for (stream in streams) {
            if (y == mosaicSize.height) {
                break
            }

            // Crop the bitmap down to a square so it leaves no empty space
            // TODO: Work around this
            val bitmap = cropBitmap(BitmapFactory.decodeStream(stream), mosaicFrameSize)
            canvas.drawBitmap(bitmap, x.toFloat(), y.toFloat(), null)

            x += bitmap.width
            if (x == mosaicSize.width) {
                x = 0
                y += bitmap.height
            }
        }

        // It's way easier to map this into a drawable then try to serialize it into an
        // BufferedSource. Just make sure we mark it as "sampled" so Coil doesn't try to
        // load low-res mosaics into high-res ImageViews.
        return DrawableResult(
            drawable = mosaicBitmap.toDrawable(context.resources),
            isSampled = true,
            dataSource = DataSource.DISK)
    }

    private fun Dimension.mosaicSize(): Int {
        // Since we want the mosaic to be perfectly divisible into two, we need to round any
        // odd image sizes upwards to prevent the mosaic creation from failing.
        val size = pxOrElse { 512 }
        return if (size.mod(2) > 0) size + 1 else size
    }

    private fun cropBitmap(input: Bitmap, size: Size): Bitmap {
        // Find the smaller dimension and then take a center portion of the image that
        // has that size.
        val dstSize = min(input.width, input.height)
        val x = (input.width - dstSize) / 2
        val y = (input.height - dstSize) / 2
        val dst = Bitmap.createBitmap(input, x, y, dstSize, dstSize)

        val desiredWidth = size.width.pxOrElse { dstSize }
        val desiredHeight = size.height.pxOrElse { dstSize }
        if (dstSize != desiredWidth || dstSize != desiredHeight) {
            // Image is not the desired size, upscale it.
            return Bitmap.createScaledBitmap(dst, desiredWidth, desiredHeight, true)
        }
        return dst
    }
}
