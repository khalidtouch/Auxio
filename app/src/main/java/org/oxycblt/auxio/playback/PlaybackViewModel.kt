package org.oxycblt.auxio.playback

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import org.oxycblt.auxio.music.Album
import org.oxycblt.auxio.music.Artist
import org.oxycblt.auxio.music.BaseModel
import org.oxycblt.auxio.music.MusicStore
import org.oxycblt.auxio.music.Song
import org.oxycblt.auxio.music.toDuration
import kotlin.random.Random
import kotlin.random.Random.Default.nextLong

// TODO: Queue
// TODO: Add the playback service itself
// TODO: Add loop control [From playback]
// TODO: Implement persistence through Bundles and sanity checks [I want to keep my shuffles, okay?]
// A ViewModel that acts as an intermediary between PlaybackService and the Playback Fragments.
class PlaybackViewModel : ViewModel() {
    private val mCurrentSong = MutableLiveData<Song>()
    val currentSong: LiveData<Song> get() = mCurrentSong

    private val mCurrentParent = MutableLiveData<BaseModel>()
    val currentParent: LiveData<BaseModel> get() = mCurrentParent

    private val mQueue = MutableLiveData(mutableListOf<Song>())
    val queue: LiveData<MutableList<Song>> get() = mQueue

    private val mCurrentIndex = MutableLiveData(0)
    val currentIndex: LiveData<Int> get() = mCurrentIndex

    private val mCurrentMode = MutableLiveData(PlaybackMode.ALL_SONGS)
    val currentMode: LiveData<PlaybackMode> get() = mCurrentMode

    private val mCurrentDuration = MutableLiveData(0L)

    private val mIsPlaying = MutableLiveData(false)
    val isPlaying: LiveData<Boolean> get() = mIsPlaying

    private val mIsShuffling = MutableLiveData(false)
    val isShuffling: LiveData<Boolean> get() = mIsShuffling

    private val mShuffleSeed = MutableLiveData(-1L)

    private val mIsSeeking = MutableLiveData(false)
    val isSeeking: LiveData<Boolean> get() = mIsSeeking

    // Formatted variants of the duration
    val formattedCurrentDuration = Transformations.map(mCurrentDuration) {
        it.toDuration()
    }

    val formattedSeekBarProgress = Transformations.map(mCurrentDuration) {
        if (mCurrentSong.value != null) it.toInt() else 0
    }

    // Update the current song while changing the queue mode.
    fun update(song: Song, mode: PlaybackMode) {
        Log.d(this::class.simpleName, "Updating song to ${song.name} and mode to $mode")

        val musicStore = MusicStore.getInstance()

        mCurrentMode.value = mode

        updatePlayback(song)

        mQueue.value = when (mode) {
            PlaybackMode.ALL_SONGS -> musicStore.songs.toMutableList()
            PlaybackMode.IN_ARTIST -> song.album.artist.songs
            PlaybackMode.IN_ALBUM -> song.album.songs
        }

        mCurrentParent.value = when (mode) {
            PlaybackMode.ALL_SONGS -> null
            PlaybackMode.IN_ARTIST -> song.album.artist
            PlaybackMode.IN_ALBUM -> song.album
        }

        if (mIsShuffling.value!!) {
            genShuffle(true)
        } else {
            resetShuffle()
        }

        mCurrentIndex.value = mQueue.value!!.indexOf(song)
    }

    fun play(album: Album, isShuffled: Boolean) {
        Log.d(this::class.simpleName, "Playing album ${album.name}")

        val songs = orderSongsInAlbum(album)

        updatePlayback(songs[0])

        mQueue.value = songs
        mCurrentIndex.value = 0
        mCurrentParent.value = album
        mIsShuffling.value = isShuffled
        mCurrentMode.value = PlaybackMode.IN_ALBUM

        if (mIsShuffling.value!!) {
            genShuffle(false)
        } else {
            resetShuffle()
        }
    }

    fun play(artist: Artist, isShuffled: Boolean) {
        Log.d(this::class.simpleName, "Playing artist ${artist.name}")

        val songs = orderSongsInArtist(artist)

        updatePlayback(songs[0])

        mQueue.value = songs
        mCurrentIndex.value = 0
        mCurrentParent.value = artist
        mIsShuffling.value = isShuffled
        mCurrentMode.value = PlaybackMode.IN_ARTIST

        if (mIsShuffling.value!!) {
            genShuffle(false)
        } else {
            resetShuffle()
        }
    }

    // Update the current duration using a SeekBar progress
    fun updateCurrentDurationWithProgress(progress: Int) {
        mCurrentDuration.value = progress.toLong()
    }

    // Invert, not directly set the playing/shuffling status
    // Used by the toggle buttons in playback fragment.
    fun invertPlayingStatus() {
        mIsPlaying.value = !mIsPlaying.value!!
    }

    fun invertShuffleStatus() {
        mIsShuffling.value = !mIsShuffling.value!!

        if (mIsShuffling.value!!) {
            genShuffle(true)
        } else {
            resetShuffle()
        }
    }

    // Shuffle all the songs.
    fun shuffleAll() {
        val musicStore = MusicStore.getInstance()

        mIsShuffling.value = true
        mQueue.value = musicStore.songs.toMutableList()
        mCurrentMode.value = PlaybackMode.ALL_SONGS
        mCurrentIndex.value = 0

        genShuffle(false)
        updatePlayback(mQueue.value!![0])
    }

    // Set the seeking status
    fun setSeekingStatus(status: Boolean) {
        mIsSeeking.value = status
    }

    fun skipNext() {
        if (mCurrentIndex.value!! < mQueue.value!!.size) {
            mCurrentIndex.value = mCurrentIndex.value!!.inc()
        }

        updatePlayback(mQueue.value!![mCurrentIndex.value!!])
    }

    fun skipPrev() {
        if (mCurrentIndex.value!! > 0) {
            mCurrentIndex.value = mCurrentIndex.value!!.dec()
        }

        updatePlayback(mQueue.value!![mCurrentIndex.value!!])
    }

    private fun updatePlayback(song: Song) {
        mCurrentSong.value = song
        mCurrentDuration.value = 0

        if (!mIsPlaying.value!!) {
            mIsPlaying.value = true
        }
    }

    // Generate a new shuffled queue.
    private fun genShuffle(keepSong: Boolean) {
        // Take a random seed and then shuffle the current queue based off of that.
        // This seed will be saved in a bundle if the app closes, so that the shuffle mode
        // can be restored when its started again.
        val newSeed = Random.Default.nextLong()

        Log.d(this::class.simpleName, "Shuffling queue with a seed of $newSeed.")

        mShuffleSeed.value = newSeed

        mQueue.value!!.shuffle(Random(newSeed))
        mCurrentIndex.value = 0

        // If specified, make the current song the first member of the queue.
        if (keepSong) {
            mQueue.value!!.remove(mCurrentSong.value)
            mQueue.value!!.add(0, mCurrentSong.value!!)
        } else {
            // Otherwise, just start from the zeroth position in the queue.
            mCurrentSong.value = mQueue.value!![0]
        }
    }

    private fun resetShuffle() {
        mShuffleSeed.value = -1

        mQueue.value = when (mCurrentMode.value!!) {
            PlaybackMode.IN_ARTIST -> orderSongsInArtist(mCurrentParent.value as Artist)
            PlaybackMode.IN_ALBUM -> orderSongsInAlbum(mCurrentParent.value as Album)
            PlaybackMode.ALL_SONGS -> MusicStore.getInstance().songs.toMutableList()
        }

        mCurrentIndex.value = mQueue.value!!.indexOf(mCurrentSong.value)
    }

    // Basic sorting functions when things are played in order
    private fun orderSongsInAlbum(album: Album): MutableList<Song> {
        return album.songs.sortedBy { it.track }.toMutableList()
    }

    private fun orderSongsInArtist(artist: Artist): MutableList<Song> {
        val final = mutableListOf<Song>()

        artist.albums.sortedByDescending { it.year }.forEach {
            final.addAll(it.songs.sortedBy { it.track })
        }

        return final
    }
}
