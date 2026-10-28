package com.carcast.receiver

import android.view.Surface

/** 解码 Surface 的跨组件单例（Activity 创建，Service 消费）。 */
object SurfaceStore {
    @Volatile var surface: Surface? = null
}

/** 状态文本回调，服务线程 -> UI 线程。 */
object StatusBridge {
    @Volatile var listener: ((String?) -> Unit)? = null

    fun post(text: String?) {
        listener?.invoke(text)
    }
}
