/*
 * Copyright (c) 2021 Auxio Project
 * HomeFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.MenuCompat
import androidx.core.view.isVisible
import androidx.core.view.iterator
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import java.lang.reflect.Field
import kotlin.math.abs
import org.oxycblt.auxio.BuildConfig
import org.oxycblt.auxio.MainFragmentDirections
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentHomeBinding
import org.oxycblt.auxio.home.list.AlbumListFragment
import org.oxycblt.auxio.home.list.ArtistListFragment
import org.oxycblt.auxio.home.list.GenreListFragment
import org.oxycblt.auxio.home.list.PlaylistListFragment
import org.oxycblt.auxio.home.list.SongListFragment
import org.oxycblt.auxio.home.tabs.AdaptiveTabStrategy
import org.oxycblt.auxio.home.tabs.Tab
import org.oxycblt.auxio.list.Sort
import org.oxycblt.auxio.list.selection.SelectionFragment
import org.oxycblt.auxio.list.selection.SelectionViewModel
import org.oxycblt.auxio.music.Album
import org.oxycblt.auxio.music.Artist
import org.oxycblt.auxio.music.Genre
import org.oxycblt.auxio.music.IndexingProgress
import org.oxycblt.auxio.music.IndexingState
import org.oxycblt.auxio.music.Music
import org.oxycblt.auxio.music.MusicMode
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.NoAudioPermissionException
import org.oxycblt.auxio.music.NoMusicException
import org.oxycblt.auxio.music.PERMISSION_READ_AUDIO
import org.oxycblt.auxio.music.Playlist
import org.oxycblt.auxio.music.Song
import org.oxycblt.auxio.navigation.MainNavigationAction
import org.oxycblt.auxio.navigation.NavigationViewModel
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.util.collect
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.getColorCompat
import org.oxycblt.auxio.util.lazyReflectedField
import org.oxycblt.auxio.util.logD
import org.oxycblt.auxio.util.logW
import org.oxycblt.auxio.util.navigateSafe
import org.oxycblt.auxio.util.unlikelyToBeNull

/**
 * The starting [SelectionFragment] of Auxio. Shows the user's music library and enables navigation
 * to other views.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class HomeFragment :
    SelectionFragment<FragmentHomeBinding>(), AppBarLayout.OnOffsetChangedListener {
    override val playbackModel: PlaybackViewModel by activityViewModels()
    override val selectionModel: SelectionViewModel by activityViewModels()
    override val musicModel: MusicViewModel by activityViewModels()
    private val homeModel: HomeViewModel by activityViewModels()
    private val navModel: NavigationViewModel by activityViewModels()
    private var storagePermissionLauncher: ActivityResultLauncher<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            // Orientation change will wipe whatever transition we were using prior, which will
            // result in no transition when the user navigates back. Make sure we re-initialize
            // our transitions.
            val axis = savedInstanceState.getInt(KEY_LAST_TRANSITION_AXIS, -1)
            if (axis > -1) {
                setupAxisTransitions(axis)
            }
        }
    }

    override fun onCreateBinding(inflater: LayoutInflater) = FragmentHomeBinding.inflate(inflater)

    override fun getSelectionToolbar(binding: FragmentHomeBinding) = binding.homeSelectionToolbar

    override fun onBindingCreated(binding: FragmentHomeBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        // Have to set up the permission launcher before the view is shown
        storagePermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                musicModel.refresh()
            }

        // --- UI SETUP ---
        binding.homeAppbar.addOnOffsetChangedListener(this)
        binding.homeNormalToolbar.apply {
            setOnMenuItemClickListener(this@HomeFragment)
            MenuCompat.setGroupDividerEnabled(menu, true)
        }

        // Load the track color in manually as it's unclear whether the track actually supports
        // using a ColorStateList in the resources
        binding.homeIndexingProgress.trackColor =
            requireContext().getColorCompat(R.color.sel_track).defaultColor

        binding.homePager.apply {
            // Update HomeViewModel whenever the user swipes through the ViewPager.
            // This would be implemented in HomeFragment itself, but OnPageChangeCallback
            // is an object for some reason.
            registerOnPageChangeCallback(
                object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        homeModel.synchronizeTabPosition(position)
                    }
                })

            // ViewPager2 will nominally consume window insets, which will then break the window
            // insets applied to the indexing view before API 30. Fix this by overriding the
            // listener with a non-consuming listener.
            setOnApplyWindowInsetsListener { _, insets -> insets }

            // We know that there will only be a fixed amount of tabs, so we manually set this
            // limit to the maximum amount possible. This will prevent the tab ripple from
            // bugging out due to dynamically inflating each fragment, at the cost of slower
            // debug UI performance.
            offscreenPageLimit = Tab.MAX_SEQUENCE_IDX + 1

            // By default, ViewPager2's sensitivity is high enough to result in vertical scroll
            // events being registered as horizontal scroll events. Reflect into the internal
            // RecyclerView and change the touch slope so that touch actions will act more as a
            // scroll than as a swipe. Derived from:
            // https://al-e-shevelev.medium.com/how-to-reduce-scroll-sensitivity-of-viewpager2-widget-87797ad02414
            val recycler = VP_RECYCLER_FIELD.get(this@apply)
            val slop = RV_TOUCH_SLOP_FIELD.get(recycler) as Int
            RV_TOUCH_SLOP_FIELD.set(recycler, slop * 3)
        }

        // Further initialization must be done in the function that also handles
        // re-creating the ViewPager.
        setupPager(binding)

        // --- VIEWMODEL SETUP ---
        collect(homeModel.recreateTabs.flow, ::handleRecreate)
        collectImmediately(homeModel.currentTabMode, ::updateCurrentTab)
        collectImmediately(homeModel.songsList, homeModel.isFastScrolling, ::updateFab)
        collectImmediately(musicModel.indexingState, ::updateIndexerState)
        collect(navModel.exploreNavigationItem.flow, ::handleNavigation)
        collectImmediately(selectionModel.selected, ::updateSelection)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val enter = enterTransition
        if (enter is MaterialSharedAxis) {
            outState.putInt(KEY_LAST_TRANSITION_AXIS, enter.axis)
        }

        super.onSaveInstanceState(outState)
    }

    override fun onDestroyBinding(binding: FragmentHomeBinding) {
        super.onDestroyBinding(binding)
        storagePermissionLauncher = null
        binding.homeAppbar.removeOnOffsetChangedListener(this)
        binding.homeNormalToolbar.setOnMenuItemClickListener(null)
    }

    override fun onOffsetChanged(appBarLayout: AppBarLayout, verticalOffset: Int) {
        val binding = requireBinding()
        val range = appBarLayout.totalScrollRange
        // Fade out the toolbar as the AppBarLayout collapses. To prevent status bar overlap,
        // the alpha transition is shifted such that the Toolbar becomes fully transparent
        // when the AppBarLayout is only at half-collapsed.
        binding.homeToolbar.alpha = 1f - (abs(verticalOffset.toFloat()) / (range.toFloat() / 2))
        binding.homeContent.updatePadding(
            bottom = binding.homeAppbar.totalScrollRange + verticalOffset)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (super.onMenuItemClick(item)) {
            return true
        }

        return when (item.itemId) {
            // Handle main actions (Search, Settings, About)
            R.id.action_search -> {
                logD("Navigating to search")
                setupAxisTransitions(MaterialSharedAxis.Z)
                findNavController().navigateSafe(HomeFragmentDirections.actionShowSearch())
                true
            }
            R.id.action_settings -> {
                logD("Navigating to settings")
                navModel.mainNavigateTo(
                    MainNavigationAction.Directions(MainFragmentDirections.actionShowSettings()))
                true
            }
            R.id.action_about -> {
                logD("Navigating to about")
                navModel.mainNavigateTo(
                    MainNavigationAction.Directions(MainFragmentDirections.actionShowAbout()))
                true
            }

            // Handle sort menu
            R.id.submenu_sorting -> {
                // Junk click event when opening the menu
                true
            }
            R.id.option_sort_asc -> {
                logD("Switching to ascending sorting")
                item.isChecked = true
                homeModel.setSortForCurrentTab(
                    homeModel
                        .getSortForTab(homeModel.currentTabMode.value)
                        .withDirection(Sort.Direction.ASCENDING))
                true
            }
            R.id.option_sort_dec -> {
                logD("Switching to descending sorting")
                item.isChecked = true
                homeModel.setSortForCurrentTab(
                    homeModel
                        .getSortForTab(homeModel.currentTabMode.value)
                        .withDirection(Sort.Direction.DESCENDING))
                true
            }
            else -> {
                val newMode = Sort.Mode.fromItemId(item.itemId)
                if (newMode != null) {
                    // Sorting option was selected, mark it as selected and update the mode
                    logD("Updating sort mode")
                    item.isChecked = true
                    homeModel.setSortForCurrentTab(
                        homeModel.getSortForTab(homeModel.currentTabMode.value).withMode(newMode))
                    true
                } else {
                    logW("Unexpected menu item selected")
                    false
                }
            }
        }
    }

    private fun setupPager(binding: FragmentHomeBinding) {
        binding.homePager.adapter =
            HomePagerAdapter(homeModel.currentTabModes, childFragmentManager, viewLifecycleOwner)

        val toolbarParams = binding.homeToolbar.layoutParams as AppBarLayout.LayoutParams
        if (homeModel.currentTabModes.size == 1) {
            // A single tab makes the tab layout redundant, hide it and disable the collapsing
            // behavior.
            logD("Single tab shown, disabling TabLayout")
            binding.homeTabs.isVisible = false
            binding.homeAppbar.setExpanded(true, false)
            toolbarParams.scrollFlags = 0
        } else {
            binding.homeTabs.isVisible = true
            toolbarParams.scrollFlags =
                AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                    AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS
        }

        // Set up the mapping between the ViewPager and TabLayout.
        TabLayoutMediator(
                binding.homeTabs,
                binding.homePager,
                AdaptiveTabStrategy(requireContext(), homeModel.currentTabModes))
            .attach()
    }

    private fun updateCurrentTab(tabMode: MusicMode) {
        val binding = requireBinding()
        // Update the sort options to align with those allowed by the tab
        val isVisible: (Int) -> Boolean =
            when (tabMode) {
                // Disallow sorting by count for songs
                MusicMode.SONGS -> {
                    logD("Using song-specific menu options")
                    ({ id -> id != R.id.option_sort_count })
                }
                // Disallow sorting by album for albums
                MusicMode.ALBUMS -> {
                    logD("Using album-specific menu options")
                    ({ id -> id != R.id.option_sort_album })
                }
                // Only allow sorting by name, count, and duration for parents
                else -> {
                    logD("Using parent-specific menu options")
                    ({ id ->
                        id == R.id.option_sort_asc ||
                            id == R.id.option_sort_dec ||
                            id == R.id.option_sort_name ||
                            id == R.id.option_sort_count ||
                            id == R.id.option_sort_duration
                    })
                }
            }

        val sortMenu =
            unlikelyToBeNull(binding.homeNormalToolbar.menu.findItem(R.id.submenu_sorting).subMenu)
        val toHighlight = homeModel.getSortForTab(tabMode)

        for (option in sortMenu) {
            val isCurrentMode = option.itemId == toHighlight.mode.itemId
            val isCurrentlyAscending =
                option.itemId == R.id.option_sort_asc &&
                    toHighlight.direction == Sort.Direction.ASCENDING
            val isCurrentlyDescending =
                option.itemId == R.id.option_sort_dec &&
                    toHighlight.direction == Sort.Direction.DESCENDING
            // Check the corresponding direction and mode sort options to align with
            // the current sort of the tab.
            if (isCurrentMode || isCurrentlyAscending || isCurrentlyDescending) {
                logD(
                    "Checking $option option [mode: $isCurrentMode asc: $isCurrentlyAscending dec: $isCurrentlyDescending]")
                // Note: We cannot inline this boolean assignment since it unchecks all other radio
                // buttons (even when setting it to false), which would result in nothing being
                // selected.
                option.isChecked = true
            }

            // Disable options that are not allowed by the isVisible lambda
            option.isVisible = isVisible(option.itemId)
            if (!option.isVisible) {
                logD("Hiding $option option")
            }
        }

        // Update the scrolling view in AppBarLayout to align with the current tab's
        // scrolling state. This prevents the lift state from being confused as one
        // goes between different tabs.
        binding.homeAppbar.liftOnScrollTargetViewId =
            when (tabMode) {
                MusicMode.SONGS -> R.id.home_song_recycler
                MusicMode.ALBUMS -> R.id.home_album_recycler
                MusicMode.ARTISTS -> R.id.home_artist_recycler
                MusicMode.GENRES -> R.id.home_genre_recycler
                MusicMode.PLAYLISTS -> R.id.home_playlist_recycler
            }

        if (tabMode != MusicMode.PLAYLISTS) {
            logD("Flipping to shuffle button")
            binding.homeFab.flipTo(R.drawable.ic_shuffle_off_24, R.string.desc_shuffle_all) {
                playbackModel.shuffleAll()
            }
        } else {
            logD("Flipping to playlist button")
            binding.homeFab.flipTo(R.drawable.ic_add_24, R.string.desc_new_playlist) {
                musicModel.createPlaylist()
            }
        }
    }

    private fun handleRecreate(recreate: Unit?) {
        if (recreate == null) return
        val binding = requireBinding()
        logD("Recreating ViewPager")
        // Move back to position zero, as there must be a tab there.
        binding.homePager.currentItem = 0
        // Make sure tabs are set up to also follow the new ViewPager configuration.
        setupPager(binding)
        homeModel.recreateTabs.consume()
    }

    private fun updateIndexerState(state: IndexingState?) {
        // TODO: Make music loading experience a bit more pleasant
        //  1. Loading placeholder for item lists
        //  2. Rework the "No Music" case to not be an error and instead result in a placeholder
        val binding = requireBinding()
        when (state) {
            is IndexingState.Completed -> setupCompleteState(binding, state.error)
            is IndexingState.Indexing -> setupIndexingState(binding, state.progress)
            null -> {
                logD("Indexer is in indeterminate state")
                binding.homeIndexingContainer.visibility = View.INVISIBLE
            }
        }
    }

    private fun setupCompleteState(binding: FragmentHomeBinding, error: Throwable?) {
        if (error == null) {
            logD("Received ok response")
            binding.homeFab.show()
            binding.homeIndexingContainer.visibility = View.INVISIBLE
            return
        }

        logD("Received non-ok response")
        val context = requireContext()
        binding.homeIndexingContainer.visibility = View.VISIBLE
        binding.homeIndexingProgress.visibility = View.INVISIBLE
        when (error) {
            is NoAudioPermissionException -> {
                logD("Showing permission prompt")
                binding.homeIndexingStatus.text = context.getString(R.string.err_no_perms)
                // Configure the action to act as a permission launcher.
                binding.homeIndexingAction.apply {
                    visibility = View.VISIBLE
                    text = context.getString(R.string.lbl_grant)
                    setOnClickListener {
                        requireNotNull(storagePermissionLauncher) {
                                "Permission launcher was not available"
                            }
                            .launch(PERMISSION_READ_AUDIO)
                    }
                }
            }
            is NoMusicException -> {
                logD("Showing no music error")
                binding.homeIndexingStatus.text = context.getString(R.string.err_no_music)
                // Configure the action to act as a reload trigger.
                binding.homeIndexingAction.apply {
                    visibility = View.VISIBLE
                    text = context.getString(R.string.lbl_retry)
                    setOnClickListener { musicModel.refresh() }
                }
            }
            else -> {
                logD("Showing generic error")
                binding.homeIndexingStatus.text = context.getString(R.string.err_index_failed)
                // Configure the action to act as a reload trigger.
                binding.homeIndexingAction.apply {
                    visibility = View.VISIBLE
                    text = context.getString(R.string.lbl_retry)
                    setOnClickListener { musicModel.rescan() }
                }
            }
        }
    }

    private fun setupIndexingState(binding: FragmentHomeBinding, progress: IndexingProgress) {
        // Remove all content except for the progress indicator.
        binding.homeIndexingContainer.visibility = View.VISIBLE
        binding.homeIndexingProgress.visibility = View.VISIBLE
        binding.homeIndexingAction.visibility = View.INVISIBLE

        when (progress) {
            is IndexingProgress.Indeterminate -> {
                // In a query/initialization state, show a generic loading status.
                binding.homeIndexingStatus.text = getString(R.string.lng_indexing)
                binding.homeIndexingProgress.isIndeterminate = true
            }
            is IndexingProgress.Songs -> {
                // Actively loading songs, show the current progress.
                binding.homeIndexingStatus.text =
                    getString(R.string.fmt_indexing, progress.current, progress.total)
                binding.homeIndexingProgress.apply {
                    isIndeterminate = false
                    max = progress.total
                    this.progress = progress.current
                }
            }
        }
    }

    private fun updateFab(songs: List<Song>, isFastScrolling: Boolean) {
        val binding = requireBinding()
        // If there are no songs, it's likely that the library has not been loaded, so
        // displaying the shuffle FAB makes no sense. We also don't want the fast scroll
        // popup to overlap with the FAB, so we hide the FAB when fast scrolling too.
        if (songs.isEmpty() || isFastScrolling) {
            logD("Hiding fab: [empty: ${songs.isEmpty()} scrolling: $isFastScrolling]")
            binding.homeFab.hide()
        } else {
            logD("Showing fab")
            binding.homeFab.show()
        }
    }

    private fun handleNavigation(item: Music?) {
        val action =
            when (item) {
                is Song -> HomeFragmentDirections.actionShowAlbum(item.album.uid)
                is Album -> HomeFragmentDirections.actionShowAlbum(item.uid)
                is Artist -> HomeFragmentDirections.actionShowArtist(item.uid)
                is Genre -> HomeFragmentDirections.actionShowGenre(item.uid)
                is Playlist -> HomeFragmentDirections.actionShowPlaylist(item.uid)
                null -> return
            }

        setupAxisTransitions(MaterialSharedAxis.X)
        findNavController().navigateSafe(action)
    }

    private fun updateSelection(selected: List<Music>) {
        val binding = requireBinding()
        if (selected.isNotEmpty()) {
            binding.homeSelectionToolbar.title = getString(R.string.fmt_selected, selected.size)
            if (binding.homeToolbar.setVisible(R.id.home_selection_toolbar)) {
                // New selection started, show the AppBarLayout to indicate the new state.
                logD("Significant selection occurred, expanding AppBar")
                binding.homeAppbar.expandWithScrollingRecycler()
            }
        } else {
            binding.homeToolbar.setVisible(R.id.home_normal_toolbar)
        }
    }

    private fun setupAxisTransitions(axis: Int) {
        // Sanity check to avoid in-correct axis transitions
        check(axis == MaterialSharedAxis.X || axis == MaterialSharedAxis.Z) {
            "Not expecting Y axis transition"
        }

        enterTransition = MaterialSharedAxis(axis, true)
        returnTransition = MaterialSharedAxis(axis, false)
        exitTransition = MaterialSharedAxis(axis, true)
        reenterTransition = MaterialSharedAxis(axis, false)
    }

    /**
     * [FragmentStateAdapter] implementation for the [HomeFragment]'s [ViewPager2] instance.
     *
     * @param tabs The current tab configuration. This will define the [Fragment]s created.
     * @param fragmentManager The [FragmentManager] required by [FragmentStateAdapter].
     * @param lifecycleOwner The [LifecycleOwner], whose Lifecycle is required by
     *   [FragmentStateAdapter].
     */
    private class HomePagerAdapter(
        private val tabs: List<MusicMode>,
        fragmentManager: FragmentManager,
        lifecycleOwner: LifecycleOwner
    ) : FragmentStateAdapter(fragmentManager, lifecycleOwner.lifecycle) {
        override fun getItemCount() = tabs.size
        override fun createFragment(position: Int): Fragment =
            when (tabs[position]) {
                MusicMode.SONGS -> SongListFragment()
                MusicMode.ALBUMS -> AlbumListFragment()
                MusicMode.ARTISTS -> ArtistListFragment()
                MusicMode.GENRES -> GenreListFragment()
                MusicMode.PLAYLISTS -> PlaylistListFragment()
            }
    }

    private companion object {
        val VP_RECYCLER_FIELD: Field by lazyReflectedField(ViewPager2::class, "mRecyclerView")
        val RV_TOUCH_SLOP_FIELD: Field by lazyReflectedField(RecyclerView::class, "mTouchSlop")
        const val KEY_LAST_TRANSITION_AXIS =
            BuildConfig.APPLICATION_ID + ".key.LAST_TRANSITION_AXIS"
    }
}
