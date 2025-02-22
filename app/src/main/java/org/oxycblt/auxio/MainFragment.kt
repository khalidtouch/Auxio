/*
 * Copyright (c) 2021 Auxio Project
 * MainFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.isInvisible
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.activityViewModels
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import com.google.android.material.R as MR
import com.google.android.material.bottomsheet.BackportBottomSheetBehavior
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.transition.MaterialFadeThrough
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.max
import kotlin.math.min
import org.oxycblt.auxio.databinding.FragmentMainBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.list.selection.SelectionViewModel
import org.oxycblt.auxio.music.Music
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.Playlist
import org.oxycblt.auxio.music.Song
import org.oxycblt.auxio.navigation.MainNavigationAction
import org.oxycblt.auxio.navigation.NavigationViewModel
import org.oxycblt.auxio.playback.PlaybackBottomSheetBehavior
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.queue.QueueBottomSheetBehavior
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collect
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.context
import org.oxycblt.auxio.util.coordinatorLayoutBehavior
import org.oxycblt.auxio.util.getAttrColorCompat
import org.oxycblt.auxio.util.getDimen
import org.oxycblt.auxio.util.logD
import org.oxycblt.auxio.util.navigateSafe
import org.oxycblt.auxio.util.systemBarInsetsCompat
import org.oxycblt.auxio.util.unlikelyToBeNull

/**
 * A wrapper around the home fragment that shows the playback fragment and controls the more
 * high-level navigation features.
 *
 * @author Alexander Capehart (OxygenCobalt)
 *
 * TODO: Break up the god navigation setup going on here
 */
@AndroidEntryPoint
class MainFragment :
    ViewBindingFragment<FragmentMainBinding>(),
    ViewTreeObserver.OnPreDrawListener,
    NavController.OnDestinationChangedListener {
    private val navModel: NavigationViewModel by activityViewModels()
    private val musicModel: MusicViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val selectionModel: SelectionViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private var sheetBackCallback: SheetBackPressedCallback? = null
    private var detailBackCallback: DetailBackPressedCallback? = null
    private var selectionBackCallback: SelectionBackPressedCallback? = null
    private var exploreBackCallback: ExploreBackPressedCallback? = null
    private var lastInsets: WindowInsets? = null
    private var elevationNormal = 0f
    private var initialNavDestinationChange = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
    }

    override fun onCreateBinding(inflater: LayoutInflater) = FragmentMainBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentMainBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        val playbackSheetBehavior =
            binding.playbackSheet.coordinatorLayoutBehavior as PlaybackBottomSheetBehavior
        val queueSheetBehavior =
            binding.queueSheet.coordinatorLayoutBehavior as QueueBottomSheetBehavior?

        elevationNormal = binding.context.getDimen(R.dimen.elevation_normal)

        // Currently all back press callbacks are handled in MainFragment, as it's not guaranteed
        // that instantiating these callbacks in their respective fragments would result in the
        // correct order.
        val sheetBackCallback =
            SheetBackPressedCallback(
                    playbackSheetBehavior = playbackSheetBehavior,
                    queueSheetBehavior = queueSheetBehavior)
                .also { sheetBackCallback = it }
        val detailBackCallback =
            DetailBackPressedCallback(detailModel).also { detailBackCallback = it }
        val selectionBackCallback =
            SelectionBackPressedCallback(selectionModel).also { selectionBackCallback = it }
        val exploreBackCallback =
            ExploreBackPressedCallback(binding.exploreNavHost).also { exploreBackCallback = it }

        // --- UI SETUP ---
        val context = requireActivity()
        // Override the back pressed listener so we can map back navigation to collapsing
        // navigation, navigation out of detail views, etc.
        context.onBackPressedDispatcher.apply {
            addCallback(viewLifecycleOwner, exploreBackCallback)
            addCallback(viewLifecycleOwner, selectionBackCallback)
            addCallback(viewLifecycleOwner, detailBackCallback)
            addCallback(viewLifecycleOwner, sheetBackCallback)
        }

        binding.root.setOnApplyWindowInsetsListener { _, insets ->
            lastInsets = insets
            insets
        }

        // Send meaningful accessibility events for bottom sheets
        ViewCompat.setAccessibilityPaneTitle(
            binding.playbackSheet, context.getString(R.string.lbl_playback))
        ViewCompat.setAccessibilityPaneTitle(
            binding.queueSheet, context.getString(R.string.lbl_queue))

        if (queueSheetBehavior != null) {
            // In portrait mode, set up click listeners on the stacked sheets.
            logD("Configuring stacked bottom sheets")
            unlikelyToBeNull(binding.queueHandleWrapper).setOnClickListener {
                if (playbackSheetBehavior.state == BackportBottomSheetBehavior.STATE_EXPANDED &&
                    queueSheetBehavior.targetState == BackportBottomSheetBehavior.STATE_COLLAPSED) {
                    // Playback sheet is expanded and queue sheet is collapsed, we can expand it.
                    queueSheetBehavior.state = BackportBottomSheetBehavior.STATE_EXPANDED
                }
            }
        } else {
            // Dual-pane mode, manually style the static queue sheet.
            logD("Configuring dual-pane bottom sheet")
            binding.queueSheet.apply {
                // Emulate the elevated bottom sheet style.
                background =
                    MaterialShapeDrawable.createWithElevationOverlay(context).apply {
                        fillColor = context.getAttrColorCompat(MR.attr.colorSurface)
                        elevation = context.getDimen(R.dimen.elevation_normal)
                    }
                // Apply bar insets for the queue's RecyclerView to use.
                setOnApplyWindowInsetsListener { v, insets ->
                    v.updatePadding(top = insets.systemBarInsetsCompat.top)
                    insets
                }
            }
        }

        // --- VIEWMODEL SETUP ---
        collectImmediately(detailModel.editedPlaylist, detailBackCallback::invalidateEnabled)
        collectImmediately(selectionModel.selected, selectionBackCallback::invalidateEnabled)
        collect(musicModel.newPlaylistSongs.flow, ::handleNewPlaylist)
        collect(musicModel.playlistToRename.flow, ::handleRenamePlaylist)
        collect(musicModel.playlistToDelete.flow, ::handleDeletePlaylist)
        collect(musicModel.songsToAdd.flow, ::handleAddToPlaylist)
        collect(navModel.mainNavigationAction.flow, ::handleMainNavigation)
        collect(navModel.exploreNavigationItem.flow, ::handleExploreNavigation)
        collect(navModel.exploreArtistNavigationItem.flow, ::handleArtistNavigationPicker)
        collectImmediately(playbackModel.song, ::updateSong)
        collect(playbackModel.artistPickerSong.flow, ::handlePlaybackArtistPicker)
        collect(playbackModel.genrePickerSong.flow, ::handlePlaybackGenrePicker)
    }

    override fun onStart() {
        super.onStart()
        val binding = requireBinding()
        // Once we add the destination change callback, we will receive another initialization call,
        // so handle that by resetting the flag.
        initialNavDestinationChange = false
        binding.exploreNavHost.findNavController().addOnDestinationChangedListener(this)
        // Listener could still reasonably fire even if we clear the binding, attach/detach
        // our pre-draw listener our listener in onStart/onStop respectively.
        binding.playbackSheet.viewTreeObserver.addOnPreDrawListener(this@MainFragment)
    }

    override fun onStop() {
        super.onStop()
        val binding = requireBinding()
        binding.exploreNavHost.findNavController().removeOnDestinationChangedListener(this)
        binding.playbackSheet.viewTreeObserver.removeOnPreDrawListener(this)
    }

    override fun onDestroyBinding(binding: FragmentMainBinding) {
        super.onDestroyBinding(binding)
        sheetBackCallback = null
        detailBackCallback = null
        selectionBackCallback = null
        exploreBackCallback = null
    }

    override fun onPreDraw(): Boolean {
        // We overload CoordinatorLayout far too much to rely on any of it's typical
        // listener functionality. Just update all transitions before every draw. Should
        // probably be cheap enough.
        val binding = requireBinding()
        val playbackSheetBehavior =
            binding.playbackSheet.coordinatorLayoutBehavior as PlaybackBottomSheetBehavior
        val queueSheetBehavior =
            binding.queueSheet.coordinatorLayoutBehavior as QueueBottomSheetBehavior?

        val playbackRatio = max(playbackSheetBehavior.calculateSlideOffset(), 0f)
        val outPlaybackRatio = 1 - playbackRatio
        val halfOutRatio = min(playbackRatio * 2, 1f)
        val halfInPlaybackRatio = max(playbackRatio - 0.5f, 0f) * 2

        if (queueSheetBehavior != null) {
            // Queue sheet available, the normal transition applies, but it now much be combined
            // with another transition where the playback panel disappears and the playback bar
            // appears as the queue sheet expands.
            val queueRatio = max(queueSheetBehavior.calculateSlideOffset(), 0f)
            val halfOutQueueRatio = min(queueRatio * 2, 1f)
            val halfInQueueRatio = max(queueRatio - 0.5f, 0f) * 2

            binding.playbackBarFragment.alpha = max(1 - halfOutRatio, halfInQueueRatio)
            binding.playbackPanelFragment.alpha = min(halfInPlaybackRatio, 1 - halfOutQueueRatio)
            binding.queueFragment.alpha = queueRatio

            if (playbackModel.song.value != null) {
                // Playback sheet intercepts queue sheet touch events, prevent that from
                // occurring by disabling dragging whenever the queue sheet is expanded.
                playbackSheetBehavior.isDraggable =
                    queueSheetBehavior.state == BackportBottomSheetBehavior.STATE_COLLAPSED
            }
        } else {
            // No queue sheet, fade normally based on the playback sheet
            binding.playbackBarFragment.alpha = 1 - halfOutRatio
            binding.playbackPanelFragment.alpha = halfInPlaybackRatio
        }

        // Fade out the content as the playback panel expands.
        // TODO: Replace with shadow?
        binding.exploreNavHost.apply {
            alpha = outPlaybackRatio
            // Prevent interactions when the content fully fades out.
            isInvisible = alpha == 0f
        }

        // Reduce playback sheet elevation as it expands. This involves both updating the
        // shadow elevation for older versions, and fading out the background drawable
        // containing the elevation overlay.
        binding.playbackSheet.translationZ = elevationNormal * outPlaybackRatio
        playbackSheetBehavior.sheetBackgroundDrawable.alpha = (outPlaybackRatio * 255).toInt()

        // Fade out the playback bar as the panel expands.
        binding.playbackBarFragment.apply {
            // Prevent interactions when the playback bar fully fades out.
            isInvisible = alpha == 0f
            // As the playback bar expands, we also want to subtly translate the bar to
            // align with the top inset. This results in both a smooth transition from the bar
            // to the playback panel's toolbar, but also a correctly positioned playback bar
            // for when the queue sheet expands.
            lastInsets?.let { translationY = it.systemBarInsetsCompat.top * halfOutRatio }
        }

        // Prevent interactions when the playback panel fully fades out.
        binding.playbackPanelFragment.isInvisible = binding.playbackPanelFragment.alpha == 0f

        binding.queueSheet.apply {
            // Queue sheet (not queue content) should fade out with the playback panel.
            alpha = halfInPlaybackRatio
            // Prevent interactions when the queue sheet fully fades out.
            binding.queueSheet.isInvisible = alpha == 0f
        }

        // Prevent interactions when the queue content fully fades out.
        binding.queueFragment.isInvisible = binding.queueFragment.alpha == 0f

        if (playbackModel.song.value == null) {
            // Sometimes lingering drags can un-hide the playback sheet even when we intend to
            // hide it, make sure we keep it hidden.
            tryHideAllSheets()
        }

        // Since the navigation listener is also reliant on the bottom sheets, we must also update
        // it every frame.
        requireNotNull(sheetBackCallback) { "SheetBackPressedCallback was not available" }
            .invalidateEnabled()

        return true
    }

    override fun onDestinationChanged(
        controller: NavController,
        destination: NavDestination,
        arguments: Bundle?
    ) {
        // Drop the initial call by NavController that simply provides us with the current
        // destination. This would cause the selection state to be lost every time the device
        // rotates.
        requireNotNull(exploreBackCallback) { "ExploreBackPressedCallback was not available" }
            .invalidateEnabled()
        if (!initialNavDestinationChange) {
            initialNavDestinationChange = true
            return
        }
        selectionModel.drop()
    }

    private fun handleMainNavigation(action: MainNavigationAction?) {
        if (action != null) {
            when (action) {
                is MainNavigationAction.OpenPlaybackPanel -> tryOpenPlaybackPanel()
                is MainNavigationAction.ClosePlaybackPanel -> tryClosePlaybackPanel()
                is MainNavigationAction.Directions ->
                    findNavController().navigateSafe(action.directions)
            }
            navModel.mainNavigationAction.consume()
        }
    }

    private fun handleExploreNavigation(item: Music?) {
        if (item != null) {
            tryClosePlaybackPanel()
        }
    }

    private fun handleArtistNavigationPicker(item: Music?) {
        if (item != null) {
            navModel.mainNavigateTo(
                MainNavigationAction.Directions(
                    MainFragmentDirections.actionPickNavigationArtist(item.uid)))
            navModel.exploreArtistNavigationItem.consume()
        }
    }

    private fun updateSong(song: Song?) {
        if (song != null) {
            tryShowSheets()
        } else {
            tryHideAllSheets()
        }
    }

    private fun handleNewPlaylist(songs: List<Song>?) {
        if (songs != null) {
            findNavController()
                .navigateSafe(
                    MainFragmentDirections.actionNewPlaylist(songs.map { it.uid }.toTypedArray()))
            musicModel.newPlaylistSongs.consume()
        }
    }

    private fun handleRenamePlaylist(playlist: Playlist?) {
        if (playlist != null) {
            findNavController()
                .navigateSafe(MainFragmentDirections.actionRenamePlaylist(playlist.uid))
            musicModel.playlistToRename.consume()
        }
    }

    private fun handleDeletePlaylist(playlist: Playlist?) {
        if (playlist != null) {
            findNavController()
                .navigateSafe(MainFragmentDirections.actionDeletePlaylist(playlist.uid))
            musicModel.playlistToDelete.consume()
        }
    }

    private fun handleAddToPlaylist(songs: List<Song>?) {
        if (songs != null) {
            findNavController()
                .navigateSafe(
                    MainFragmentDirections.actionAddToPlaylist(songs.map { it.uid }.toTypedArray()))
            musicModel.songsToAdd.consume()
        }
    }

    private fun handlePlaybackArtistPicker(song: Song?) {
        if (song != null) {
            navModel.mainNavigateTo(
                MainNavigationAction.Directions(
                    MainFragmentDirections.actionPickPlaybackArtist(song.uid)))
            playbackModel.artistPickerSong.consume()
        }
    }

    private fun handlePlaybackGenrePicker(song: Song?) {
        if (song != null) {
            navModel.mainNavigateTo(
                MainNavigationAction.Directions(
                    MainFragmentDirections.actionPickPlaybackGenre(song.uid)))
            playbackModel.genrePickerSong.consume()
        }
    }

    private fun tryOpenPlaybackPanel() {
        val binding = requireBinding()
        val playbackSheetBehavior =
            binding.playbackSheet.coordinatorLayoutBehavior as PlaybackBottomSheetBehavior

        if (playbackSheetBehavior.targetState == BackportBottomSheetBehavior.STATE_COLLAPSED) {
            // Playback sheet is not expanded and not hidden, we can expand it.
            logD("Expanding playback sheet")
            playbackSheetBehavior.state = BackportBottomSheetBehavior.STATE_EXPANDED
            return
        }

        val queueSheetBehavior =
            (binding.queueSheet.coordinatorLayoutBehavior ?: return) as QueueBottomSheetBehavior
        if (playbackSheetBehavior.state == BackportBottomSheetBehavior.STATE_EXPANDED &&
            queueSheetBehavior.targetState == BackportBottomSheetBehavior.STATE_EXPANDED) {
            // Queue sheet and playback sheet is expanded, close the queue sheet so the
            // playback panel can shown.
            logD("Collapsing queue sheet")
            queueSheetBehavior.state = BackportBottomSheetBehavior.STATE_COLLAPSED
        }
    }

    private fun tryClosePlaybackPanel() {
        val binding = requireBinding()
        val playbackSheetBehavior =
            binding.playbackSheet.coordinatorLayoutBehavior as PlaybackBottomSheetBehavior
        if (playbackSheetBehavior.targetState == BackportBottomSheetBehavior.STATE_EXPANDED) {
            // Playback sheet (and possibly queue) needs to be collapsed.
            logD("Collapsing playback and queue sheets")
            val queueSheetBehavior =
                binding.queueSheet.coordinatorLayoutBehavior as QueueBottomSheetBehavior?
            playbackSheetBehavior.state = BackportBottomSheetBehavior.STATE_COLLAPSED
            queueSheetBehavior?.state = BackportBottomSheetBehavior.STATE_COLLAPSED
        }
    }

    private fun tryShowSheets() {
        val binding = requireBinding()
        val playbackSheetBehavior =
            binding.playbackSheet.coordinatorLayoutBehavior as PlaybackBottomSheetBehavior
        if (playbackSheetBehavior.targetState == BackportBottomSheetBehavior.STATE_HIDDEN) {
            logD("Unhiding and enabling playback sheet")
            val queueSheetBehavior =
                binding.queueSheet.coordinatorLayoutBehavior as QueueBottomSheetBehavior?
            // Queue sheet behavior is either collapsed or expanded, no hiding needed
            queueSheetBehavior?.isDraggable = true
            playbackSheetBehavior.apply {
                // Make sure the view is draggable, at least until the draw checks kick in.
                isDraggable = true
                state = BackportBottomSheetBehavior.STATE_COLLAPSED
            }
        }
    }

    private fun tryHideAllSheets() {
        val binding = requireBinding()
        val playbackSheetBehavior =
            binding.playbackSheet.coordinatorLayoutBehavior as PlaybackBottomSheetBehavior
        if (playbackSheetBehavior.targetState != BackportBottomSheetBehavior.STATE_HIDDEN) {
            val queueSheetBehavior =
                binding.queueSheet.coordinatorLayoutBehavior as QueueBottomSheetBehavior?

            logD("Hiding and disabling playback and queue sheets")

            // Make both bottom sheets non-draggable so the user can't halt the hiding event.
            queueSheetBehavior?.apply {
                isDraggable = false
                state = BackportBottomSheetBehavior.STATE_COLLAPSED
            }

            playbackSheetBehavior.apply {
                isDraggable = false
                state = BackportBottomSheetBehavior.STATE_HIDDEN
            }
        }
    }

    private class SheetBackPressedCallback(
        private val playbackSheetBehavior: PlaybackBottomSheetBehavior<*>,
        private val queueSheetBehavior: QueueBottomSheetBehavior<*>?
    ) : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // If expanded, collapse the queue sheet first.
            if (queueSheetShown()) {
                unlikelyToBeNull(queueSheetBehavior).state =
                    BackportBottomSheetBehavior.STATE_COLLAPSED
                logD("Collapsed queue sheet")
                return
            }

            // If expanded, collapse the playback sheet next.
            if (playbackSheetShown()) {
                playbackSheetBehavior.state = BackportBottomSheetBehavior.STATE_COLLAPSED
                logD("Collapsed playback sheet")
                return
            }
        }

        fun invalidateEnabled() {
            isEnabled = queueSheetShown() || playbackSheetShown()
        }

        private fun playbackSheetShown() =
            playbackSheetBehavior.targetState != BackportBottomSheetBehavior.STATE_COLLAPSED &&
                playbackSheetBehavior.targetState != BackportBottomSheetBehavior.STATE_HIDDEN

        private fun queueSheetShown() =
            queueSheetBehavior != null &&
                playbackSheetBehavior.state == BackportBottomSheetBehavior.STATE_EXPANDED &&
                queueSheetBehavior.targetState != BackportBottomSheetBehavior.STATE_COLLAPSED
    }

    private class DetailBackPressedCallback(private val detailModel: DetailViewModel) :
        OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (detailModel.dropPlaylistEdit()) {
                logD("Dropped playlist edits")
            }
        }

        fun invalidateEnabled(playlistEdit: List<Song>?) {
            isEnabled = playlistEdit != null
        }
    }

    private inner class SelectionBackPressedCallback(
        private val selectionModel: SelectionViewModel
    ) : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (selectionModel.drop()) {
                logD("Dropped selection")
            }
        }

        fun invalidateEnabled(selection: List<Music>) {
            isEnabled = selection.isNotEmpty()
        }
    }

    private inner class ExploreBackPressedCallback(
        private val exploreNavHost: FragmentContainerView
    ) : OnBackPressedCallback(false) {
        // Note: We cannot cache the NavController in a variable since it's current destination
        // value goes stale for some reason.

        override fun handleOnBackPressed() {
            exploreNavHost.findNavController().navigateUp()
            logD("Forwarded back navigation to explore nav host")
        }

        fun invalidateEnabled() {
            val exploreNavController = exploreNavHost.findNavController()
            isEnabled =
                exploreNavController.currentDestination?.id !=
                    exploreNavController.graph.startDestinationId
        }
    }
}
