/*
 * Copyright (c) 2021 Auxio Project
 * PlaybackViewModel.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.oxycblt.auxio.music.Album
import org.oxycblt.auxio.music.Artist
import org.oxycblt.auxio.music.Genre
import org.oxycblt.auxio.music.MusicMode
import org.oxycblt.auxio.music.MusicParent
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.MusicSettings
import org.oxycblt.auxio.music.Playlist
import org.oxycblt.auxio.music.Song
import org.oxycblt.auxio.playback.persist.PersistenceRepository
import org.oxycblt.auxio.playback.queue.Queue
import org.oxycblt.auxio.playback.state.InternalPlayer
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.util.Event
import org.oxycblt.auxio.util.MutableEvent
import org.oxycblt.auxio.util.logD

/**
 * An [ViewModel] that provides a safe UI frontend for the current playback state.
 *
 * @author Alexander Capehart (OxygenCobalt)
 *
 * TODO: Debug subtle backwards movement of position on pause
 */
@HiltViewModel
class PlaybackViewModel
@Inject
constructor(
    private val playbackManager: PlaybackStateManager,
    private val playbackSettings: PlaybackSettings,
    private val persistenceRepository: PersistenceRepository,
    private val musicRepository: MusicRepository,
    private val musicSettings: MusicSettings
) : ViewModel(), PlaybackStateManager.Listener {
    private var lastPositionJob: Job? = null

    private val _song = MutableStateFlow<Song?>(null)
    /** The currently playing song. */
    val song: StateFlow<Song?>
        get() = _song
    private val _parent = MutableStateFlow<MusicParent?>(null)
    /** The [MusicParent] currently being played. Null if playback is occurring from all songs. */
    val parent: StateFlow<MusicParent?> = _parent
    private val _isPlaying = MutableStateFlow(false)
    /** Whether playback is ongoing or paused. */
    val isPlaying: StateFlow<Boolean>
        get() = _isPlaying
    private val _positionDs = MutableStateFlow(0L)
    /** The current position, in deci-seconds (1/10th of a second). */
    val positionDs: StateFlow<Long>
        get() = _positionDs

    private val _repeatMode = MutableStateFlow(RepeatMode.NONE)
    /** The current [RepeatMode]. */
    val repeatMode: StateFlow<RepeatMode>
        get() = _repeatMode
    private val _isShuffled = MutableStateFlow(false)
    /** Whether the queue is shuffled or not. */
    val isShuffled: StateFlow<Boolean>
        get() = _isShuffled

    private val _artistPlaybackPickerSong = MutableEvent<Song>()
    /**
     * Flag signaling to open a picker dialog in order to resolve an ambiguous choice when playing a
     * [Song] from one of it's [Artist]s.
     *
     * @see playFromArtist
     */
    val artistPickerSong: Event<Song>
        get() = _artistPlaybackPickerSong

    private val _genrePlaybackPickerSong = MutableEvent<Song>()
    /**
     * Flag signaling to open a picker dialog in order to resolve an ambiguous choice when playing a
     * [Song] from one of it's [Genre]s.
     */
    val genrePickerSong: Event<Song>
        get() = _genrePlaybackPickerSong

    /** The current action to show on the playback bar. */
    val currentBarAction: ActionMode
        get() = playbackSettings.barAction

    /**
     * The current audio session ID of the internal player. Null if no [InternalPlayer] is
     * available.
     */
    val currentAudioSessionId: Int?
        get() = playbackManager.currentAudioSessionId

    init {
        playbackManager.addListener(this)
    }

    override fun onCleared() {
        playbackManager.removeListener(this)
    }

    override fun onIndexMoved(queue: Queue) {
        logD("Index moved, updating current song")
        _song.value = queue.currentSong
    }

    override fun onQueueChanged(queue: Queue, change: Queue.Change) {
        // Other types of queue changes preserve the current song.
        if (change.type == Queue.Change.Type.SONG) {
            logD("Queue changed, updating current song")
            _song.value = queue.currentSong
        }
    }

    override fun onQueueReordered(queue: Queue) {
        logD("Queue completely changed, updating current song")
        _isShuffled.value = queue.isShuffled
    }

    override fun onNewPlayback(queue: Queue, parent: MusicParent?) {
        logD("New playback started, updating playback information")
        _song.value = queue.currentSong
        _parent.value = parent
        _isShuffled.value = queue.isShuffled
    }

    override fun onStateChanged(state: InternalPlayer.State) {
        logD("Player state changed, starting new position polling")
        _isPlaying.value = state.isPlaying
        // Still need to update the position now due to co-routine launch delays
        _positionDs.value = state.calculateElapsedPositionMs().msToDs()
        // Replace the previous position co-routine with a new one that uses the new
        // state information.
        lastPositionJob?.cancel()
        lastPositionJob =
            viewModelScope.launch {
                while (true) {
                    _positionDs.value = state.calculateElapsedPositionMs().msToDs()
                    // Wait a deci-second for the next position tick.
                    delay(100)
                }
            }
    }

    override fun onRepeatChanged(repeatMode: RepeatMode) {
        _repeatMode.value = repeatMode
    }

    // --- PLAYING FUNCTIONS ---

    /** Shuffle all songs in the music library. */
    fun shuffleAll() {
        logD("Shuffling all songs")
        playImpl(null, null, true)
    }

    /**
     * Play a [Song] from the [MusicParent] outlined by the given [MusicMode].
     * - If [MusicMode.SONGS], the [Song] is played from all songs.
     * - If [MusicMode.ALBUMS], the [Song] is played from it's [Album].
     * - If [MusicMode.ARTISTS], the [Song] is played from one of it's [Artist]s.
     * - If [MusicMode.GENRES], the [Song] is played from one of it's [Genre]s.
     *   [MusicMode.PLAYLISTS] is disallowed here.
     *
     * @param song The [Song] to play.
     * @param playbackMode The [MusicMode] to play from.
     */
    fun playFrom(song: Song, playbackMode: MusicMode) {
        logD("Playing $song from $playbackMode")
        when (playbackMode) {
            MusicMode.SONGS -> playImpl(song, null)
            MusicMode.ALBUMS -> playImpl(song, song.album)
            MusicMode.ARTISTS -> playFromArtist(song)
            MusicMode.GENRES -> playFromGenre(song)
            MusicMode.PLAYLISTS -> error("Playing from a playlist is not supported.")
        }
    }

    /**
     * Play a [Song] from one of it's [Artist]s.
     *
     * @param song The [Song] to play.
     * @param artist The [Artist] to play from. Must be linked to the [Song]. If null, the user will
     *   be prompted on what artist to play. Defaults to null.
     */
    fun playFromArtist(song: Song, artist: Artist? = null) {
        if (artist != null) {
            logD("Playing $song from $artist")
            playImpl(song, artist)
        } else if (song.artists.size == 1) {
            logD("$song has one artist, playing from it")
            playImpl(song, song.artists[0])
        } else {
            logD("$song has multiple artists, showing choice dialog")
            _artistPlaybackPickerSong.put(song)
        }
    }

    /**
     * Play a [Song] from one of it's [Genre]s.
     *
     * @param song The [Song] to play.
     * @param genre The [Genre] to play from. Must be linked to the [Song]. If null, the user will
     *   be prompted on what artist to play. Defaults to null.
     */
    fun playFromGenre(song: Song, genre: Genre? = null) {
        if (genre != null) {
            logD("Playing $song from $genre")
            playImpl(song, genre)
        } else if (song.genres.size == 1) {
            logD("$song has one genre, playing from it")
            playImpl(song, song.genres[0])
        } else {
            logD("$song has multiple genres, showing choice dialog")
            _genrePlaybackPickerSong.put(song)
        }
    }

    /**
     * PLay a [Song] from one of it's [Playlist]s.
     *
     * @param song The [Song] to play.
     * @param playlist The [Playlist] to play from. Must be linked to the [Song].
     */
    fun playFromPlaylist(song: Song, playlist: Playlist) {
        logD("Playing $song from $playlist")
        playImpl(song, playlist)
    }

    /**
     * Play an [Album].
     *
     * @param album The [Album] to play.
     */
    fun play(album: Album) {
        logD("Playing $album")
        playImpl(null, album, false)
    }

    /**
     * Play an [Artist].
     *
     * @param artist The [Artist] to play.
     */
    fun play(artist: Artist) {
        logD("Playing $artist")
        playImpl(null, artist, false)
    }

    /**
     * Play a [Genre].
     *
     * @param genre The [Genre] to play.
     */
    fun play(genre: Genre) {
        logD("Playing $genre")
        playImpl(null, genre, false)
    }

    /**
     * Play a [Playlist].
     *
     * @param playlist The [Playlist] to play.
     */
    fun play(playlist: Playlist) {
        logD("Playing $playlist")
        playImpl(null, playlist, false)
    }

    /**
     * Play a list of [Song]s.
     *
     * @param songs The [Song]s to play.
     */
    fun play(songs: List<Song>) {
        logD("Playing ${songs.size} songs")
        playbackManager.play(null, null, songs, false)
    }

    /**
     * Shuffle an [Album].
     *
     * @param album The [Album] to shuffle.
     */
    fun shuffle(album: Album) {
        logD("Shuffling $album")
        playImpl(null, album, true)
    }

    /**
     * Shuffle an [Artist].
     *
     * @param artist The [Artist] to shuffle.
     */
    fun shuffle(artist: Artist) {
        logD("Shuffling $artist")
        playImpl(null, artist, true)
    }

    /**
     * Shuffle a [Genre].
     *
     * @param genre The [Genre] to shuffle.
     */
    fun shuffle(genre: Genre) {
        logD("Shuffling $genre")
        playImpl(null, genre, true)
    }

    /**
     * Shuffle a [Playlist].
     *
     * @param playlist The [Playlist] to shuffle.
     */
    fun shuffle(playlist: Playlist) {
        logD("Shuffling $playlist")
        playImpl(null, playlist, true)
    }

    /**
     * Shuffle a list of [Song]s.
     *
     * @param songs The [Song]s to shuffle.
     */
    fun shuffle(songs: List<Song>) {
        logD("Shuffling ${songs.size} songs")
        playbackManager.play(null, null, songs, true)
    }

    private fun playImpl(
        song: Song?,
        parent: MusicParent?,
        shuffled: Boolean = playbackManager.queue.isShuffled && playbackSettings.keepShuffle
    ) {
        check(song == null || parent == null || parent.songs.contains(song)) {
            "Song to play not in parent"
        }
        val deviceLibrary = musicRepository.deviceLibrary ?: return
        val queue =
            when (parent) {
                is Genre -> musicSettings.genreSongSort.songs(parent.songs)
                is Artist -> musicSettings.artistSongSort.songs(parent.songs)
                is Album -> musicSettings.albumSongSort.songs(parent.songs)
                is Playlist -> parent.songs
                null -> musicSettings.songSort.songs(deviceLibrary.songs)
            }
        playbackManager.play(song, parent, queue, shuffled)
    }

    /**
     * Start the given [InternalPlayer.Action] to be completed eventually. This can be used to
     * enqueue a playback action at startup to then occur when the music library is fully loaded.
     *
     * @param action The [InternalPlayer.Action] to perform eventually.
     */
    fun startAction(action: InternalPlayer.Action) {
        logD("Starting action $action")
        playbackManager.startAction(action)
    }

    // --- PLAYER FUNCTIONS ---

    /**
     * Seek to the given position in the currently playing [Song].
     *
     * @param positionDs The position to seek to, in deci-seconds (1/10th of a second).
     */
    fun seekTo(positionDs: Long) {
        logD("Seeking to ${positionDs}ds")
        playbackManager.seekTo(positionDs.dsToMs())
    }

    // --- QUEUE FUNCTIONS ---

    /** Skip to the next [Song]. */
    fun next() {
        logD("Skipping to next song")
        playbackManager.next()
    }

    /** Skip to the previous [Song]. */
    fun prev() {
        logD("Skipping to previous song")
        playbackManager.prev()
    }

    /**
     * Add a [Song] to the top of the queue.
     *
     * @param song The [Song] to add.
     */
    fun playNext(song: Song) {
        logD("Playing $song next")
        playbackManager.playNext(song)
    }

    /**
     * Add a [Album] to the top of the queue.
     *
     * @param album The [Album] to add.
     */
    fun playNext(album: Album) {
        logD("Playing $album next")
        playbackManager.playNext(musicSettings.albumSongSort.songs(album.songs))
    }

    /**
     * Add a [Artist] to the top of the queue.
     *
     * @param artist The [Artist] to add.
     */
    fun playNext(artist: Artist) {
        logD("Playing $artist next")
        playbackManager.playNext(musicSettings.artistSongSort.songs(artist.songs))
    }

    /**
     * Add a [Genre] to the top of the queue.
     *
     * @param genre The [Genre] to add.
     */
    fun playNext(genre: Genre) {
        logD("Playing $genre next")
        playbackManager.playNext(musicSettings.genreSongSort.songs(genre.songs))
    }

    /**
     * Add a [Playlist] to the top of the queue.
     *
     * @param playlist The [Playlist] to add.
     */
    fun playNext(playlist: Playlist) {
        logD("Playing $playlist next")
        playbackManager.playNext(playlist.songs)
    }

    /**
     * Add [Song]s to the top of the queue.
     *
     * @param songs The [Song]s to add.
     */
    fun playNext(songs: List<Song>) {
        logD("Playing ${songs.size} songs next")
        playbackManager.playNext(songs)
    }

    /**
     * Add a [Song] to the end of the queue.
     *
     * @param song The [Song] to add.
     */
    fun addToQueue(song: Song) {
        logD("Adding $song to queue")
        playbackManager.addToQueue(song)
    }

    /**
     * Add a [Album] to the end of the queue.
     *
     * @param album The [Album] to add.
     */
    fun addToQueue(album: Album) {
        logD("Adding $album to queue")
        playbackManager.addToQueue(musicSettings.albumSongSort.songs(album.songs))
    }

    /**
     * Add a [Artist] to the end of the queue.
     *
     * @param artist The [Artist] to add.
     */
    fun addToQueue(artist: Artist) {
        logD("Adding $artist to queue")
        playbackManager.addToQueue(musicSettings.artistSongSort.songs(artist.songs))
    }

    /**
     * Add a [Genre] to the end of the queue.
     *
     * @param genre The [Genre] to add.
     */
    fun addToQueue(genre: Genre) {
        logD("Adding $genre to queue")
        playbackManager.addToQueue(musicSettings.genreSongSort.songs(genre.songs))
    }

    /**
     * Add a [Playlist] to the end of the queue.
     *
     * @param playlist The [Playlist] to add.
     */
    fun addToQueue(playlist: Playlist) {
        logD("Adding $playlist to queue")
        playbackManager.addToQueue(playlist.songs)
    }

    /**
     * Add [Song]s to the end of the queue.
     *
     * @param songs The [Song]s to add.
     */
    fun addToQueue(songs: List<Song>) {
        logD("Adding ${songs.size} songs to queue")
        playbackManager.addToQueue(songs)
    }

    // --- STATUS FUNCTIONS ---

    /** Toggle [isPlaying] (i.e from playing to paused) */
    fun togglePlaying() {
        logD("Toggling playing state")
        playbackManager.setPlaying(!playbackManager.playerState.isPlaying)
    }

    /** Toggle [isShuffled] (ex. from on to off) */
    fun toggleShuffled() {
        logD("Toggling shuffled state")
        playbackManager.reorder(!playbackManager.queue.isShuffled)
    }

    /**
     * Toggle [repeatMode] (ex. from [RepeatMode.NONE] to [RepeatMode.TRACK])
     *
     * @see RepeatMode.increment
     */
    fun toggleRepeatMode() {
        logD("Toggling repeat mode")
        playbackManager.repeatMode = playbackManager.repeatMode.increment()
    }

    // --- SAVE/RESTORE FUNCTIONS ---

    /**
     * Force-save the current playback state.
     *
     * @param onDone Called when the save is completed with true if successful, and false otherwise.
     */
    fun savePlaybackState(onDone: (Boolean) -> Unit) {
        logD("Saving playback state")
        viewModelScope.launch {
            onDone(persistenceRepository.saveState(playbackManager.toSavedState()))
        }
    }

    /**
     * Clear the current playback state.
     *
     * @param onDone Called when the wipe is completed with true if successful, and false otherwise.
     */
    fun wipePlaybackState(onDone: (Boolean) -> Unit) {
        logD("Wiping playback state")
        viewModelScope.launch { onDone(persistenceRepository.saveState(null)) }
    }

    /**
     * Force-restore the current playback state.
     *
     * @param onDone Called when the restoration is completed with true if successful, and false
     *   otherwise.
     */
    fun tryRestorePlaybackState(onDone: (Boolean) -> Unit) {
        logD("Force-restoring playback state")
        viewModelScope.launch {
            val savedState = persistenceRepository.readState()
            if (savedState != null) {
                playbackManager.applySavedState(savedState, true)
                onDone(true)
                return@launch
            }
            onDone(false)
        }
    }
}
