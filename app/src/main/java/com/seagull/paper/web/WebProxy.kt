package com.seagull.paper.web

import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import java.util.concurrent.Executors

/**
 * 让 WebView 也走用户配的代理。
 *
 * 这个才是「浏览器能上 Google、App 里上不去」的根因：大部分人的 Clash 只是开了
 * HTTP 代理端口（7890），系统流量并不全走它 —— 浏览器自己配了代理所以好使，
 * 而 WebView 默认看系统代理(=直连)就歇了。
 */
object WebProxy {

    private val exec = Executors.newSingleThreadExecutor()

    fun apply(spec: String) {
        try {
            val s = spec.trim()
                .removePrefix("http://").removePrefix("https://")
                .removePrefix("socks5://").removePrefix("socks://").trim()
            if (s.isEmpty()) {
                ProxyController.getInstance().clearProxyOverride(exec) { }
                return
            }
            val host = s.substringBefore(":").trim()
            val port = s.substringAfter(":", "").trim().toIntOrNull() ?: -1
            if (host.isEmpty() || port <= 0) {
                ProxyController.getInstance().clearProxyOverride(exec) { }
                return
            }
            val cfg = ProxyConfig.Builder()
                .addProxyRule("$host:$port")
                .addDirect()   // 代理连不通时回落直连，国内站不受影响
                .build()
            ProxyController.getInstance().setProxyOverride(cfg, exec) { }
        } catch (e: Throwable) {
            // WebView 不支持就算了，不影响其它功能
        }
    }
}
