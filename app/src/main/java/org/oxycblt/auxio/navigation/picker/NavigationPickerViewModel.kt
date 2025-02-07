/*
 * Copyright (c) 2023 Auxio Project
 * NavigationPickerViewModel.kt is part of Auxio.
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
 
package org.oxycblt.auxio.navigation.picker

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.oxycblt.auxio.music.Album
import org.oxycblt.auxio.music.Artist
import org.oxycblt.auxio.music.Music
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.Song
import org.oxycblt.auxio.util.logD

/**
 * A [ViewModel] that stores the current information required for navigation picker dialogs
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@HiltViewModel
class NavigationPickerViewModel @Inject constructor(private val musicRepository: MusicRepository) :
    ViewModel(), MusicRepository.UpdateListener {
    private val _artistChoices = MutableStateFlow<ArtistNavigationChoices?>(null)
    /** The current set of [Artist] choices to show in the picker, or null if to show nothing. */
    val artistChoices: StateFlow<ArtistNavigationChoices?>
        get() = _artistChoices

    init {
        musicRepository.addUpdateListener(this)
    }

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        if (!changes.deviceLibrary) return
        val deviceLibrary = musicRepository.deviceLibrary ?: return
        // Need to sanitize different items depending on the current set of choices.
        _artistChoices.value =
            when (val choices = _artistChoices.value) {
                is SongArtistNavigationChoices ->
                    deviceLibrary.findSong(choices.song.uid)?.let {
                        SongArtistNavigationChoices(it)
                    }
                is AlbumArtistNavigationChoices ->
                    deviceLibrary.findAlbum(choices.album.uid)?.let {
                        AlbumArtistNavigationChoices(it)
                    }
                else -> null
            }
        logD("Updated artist choices: ${_artistChoices.value}")
    }

    override fun onCleared() {
        super.onCleared()
        musicRepository.removeUpdateListener(this)
    }

    /**
     * Set the [Music.UID] of the item to show artist choices for.
     *
     * @param itemUid The [Music.UID] of the item to show. Must be a [Song] or [Album].
     */
    fun setArtistChoiceUid(itemUid: Music.UID) {
        logD("Opening navigation choices for $itemUid")
        // Support Songs and Albums, which have parent artists.
        _artistChoices.value =
            when (val music = musicRepository.find(itemUid)) {
                is Song -> {
                    logD("Creating navigation choices for song")
                    SongArtistNavigationChoices(music)
                }
                is Album -> {
                    logD("Creating navigation choices for album")
                    AlbumArtistNavigationChoices(music)
                }
                else -> {
                    logD("Given song/album UID was invalid")
                    null
                }
            }
    }
}

/**
 * The current list of choices to show in the artist navigation picker dialog.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
sealed interface ArtistNavigationChoices {
    /** The current [Artist] choices. */
    val choices: List<Artist>
}

/** Backing implementation of [ArtistNavigationChoices] that is based on a [Song]. */
private data class SongArtistNavigationChoices(val song: Song) : ArtistNavigationChoices {
    override val choices = song.artists
}

/**
 * Backing implementation of [ArtistNavigationChoices] that is based on an
 * [AlbumArtistNavigationChoices].
 */
private data class AlbumArtistNavigationChoices(val album: Album) : ArtistNavigationChoices {
    override val choices = album.artists
}
