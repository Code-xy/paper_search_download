package com.seagull.paper

import android.app.Application

/** 极简全局 App 引用：有源（如 Google 学术）需要 WebView 抓取，object 源拿不到 Context。 */
object AppRef {
    @Volatile
    private var app: Application? = null

    fun set(a: Application) {
        app = a
    }

    val get: Application?
        get() = app
}
