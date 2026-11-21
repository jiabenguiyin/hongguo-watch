package com.hongguo.watch.ui

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hongguo.watch.HongguoWatchApp
import com.hongguo.watch.R
import com.hongguo.watch.data.redfruit.RedfruitAccountState
import com.hongguo.watch.databinding.ActivityMineBinding
import kotlinx.coroutines.launch

class MineActivity : WatchDisplayActivity() {
    private lateinit var binding: ActivityMineBinding
    private val container get() = (application as HongguoWatchApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMineBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
        WatchUi.applySafePadding(binding.mineRoot)
        WatchToolbarClock(this, binding.toolbarClock)

        binding.backButton.setOnClickListener { finish() }
        binding.accountCard.setOnClickListener {
            startActivity(Intent(this, AccountSyncActivity::class.java))
        }
        binding.favoritesButton.setOnClickListener {
            openLibrary(LibrarySegmentPolicy.Segment.FAVORITES)
        }
        binding.historyButton.setOnClickListener {
            openLibrary(LibrarySegmentPolicy.Segment.HISTORY)
        }
        binding.offlineButton.setOnClickListener {
            openLibrary(LibrarySegmentPolicy.Segment.OFFLINE)
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        render(container.redfruitAccount.status.value)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.redfruitAccount.status.collect(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        recreateForDisplaySizeChange()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
    }

    private fun render(state: RedfruitAccountState) {
        val name = state.accountName
        binding.accountName.text = if (name.isNullOrBlank()) {
            getString(R.string.mine_account_title)
        } else {
            name
        }
        binding.accountHint.text = when {
            state.restoring || state.busy && name.isNullOrBlank() ->
                getString(R.string.mine_account_loading)
            !name.isNullOrBlank() && state.history != null && state.favorites != null ->
                getString(
                    R.string.mine_account_signed_in,
                    name,
                    state.favorites.size,
                    state.history.size,
                )
            !name.isNullOrBlank() -> getString(R.string.account_sync_signed_in, name)
            else -> getString(R.string.mine_account_signed_out)
        }
    }

    private fun openLibrary(segment: LibrarySegmentPolicy.Segment) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_OPEN_LIBRARY_SEGMENT, segment.name)
            },
        )
    }
}
