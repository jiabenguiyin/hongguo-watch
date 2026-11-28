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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 云端收藏夹：列表 / 新建 / 打开内容 / 改名·公开·删除。
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
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (openFolder != null) {
                        openFolder = null
                        folderItems = emptyList()
                        reload(fromSwipe = false)
                    } else {
                        finish()
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
        loadJob = lifecycleScope.launch {
            try {
                if (!loggedIn) {
                    renderGuest()
                    return@launch
                }
                if (current == null) {
                    val folders = container.redfruitAccount.libraryBookshelfFolders()
                        ?: run {
                            renderGuest()
                            return@launch
                        }
                    renderFolderList(folders)
                } else {
                    val pair = container.redfruitAccount.libraryBookshelfFolderItems(current.id)
                        ?: run {
                            renderGuest()
                            return@launch
                        }
                    val (meta, items) = pair
                    openFolder = meta?.copy(id = current.id) ?: current
                    folderItems = items
                    renderFolderDetail(openFolder!!, items)
                }
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
        // 未登录：禁用下拉「同步」假象，只留登录引导（对齐点赞/预约 need_login）
        binding.swipeRefresh.isEnabled = false
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
        binding.swipeRefresh.isEnabled = true
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
        binding.swipeRefresh.isEnabled = true
        binding.manageButton.visibility = View.VISIBLE
        binding.createButton.visibility = View.VISIBLE
        binding.createButton.text = getString(R.string.favorites_folder_add)
        binding.folderList.adapter = itemAdapter
        val rows = items.map { item ->
            HomeRow.Item(
                series = Series(
                    id = item.seriesId,
                    title = item.title,
                    cover = CoverUrls.normalize(item.cover),
                    description = "",
                    episodeCount = item.episodeCount,
                    source = SeriesSource.APP,
                ),
                totalEpisodes = item.episodeCount.takeIf { it > 0 },
            )
        }
        itemAdapter.submit(rows)
        if (rows.isEmpty()) {
            binding.emptyPanel.visibility = View.VISIBLE
            binding.emptyText.text = getString(R.string.favorites_folder_items_empty)
            binding.emptyAction.text = getString(R.string.favorites_folder_add)
            binding.emptyAction.setOnClickListener { showAddItemsPicker(folder) }
        } else {
            binding.emptyPanel.visibility = View.GONE
        }
    }

    private fun openFolder(folder: RedfruitBookshelfFolder) {
        openFolder = folder
        reload(fromSwipe = false)
    }

    private fun showManageSheet(folder: RedfruitBookshelfFolder): Boolean {
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

    private fun showCreateOrEditDialog(existing: RedfruitBookshelfFolder?) {
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
                    reload(fromSwipe = false)
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
                        }
                        reload(fromSwipe = false)
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
}
