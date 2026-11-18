package com.hongguo.watch.ui

/**
 * 首页 / 库（收藏·历史）共用一个 RecyclerView adapter。
 * 切 Tab 时必须立刻清空，避免「推荐」标题下闪历史行，或收藏混入首页。
 */
object ListTabPolicy {
    /** 从库进入首页，或强制刷新时，必须清空 adapter。 */
    fun shouldClearHomeAdapter(switchingToHome: Boolean, forceReload: Boolean): Boolean =
        switchingToHome || forceReload
}
