package com.hongguo.watch.data

import com.hongguo.watch.data.model.StreamInfo
import com.hongguo.watch.upstream.Rendition

/** 一次取流结果：当前选中档 + 完整阶梯（失败时可本地再选）。 */
data class StreamSelection(
    val stream: StreamInfo,
    val renditions: List<Rendition>,
    val selectedKey: String,
)
