// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.hongguo.watch.HongguoWatchApp
import com.hongguo.watch.R
import com.hongguo.watch.data.CoverUrls
import com.hongguo.watch.data.model.Series
import com.hongguo.watch.data.model.SeriesSource
import com.hongguo.watch.data.redfruit.RedfruitBookshelfFolder
import com.hongguo.watch.data.redfruit.RedfruitBookshelfFolderItem
import com.hongguo.watch.databinding.ActivityFavoritesFoldersBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 云端收藏夹：列表 / 新建 / 打开内容 / 改名·公开·删除；
 * 夹内条目批量编辑：顶栏「编辑」与「管理」并排；进入后全选/完成 + 底栏移动到|删除。
 * 未登录明确引导登录，不假装已同步。
 */
class FavoritesFoldersActivity : WatchDisplayActivity() {
    private lateinit var binding: ActivityFavoritesFoldersBinding
    private val container get() = (application as HongguoWatchApp).container
    private val folderAdapter = FolderCardAdapter(
        onClick = ::openFolder,
        onLongClick = ::showManageSheet,
    )
    private val itemAdapter = SeriesListAdapter(
        onHeaderAction = { enterItemEditMode() },
        onItemLongClick = { series ->
            if (openFolder != null && !itemEditMode) {
                enterItemEditMode(preselect = series.id)
                true
            } else {
                false
            }
        },
        onSelectionToggle = { series -> toggleItemSelection(series.id) },
        onClick = { series ->
            startActivity(
                Intent(this, DetailActivity::class.java)
                    .putExtra(DetailActivity.EXTRA_SERIES_ID, series.id),
            )
        },
    )
    private var loadJob: Job? = null
    private var openFolder: RedfruitBookshelfFolder? = null
    private var folderItems: List<RedfruitBookshelfFolderItem> = emptyList()
    private var itemEditMode = false
    private val selectedSeriesIds = linkedSetOf<String>()
    private var posterSnapshot: List<HomeRow.Poster> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFavoritesFoldersBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
        WatchUi.applySafePadding(binding.foldersRoot)
        WatchToolbarClock(this, binding.toolbarClock)

        binding.folderList.layoutManager = LinearLayoutManager(this)
        binding.folderList.adapter = folderAdapter
        binding.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.swipeRefresh.setColorSchemeResources(R.color.accent)
        binding.swipeRefresh.setOnRefreshListener { reload(fromSwipe = true) }
        binding.createButton.setOnClickListener { onCreateClicked() }
        binding.emptyAction.setOnClickListener { onEmptyAction() }
        binding.manageButton.setOnClickListener {
            openFolder?.let(::showManageSheet)
        }
        binding.editButton.setOnClickListener { enterItemEditMode() }
        binding.selectAllButton.setOnClickListener { toggleSelectAllItems() }
        binding.editDoneButton.setOnClickListener { exitItemEditMode(reload = true) }
        binding.moveBar.setOnClickListener { showMoveSheet() }
        binding.deleteBar.setOnClickListener { confirmDeleteSelectedItems() }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        itemEditMode -> exitItemEditMode(reload = true)
                        openFolder != null -> {
                            openFolder = null
                            folderItems = emptyList()
                            exitItemEditMode(reload = false)
                            reload(fromSwipe = false)
                        }
                        else -> finish()
                    }
                }
            },
        )
        reload(fromSwipe = false)
    }

    override fun onResume() {
        super.onResume()
        recreateForDisplaySizeChange()
        if (openFolder == null) reload(fromSwipe = false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
    }

    private fun onEmptyAction() {
        if (!container.redfruitAccount.hasCloudSession()) {
            startActivity(Intent(this, AccountSyncActivity::class.java))
            return
        }
        showCreateOrEditDialog(existing = null)
    }

    private fun onCreateClicked() {
        if (!container.redfruitAccount.hasCloudSession()) {
            Toast.makeText(this, R.string.favorites_folders_need_login, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, AccountSyncActivity::class.java))
            return
        }
        if (itemEditMode) return
        val folder = openFolder
        if (folder != null) {
            showAddItemsPicker(folder)
        } else {
            showCreateOrEditDialog(existing = null)
        }
    }

    private fun reload(fromSwipe: Boolean) {
        loadJob?.cancel()
        val loggedIn = container.redfruitAccount.hasCloudSession()
        if (!fromSwipe) {
            binding.loading.visibility = View.VISIBLE
            binding.emptyPanel.visibility = View.GONE
        }
        binding.swipeRefresh.isRefreshing = fromSwipe
        val current = openFolder
        val keepEdit = itemEditMode && current != null
        loadJob = lifecycleScope.launch {
            try {
                if (!loggedIn) {
                    exitItemEditMode(reload = false)
                    renderGuest()
                    return@launch
                }
                if (current == null) {
                    exitItemEditMode(reload = false)
                    val folders = container.redfruitAccount.libraryBookshelfFolders()
                        ?: run {
                            renderGuest()
                            return@launch
                        }
                    renderFolderList(folders)
                } else {
                    val pair = container.redfruitAccount.libraryBookshelfFolderItems(current.id)
                        ?: run {
                            exitItemEditMode(reload = false)
                            renderGuest()
                            return@launch
                        }
                    val (meta, items) = pair
                    openFolder = meta?.copy(id = current.id) ?: current
                    folderItems = items
                    if (keepEdit) {
                        selectedSeriesIds.retainAll(items.map { it.seriesId }.toSet())
                        itemAdapter.selectionMode = true
                    } else {
                        exitItemEditMode(reload = false)
                    }
                    renderFolderDetail(openFolder!!, items)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = error.message?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.favorites_folders_load_failed)
                Toast.makeText(this@FavoritesFoldersActivity, message, Toast.LENGTH_SHORT).show()
                if (current == null) {
                    binding.emptyPanel.visibility = View.VISIBLE
                    binding.emptyText.text = getString(R.string.favorites_folders_load_failed)
                    binding.emptyAction.text = getString(R.string.favorites_folder_retry)
                    binding.emptyAction.setOnClickListener { reload(fromSwipe = false) }
                    binding.createButton.visibility = View.GONE
                    folderAdapter.submit(emptyList())
                }
            } finally {
                binding.loading.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun renderGuest() {
        openFolder = null
        binding.titleView.text = getString(R.string.favorites_folders_title)
        binding.subtitleView.visibility = View.GONE
        binding.manageButton.visibility = View.GONE
        binding.createButton.visibility = View.GONE
        hideItemEditChrome()
        // 未登录：禁用下拉「同步」假象，只留登录引导（对齐点赞/预约 need_login）
        binding.swipeRefresh.isEnabled = false
        applyListLayout()
        binding.folderList.adapter = folderAdapter
        folderAdapter.submit(emptyList())
        binding.emptyPanel.visibility = View.VISIBLE
        binding.emptyText.text = getString(R.string.favorites_folders_need_login)
        binding.emptyAction.text = getString(R.string.favorites_folders_go_login)
        binding.emptyAction.setOnClickListener {
            startActivity(Intent(this, AccountSyncActivity::class.java))
        }
    }

    private fun renderFolderList(folders: List<RedfruitBookshelfFolder>) {
        binding.titleView.text = getString(R.string.favorites_folders_title)
        binding.subtitleView.visibility = View.GONE
        binding.manageButton.visibility = View.GONE
        hideItemEditChrome()
        binding.swipeRefresh.isEnabled = true
        applyListLayout()
        binding.folderList.adapter = folderAdapter
        folderAdapter.submit(folders)
        binding.createButton.visibility = View.VISIBLE
        binding.createButton.text = getString(R.string.favorites_folder_create_plus)
        if (folders.isEmpty()) {
            binding.emptyPanel.visibility = View.VISIBLE
            binding.emptyText.text = getString(R.string.favorites_folders_empty)
            binding.emptyAction.text = getString(R.string.favorites_folder_create)
            binding.emptyAction.setOnClickListener { showCreateOrEditDialog(existing = null) }
            binding.createButton.visibility = View.GONE
        } else {
            binding.emptyPanel.visibility = View.GONE
        }
    }

    private fun renderFolderDetail(folder: RedfruitBookshelfFolder, items: List<RedfruitBookshelfFolderItem>) {
        binding.titleView.text = folder.name
        binding.subtitleView.visibility = View.VISIBLE
        binding.subtitleView.text = getString(
            R.string.favorites_folder_detail_meta,
            folder.itemCount.takeIf { it > 0 } ?: items.size,
            getString(if (folder.published) R.string.favorites_folder_public_label else R.string.favorites_folder_private_label),
        )
        binding.swipeRefresh.isEnabled = !itemEditMode
        val showFolderChrome = !itemEditMode
        binding.manageButton.visibility = if (showFolderChrome) View.VISIBLE else View.GONE
        binding.editButton.visibility =
            if (showFolderChrome && items.isNotEmpty()) View.VISIBLE else View.GONE
        applyPosterLayout()
        binding.folderList.adapter = itemAdapter
        itemAdapter.selectionMode = itemEditMode
        posterSnapshot = items.map { item ->
            HomeRow.Poster(
                series = Series(
                    id = item.seriesId,
                    title = item.title,
                    cover = CoverUrls.normalize(item.cover),
                    description = "",
                    episodeCount = item.episodeCount,
                    source = SeriesSource.APP,
                ),
                totalEpisodes = item.episodeCount.takeIf { it > 0 },
                selected = item.seriesId in selectedSeriesIds,
                metaLabel = if (item.episodeCount > 0) {
                    getString(R.string.favorite_progress_total, item.episodeCount)
                } else {
                    null
                },
            )
        }
        submitItemRows()
        if (items.isEmpty()) {
            binding.emptyPanel.visibility = View.VISIBLE
            binding.emptyText.text = getString(R.string.favorites_folder_items_empty)
            binding.emptyAction.text = getString(R.string.favorites_folder_add)
            binding.emptyAction.setOnClickListener { showAddItemsPicker(folder) }
            binding.createButton.visibility = View.GONE
            hideItemEditChrome()
        } else {
            binding.emptyPanel.visibility = View.GONE
            refreshItemEditChrome()
            if (itemEditMode) {
                binding.createButton.visibility = View.GONE
            } else {
                binding.createButton.visibility = View.VISIBLE
                binding.createButton.text = getString(R.string.favorites_folder_add)
            }
        }
    }

    private fun submitItemRows() {
        val posters = posterSnapshot.map { row ->
            row.copy(selected = row.series.id in selectedSeriesIds)
        }
        posterSnapshot = posters
        // 编辑态顶栏已是「全选/已选择/完成」，网格不再叠 Header（对齐官方）
        // 「编辑」已挪到顶栏与「管理」并排，Header 不再重复入口
        if (itemEditMode) {
            itemAdapter.submit(posters)
            return
        }
        val header = HomeRow.Header(
            title = openFolder?.name.orEmpty().ifBlank { getString(R.string.favorites_folders_title) },
            subtitle = getString(R.string.favorites_count, posters.size),
        )
        itemAdapter.submit(listOf(header) + posters)
    }

    private fun enterItemEditMode(preselect: String? = null) {
        if (openFolder == null || folderItems.isEmpty()) return
        itemEditMode = true
        selectedSeriesIds.clear()
        if (!preselect.isNullOrBlank()) selectedSeriesIds += preselect
        itemAdapter.selectionMode = true
        binding.swipeRefresh.isEnabled = false
        binding.manageButton.visibility = View.GONE
        binding.editButton.visibility = View.GONE
        binding.createButton.visibility = View.GONE
        refreshItemEditChrome()
        submitItemRows()
    }

    private fun exitItemEditMode(reload: Boolean) {
        val wasEditing = itemEditMode
        itemEditMode = false
        selectedSeriesIds.clear()
        itemAdapter.selectionMode = false
        binding.backButton.visibility = View.VISIBLE
        hideItemEditChrome()
        if (wasEditing && reload && openFolder != null) {
            reload(fromSwipe = false)
        }
    }

    private fun hideItemEditChrome() {
        binding.editButton.visibility = View.GONE
        binding.editBar.visibility = View.GONE
        binding.itemActionBar.visibility = View.GONE
    }

    private fun refreshItemEditChrome() {
        if (openFolder == null || folderItems.isEmpty()) {
            hideItemEditChrome()
            return
        }
        if (!itemEditMode) {
            binding.editBar.visibility = View.GONE
            binding.itemActionBar.visibility = View.GONE
            binding.editButton.visibility = View.VISIBLE
            binding.manageButton.visibility = View.VISIBLE
            binding.backButton.visibility = View.VISIBLE
            binding.subtitleView.visibility = View.VISIBLE
            return
        }
        binding.editButton.visibility = View.GONE
        binding.editBar.visibility = View.VISIBLE
        binding.itemActionBar.visibility = View.VISIBLE
        // 顶栏对齐官方：全选 | 夹名+已选择N个 | 完成（夹名仍用 titleView）
        binding.backButton.visibility = View.GONE
        binding.manageButton.visibility = View.GONE
        binding.subtitleView.visibility = View.GONE
        val n = selectedSeriesIds.size
        binding.selectedCount.text = getString(R.string.favorites_folder_selected_count, n)
        val allIds = posterSnapshot.map { it.series.id }.toSet()
        val allSelected = allIds.isNotEmpty() && selectedSeriesIds.containsAll(allIds)
        binding.selectAllButton.text = getString(
            if (allSelected) R.string.library_deselect_all else R.string.library_select_all,
        )
        val enabled = n > 0
        binding.moveBar.isEnabled = enabled
        binding.deleteBar.isEnabled = enabled
        binding.moveBar.alpha = if (enabled) 1f else 0.4f
        binding.deleteBar.alpha = if (enabled) 1f else 0.4f
    }

    private fun toggleItemSelection(seriesId: String) {
        if (!itemEditMode) return
        if (seriesId in selectedSeriesIds) selectedSeriesIds.remove(seriesId)
        else selectedSeriesIds += seriesId
        submitItemRows()
        refreshItemEditChrome()
    }

    private fun toggleSelectAllItems() {
        if (!itemEditMode) return
        val allIds = posterSnapshot.map { it.series.id }
        if (selectedSeriesIds.containsAll(allIds) && allIds.isNotEmpty()) {
            selectedSeriesIds.clear()
        } else {
            selectedSeriesIds.clear()
            selectedSeriesIds.addAll(allIds)
        }
        submitItemRows()
        refreshItemEditChrome()
    }

    private fun selectedFolderItems(): List<RedfruitBookshelfFolderItem> {
        val ids = selectedSeriesIds
        return folderItems.filter { it.seriesId in ids }
    }

    private fun confirmDeleteSelectedItems() {
        val folder = openFolder ?: return
        val toRemove = selectedFolderItems()
        if (toRemove.isEmpty()) return
        val dialog = AlertDialog.Builder(this, R.style.Theme_HongguoWatch_AlertDialog)
            .setTitle(R.string.favorites_folder_items_delete_confirm_title)
            .setMessage(getString(R.string.favorites_folder_items_delete_confirm_body, toRemove.size))
            .setPositiveButton(R.string.library_delete) { _, _ ->
                lifecycleScope.launch {
                    try {
                        container.redfruitAccount.removeBookshelfFolderItems(folder.id, toRemove)
                        Toast.makeText(
                            this@FavoritesFoldersActivity,
                            getString(R.string.favorites_folder_items_deleted_count, toRemove.size),
                            Toast.LENGTH_SHORT,
                        ).show()
                        exitItemEditMode(reload = true)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Toast.makeText(
                            this@FavoritesFoldersActivity,
                            error.message ?: getString(R.string.favorites_folder_save_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.update_cancel, null)
            .create()
        showWatchDialog(dialog)
    }

    /** 官方「选择收藏夹」：列表 + 新建；手表深色对话框。 */
    private fun showMoveSheet() {
        val origin = openFolder ?: return
        val moving = selectedFolderItems()
        if (moving.isEmpty()) return
        lifecycleScope.launch {
            try {
                val folders = container.redfruitAccount.libraryBookshelfFolders().orEmpty()
                val destinations = folders.filter { it.id != origin.id }
                presentMoveSheet(origin, moving, destinations)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Toast.makeText(
                    this@FavoritesFoldersActivity,
                    error.message ?: getString(R.string.favorites_folder_save_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun presentMoveSheet(
        origin: RedfruitBookshelfFolder,
        moving: List<RedfruitBookshelfFolderItem>,
        destinations: List<RedfruitBookshelfFolder>,
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_favorites_folder_pick, null)
        val list = view.findViewById<RecyclerView>(R.id.pickList)
        val empty = view.findViewById<TextView>(R.id.pickEmpty)
        val dialog = AlertDialog.Builder(this, R.style.Theme_HongguoWatch_AlertDialog)
            .setView(view)
            .create()
        list.layoutManager = LinearLayoutManager(this)
        fun bindDestinations(rows: List<RedfruitBookshelfFolder>) {
            empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            list.adapter = FolderPickAdapter(rows) { target ->
                dialog.dismiss()
                moveSelectedItems(origin, target, moving)
            }
        }
        bindDestinations(destinations)
        view.findViewById<TextView>(R.id.pickClose).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.pickCreate).setOnClickListener {
            dialog.dismiss()
            showCreateOrEditDialog(existing = null) {
                // 新建后重新打开选择表，便于立刻移入新夹
                showMoveSheet()
            }
        }
        showWatchDialog(dialog)
    }

    private fun moveSelectedItems(
        origin: RedfruitBookshelfFolder,
        target: RedfruitBookshelfFolder,
        moving: List<RedfruitBookshelfFolderItem>,
    ) {
        lifecycleScope.launch {
            try {
                container.redfruitAccount.moveBookshelfFolderItems(origin.id, target.id, moving)
                Toast.makeText(
                    this@FavoritesFoldersActivity,
                    getString(R.string.favorites_folder_moved_count, moving.size),
                    Toast.LENGTH_SHORT,
                ).show()
                exitItemEditMode(reload = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Toast.makeText(
                    this@FavoritesFoldersActivity,
                    error.message ?: getString(R.string.favorites_folder_save_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun applyListLayout() {
        val current = binding.folderList.layoutManager
        if (current !is LinearLayoutManager || current is GridLayoutManager) {
            binding.folderList.layoutManager = LinearLayoutManager(this)
        }
    }

    private fun applyPosterLayout() {
        val current = binding.folderList.layoutManager as? GridLayoutManager
        if (current != null && current.spanCount == POSTER_SPAN) {
            current.spanSizeLookup = posterSpanLookup
            return
        }
        binding.folderList.layoutManager = GridLayoutManager(this, POSTER_SPAN).apply {
            spanSizeLookup = posterSpanLookup
        }
    }

    private val posterSpanLookup = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int): Int =
            itemAdapter.spanSizeFor(position, POSTER_SPAN)
    }

    private fun openFolder(folder: RedfruitBookshelfFolder) {
        exitItemEditMode(reload = false)
        openFolder = folder
        reload(fromSwipe = false)
    }

    private fun showManageSheet(folder: RedfruitBookshelfFolder): Boolean {
        if (itemEditMode) return true
        val options = arrayOf(
            getString(R.string.favorites_folder_rename),
            getString(
                if (folder.published) R.string.favorites_folder_set_private
                else R.string.favorites_folder_set_public,
            ),
            getString(R.string.favorites_folder_delete),
        )
        val dialog = AlertDialog.Builder(this, R.style.Theme_HongguoWatch_AlertDialog)
            .setTitle(folder.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showCreateOrEditDialog(existing = folder)
                    1 -> togglePrivacy(folder)
                    2 -> confirmDelete(folder)
                }
            }
            .setNegativeButton(R.string.update_cancel, null)
            .create()
        showWatchDialog(dialog)
        return true
    }

    private fun showCreateOrEditDialog(
        existing: RedfruitBookshelfFolder?,
        onCreated: (() -> Unit)? = null,
    ) {
        if (!container.redfruitAccount.hasCloudSession()) {
            Toast.makeText(this, R.string.favorites_folders_need_login, Toast.LENGTH_SHORT).show()
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_favorites_folder_edit, null)
        val input = view.findViewById<EditText>(R.id.folderNameInput)
        val counter = view.findViewById<TextView>(R.id.folderNameCounter)
        val publishSwitch = view.findViewById<SwitchCompat>(R.id.folderPublicSwitch)
        input.setText(existing?.name.orEmpty())
        publishSwitch.isChecked = existing?.published ?: true
        fun refreshCounter() {
            val len = input.text?.length ?: 0
            counter.text = getString(R.string.favorites_folder_name_counter, len)
        }
        refreshCounter()
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refreshCounter()
        })
        val dialog = AlertDialog.Builder(this, R.style.Theme_HongguoWatch_AlertDialog)
            .setTitle(
                if (existing == null) R.string.favorites_folder_create
                else R.string.favorites_folder_rename,
            )
            .setView(view)
            .setPositiveButton(R.string.favorites_folder_confirm, null)
            .setNegativeButton(R.string.update_cancel, null)
            .create()
        showWatchDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val name = input.text?.toString().orEmpty()
            val publish = publishSwitch.isChecked
            lifecycleScope.launch {
                try {
                    if (existing == null) {
                        container.redfruitAccount.createBookshelfFolder(name, publish)
                        Toast.makeText(
                            this@FavoritesFoldersActivity,
                            R.string.favorites_folder_created,
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        container.redfruitAccount.updateBookshelfFolder(existing.id, name, publish)
                        Toast.makeText(
                            this@FavoritesFoldersActivity,
                            R.string.favorites_folder_updated,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    dialog.dismiss()
                    if (existing == null && onCreated != null) {
                        onCreated.invoke()
                    } else {
                        reload(fromSwipe = false)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Toast.makeText(
                        this@FavoritesFoldersActivity,
                        error.message ?: getString(R.string.favorites_folder_save_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun togglePrivacy(folder: RedfruitBookshelfFolder) {
        lifecycleScope.launch {
            try {
                container.redfruitAccount.updateBookshelfFolder(
                    folder.id,
                    folder.name,
                    publish = !folder.published,
                )
                Toast.makeText(
                    this@FavoritesFoldersActivity,
                    if (folder.published) R.string.favorites_folder_now_private
                    else R.string.favorites_folder_now_public,
                    Toast.LENGTH_SHORT,
                ).show()
                reload(fromSwipe = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Toast.makeText(
                    this@FavoritesFoldersActivity,
                    error.message ?: getString(R.string.favorites_folder_save_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun confirmDelete(folder: RedfruitBookshelfFolder) {
        val dialog = AlertDialog.Builder(this, R.style.Theme_HongguoWatch_AlertDialog)
            .setTitle(R.string.favorites_folder_delete)
            .setMessage(getString(R.string.favorites_folder_delete_confirm, folder.name))
            .setPositiveButton(R.string.favorites_folder_delete) { _, _ ->
                lifecycleScope.launch {
                    try {
                        container.redfruitAccount.deleteBookshelfFolder(folder.id)
                        Toast.makeText(
                            this@FavoritesFoldersActivity,
                            R.string.favorites_folder_deleted,
                            Toast.LENGTH_SHORT,
                        ).show()
                        if (openFolder?.id == folder.id) {
                            openFolder = null
                            folderItems = emptyList()
                            exitItemEditMode(reload = false)
                        }
                        reload(fromSwipe = false)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Toast.makeText(
                            this@FavoritesFoldersActivity,
                            error.message ?: getString(R.string.favorites_folder_save_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.update_cancel, null)
            .create()
        showWatchDialog(dialog)
    }

    private fun showAddItemsPicker(folder: RedfruitBookshelfFolder) {
        lifecycleScope.launch {
            try {
                val favorites = container.redfruitAccount.libraryFavorites()
                val existing = folderItems.map { it.seriesId }.toSet()
                val candidates = favorites.filter { it.seriesId !in existing }
                if (candidates.isEmpty()) {
                    Toast.makeText(
                        this@FavoritesFoldersActivity,
                        R.string.favorites_folder_add_none,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@launch
                }
                val labels = candidates.map { it.seriesTitle.ifBlank { it.seriesId } }.toTypedArray()
                val checked = BooleanArray(labels.size)
                val dialog = AlertDialog.Builder(this@FavoritesFoldersActivity, R.style.Theme_HongguoWatch_AlertDialog)
                    .setTitle(R.string.favorites_folder_add)
                    .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton(R.string.favorites_folder_confirm) { _, _ ->
                        val ids = candidates.mapIndexedNotNull { index, fav ->
                            fav.seriesId.takeIf { checked[index] }
                        }
                        if (ids.isEmpty()) return@setPositiveButton
                        lifecycleScope.launch {
                            try {
                                container.redfruitAccount.addBookshelfFolderItems(folder.id, ids)
                                Toast.makeText(
                                    this@FavoritesFoldersActivity,
                                    getString(R.string.favorites_folder_added_count, ids.size),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                reload(fromSwipe = false)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                Toast.makeText(
                                    this@FavoritesFoldersActivity,
                                    error.message ?: getString(R.string.favorites_folder_save_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                    .setNegativeButton(R.string.update_cancel, null)
                    .create()
                showWatchDialog(dialog)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Toast.makeText(
                    this@FavoritesFoldersActivity,
                    error.message ?: getString(R.string.favorites_folder_save_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun showWatchDialog(dialog: AlertDialog) {
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isAllCaps = false
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isAllCaps = false
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isAllCaps = false
        }
        dialog.show()
        dialog.window?.let { window ->
            window.setLayout(WatchUi.dialogContentWidthPx(this), WindowManager.LayoutParams.WRAP_CONTENT)
            window.setBackgroundDrawableResource(R.drawable.bg_alert_dialog)
        }
    }

    private class FolderPickAdapter(
        private val rows: List<RedfruitBookshelfFolder>,
        private val onClick: (RedfruitBookshelfFolder) -> Unit,
    ) : RecyclerView.Adapter<FolderPickAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_favorites_folder_pick, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(rows[position], onClick)
        }

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cover = itemView.findViewById<ImageView>(R.id.pickCover)
            private val name = itemView.findViewById<TextView>(R.id.pickName)
            private val meta = itemView.findViewById<TextView>(R.id.pickMeta)

            fun bind(folder: RedfruitBookshelfFolder, onClick: (RedfruitBookshelfFolder) -> Unit) {
                name.text = folder.name
                val privacy = itemView.context.getString(
                    if (folder.published) R.string.favorites_folder_public_label
                    else R.string.favorites_folder_private_label,
                )
                meta.text = itemView.context.getString(
                    R.string.favorites_folder_card_meta,
                    folder.itemCount,
                    privacy,
                )
                val url = folder.covers.firstOrNull().orEmpty()
                if (url.isBlank()) {
                    cover.setImageDrawable(null)
                    cover.setBackgroundResource(R.drawable.bg_cover)
                } else {
                    cover.load(CoverUrls.normalize(url)) {
                        transformations(RoundedCornersTransformation(4f))
                        crossfade(true)
                    }
                }
                itemView.setOnClickListener { onClick(folder) }
            }
        }
    }

    private class FolderCardAdapter(
        private val onClick: (RedfruitBookshelfFolder) -> Unit,
        private val onLongClick: (RedfruitBookshelfFolder) -> Boolean,
    ) : RecyclerView.Adapter<FolderCardAdapter.Holder>() {
        private val rows = mutableListOf<RedfruitBookshelfFolder>()

        fun submit(folders: List<RedfruitBookshelfFolder>) {
            rows.clear()
            rows.addAll(folders)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_favorites_folder, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(rows[position], onClick, onLongClick)
        }

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val name = itemView.findViewById<TextView>(R.id.folderName)
            private val meta = itemView.findViewById<TextView>(R.id.folderMeta)
            private val covers = listOf(
                itemView.findViewById<ImageView>(R.id.cover0),
                itemView.findViewById<ImageView>(R.id.cover1),
                itemView.findViewById<ImageView>(R.id.cover2),
                itemView.findViewById<ImageView>(R.id.cover3),
            )

            fun bind(
                folder: RedfruitBookshelfFolder,
                onClick: (RedfruitBookshelfFolder) -> Unit,
                onLongClick: (RedfruitBookshelfFolder) -> Boolean,
            ) {
                name.text = folder.name
                val privacy = itemView.context.getString(
                    if (folder.published) R.string.favorites_folder_public_label
                    else R.string.favorites_folder_private_label,
                )
                meta.text = itemView.context.getString(
                    R.string.favorites_folder_card_meta,
                    folder.itemCount,
                    privacy,
                )
                for (index in covers.indices) {
                    val coverView = covers[index]
                    val url = folder.covers.getOrNull(index).orEmpty()
                    if (url.isBlank()) {
                        coverView.setImageDrawable(null)
                        coverView.setBackgroundResource(R.drawable.bg_cover)
                    } else {
                        coverView.load(CoverUrls.normalize(url)) {
                            transformations(RoundedCornersTransformation(6f))
                            crossfade(true)
                        }
                    }
                }
                itemView.setOnClickListener { onClick(folder) }
                itemView.setOnLongClickListener { onLongClick(folder) }
            }
        }
    }

    companion object {
        private const val POSTER_SPAN = 3
    }
}
