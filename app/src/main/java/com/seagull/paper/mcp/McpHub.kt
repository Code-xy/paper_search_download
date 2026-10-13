package com.seagull.paper.mcp

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class McpState(
    val running: Boolean = false,
    val port: Int = 8765,
    val token: String = "",
    val calls: Int = 0,
    val error: String = "",
    val log: List<String> = emptyList()
)

/** MCP 服务的全局状态机：UI 看状态，Service 负责保活。 */
object McpHub {

    private val _state = MutableStateFlow(McpState())
    val state: StateFlow<McpState> = _state.asStateFlow()

    private var http: McpHttp? = null

    fun start(app: Application, port: Int, token: String): Boolean {
        stopQuiet()
        val srv = McpHttp(port, token, McpTools(app))
        val okStart = srv.start()
        http = srv
        _state.value = _state.value.copy(
            running = okStart,
            port = port,
            token = token,
            error = if (okStart) "" else ("绑定 127.0.0.1:" + port + " 失败：" + srv.lastError),
            log = emptyList()
        )
        log(if (okStart) "服务已启动 127.0.0.1:$port" else "启动失败：" + srv.lastError)
        return okStart
    }

    private fun stopQuiet() {
        try {
            http?.stop()
        } catch (e: Exception) {
            // ignore
        }
        http = null
    }

    fun stop() {
        val was = _state.value.running
        stopQuiet()
        _state.value = _state.value.copy(running = false)
        if (was) log("服务已停止")
    }

    fun countCall() {
        _state.value = _state.value.copy(calls = _state.value.calls + 1)
    }

    fun log(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val list = (_state.value.log + (ts + "  " + line)).takeLast(40)
        _state.value = _state.value.copy(log = list)
    }

    fun endpoint(port: Int): String = "http://127.0.0.1:$port/mcp"

    fun newToken(): String {
        val chars = "abcdef0123456789"
        return (1..24).map { chars.random() }.joinToString("")
    }
}
