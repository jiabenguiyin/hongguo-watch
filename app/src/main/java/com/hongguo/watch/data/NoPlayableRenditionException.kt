package com.hongguo.watch.data

import com.hongguo.watch.upstream.Rendition

/** 当前策略下无可用档；仍带回阶梯供自动切流 / 放宽策略后重选。 */
class NoPlayableRenditionException(
    message: String,
    val renditions: List<Rendition>,
) : IllegalStateException(message)
