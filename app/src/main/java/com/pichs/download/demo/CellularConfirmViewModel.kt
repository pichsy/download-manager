package com.pichs.download.demo

/**
 * 流量确认弹窗通信（单例）
 * 负责保存"用户确认后要执行的下载操作"。
 *
 * 回调直接同步执行：弹窗关闭时会取消自身协程作用域，
 * 之前用协程派发 confirm/deny 可被 onDismiss 抢先取消，导致点击无反应。
 */
object CellularConfirmViewModel {

    @Volatile
    var pendingAction: (() -> Unit)? = null

    /**
     * 用户确认
     */
    fun confirm() {
        val action = pendingAction
        pendingAction = null
        action?.invoke()
    }

    /**
     * 用户拒绝
     */
    fun deny() {
        pendingAction = null
    }

    /**
     * 清理
     */
    fun clear() {
        pendingAction = null
    }
}
