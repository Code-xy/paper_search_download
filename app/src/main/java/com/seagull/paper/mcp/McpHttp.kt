package com.seagull.paper.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 内嵌 MCP 服务器：JSON-RPC 2.0 over Streamable HTTP，只绑 127.0.0.1。
 * 不引任何 MCP 库——协议就这么几个方法，自己实现反而干净、可控、APK 不膨胀。
 *
 * 支持：initialize / ping / tools/list / tools/call / notifications.* / GET(SSE 占位) / DELETE(会话结束)
 */
class McpHttp(
    private val port: Int,
    private val token: String,
    private val tools: McpTools
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null

    @Volatile
    var running = false
        private set

    @Volatile
    var lastError = ""

    @Volatile
    var connections = 0
        private set

    fun start(): Boolean = try {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        server = s
        running = true
        scope.launch {
            while (running) {
                val sock = try {
                    s.accept()
                } catch (e: Exception) {
                    break
                }
                connections++
                scope.launch { serve(sock) }
            }
        }
        true
    } catch (e: Exception) {
        lastError = e.message ?: e.javaClass.simpleName
        false
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (e: Exception) {
            // ignore
        }
        scope.cancel()
    }

    // ── 连接处理 ─────────────────────────────────────────────

    private suspend fun serve(sock: Socket) {
        try {
            sock.use { s ->
                s.tcpNoDelay = true
                s.soTimeout = 0
                val input = s.getInputStream()
                val out = s.getOutputStream()
                while (running) {
                    val requestLine = readLine(input) ?: break
                    if (requestLine.isBlank()) continue
                    val parts = requestLine.split(" ")
                    if (parts.size < 3) break
                    val method = parts[0].uppercase()
                    val path = parts[1]
                    val headers = HashMap<String, String>()
                    while (true) {
                        val h = readLine(input) ?: break
                        if (h.isEmpty()) break
                        val i = h.indexOf(':')
                        if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
                    }

                    if (method == "GET") {
                        // Streamable HTTP 的服务器推送通道：我们从不主动推，挂着保温即可
                        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n" +
                                "Connection: keep-alive\r\n\r\n"
                        out.write(head.toByteArray())
                        out.flush()
                        keepAliveStream(out)
                        return
                    }

                    val len = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = if (len > 0) String(readExactly(input, len), Charsets.UTF_8) else ""

                    if (method == "OPTIONS") {
                        respond(out, 204, null, headers)
                        continue
                    }
                    if (method == "DELETE") {
                        respond(out, 200, "{}", headers)
                        continue
                    }
                    if (!path.startsWith("/mcp")) {
                        respond(out, 404, """{"error":"use POST /mcp"}""", headers)
                        continue
                    }
                    if (!originOk(headers)) {
                        respond(out, 403, """{"error":"origin not allowed"}""", headers)
                        continue
                    }
                    if (!authOk(headers, path)) {
                        respond(out, 401, """{"error":"unauthorized"}""", headers)
                        continue
                    }

                    val reply = dispatch(body)
                    if (reply == null) {
                        respond(out, 202, null, headers)
                    } else {
                        respond(out, 200, reply, headers)
                    }
                }
            }
        } catch (e: Exception) {
            // 单连接出错不影响服务器
        }
    }

    private fun keepAliveStream(out: OutputStream) {
        try {
            while (running) {
                out.write(": keep-alive\n\n".toByteArray())
                out.flush()
                Thread.sleep(15000L)
            }
        } catch (e: Exception) {
            // 客户端断开
        }
    }

    // ── JSON-RPC 分发 ────────────────────────────────────────

    private suspend fun dispatch(body: String): String? {
        if (body.isBlank()) return err(null, -32700, "empty body")
        val req = try {
            JSONObject(body)
        } catch (e: Exception) {
            return err(null, -32700, "parse error")
        }
        val id = req.opt("id")
        val method = req.optString("method")

        return when (method) {
            "initialize" -> {
                val reqVer = req.optJSONObject("params")?.optString("protocolVersion").orEmpty()
                val ver = if (reqVer.startsWith("202")) reqVer else LATEST
                val result = JSONObject()
                    .put("protocolVersion", ver)
                    .put(
                        "capabilities",
                        JSONObject().put("tools", JSONObject().put("listChanged", false))
                    )
                    .put(
                        "serverInfo",
                        JSONObject().put("name", "paper-search").put("title", "学术搜 MCP").put("version", APP_VERSION)
                    )
                    .put(
                        "instructions",
                        "手机上的论文聚合搜索。search_papers 并发查 arXiv/Google 学术/OpenAlex/Europe PMC/Crossref/DOAJ 等；" +
                                "resolve_pdf 跑全文解析链（Unpaywall→OpenAlex→Europe PMC→预印本→Sci-Hub→落地页）；" +
                                "download_pdf 下载到本机；read_paper 抽正文或章节。国内网络先用 diagnose 看链路。"
                    )
                McpHub.log("initialize ← " + (req.optJSONObject("params")?.optJSONObject("clientInfo")?.optString("name") ?: "client"))
                ok(id, result)
            }

            "notifications/initialized", "notifications/cancelled", "notifications/progress",
            "notifications/roots/list_changed", "logging/setLevel", "completion/complete" -> null

            "ping" -> ok(id, JSONObject())

            "tools/list" -> {
                McpHub.log("tools/list")
                ok(id, JSONObject().put("tools", tools.definitions()))
            }

            "tools/call" -> {
                val params = req.optJSONObject("params")
                val name = params?.optString("name").orEmpty()
                val args = params?.optJSONObject("arguments") ?: JSONObject()
                if (name.isEmpty()) return err(id, -32602, "缺少工具名")
                McpHub.log("tools/call → " + name)
                val result = tools.call(name, args)
                ok(id, result)
            }

            "" -> err(id, -32600, "缺少 method")

            else -> {
                // resources / prompts 我们没实现，按协议回空列表比报错友好
                when (method) {
                    "resources/list" -> ok(id, JSONObject().put("resources", org.json.JSONArray()))
                    "prompts/list" -> ok(id, JSONObject().put("prompts", org.json.JSONArray()))
                    else -> err(id, -32601, "method not found: " + method)
                }
            }
        }
    }

    private fun ok(id: Any?, result: JSONObject): String =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", result).toString()

    private fun err(id: Any?, code: Int, message: String): String =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message)).toString()

    // ── HTTP 细节 ────────────────────────────────────────────

    private fun respond(out: OutputStream, code: Int, body: String?, reqHeaders: Map<String, String>) {
        val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
        if (body != null) sb.append("Content-Type: application/json; charset=utf-8\r\n")
        sb.append("Content-Length: ").append(bytes.size).append("\r\n")
        sb.append("MCP-Protocol-Version: ").append(LATEST).append("\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Headers: Content-Type, Authorization, Mcp-Session-Id, MCP-Protocol-Version\r\n")
        sb.append("Access-Control-Allow-Methods: POST, GET, DELETE, OPTIONS\r\n")
        val sid = reqHeaders["mcp-session-id"]
        if (sid.isNullOrBlank()) sb.append("Mcp-Session-Id: paper-search-mcp\r\n")
        sb.append("Connection: keep-alive\r\n\r\n")
        out.write(sb.toString().toByteArray())
        if (bytes.isNotEmpty()) out.write(bytes)
        out.flush()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        202 -> "Accepted"
        204 -> "No Content"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        else -> "OK"
    }

    /** 只允许回环来源，防浏览器 CSRF。 */
    private fun originOk(headers: Map<String, String>): Boolean {
        val o = headers["origin"] ?: return true
        return o.contains("127.0.0.1") || o.contains("localhost") || o.contains("[::1]")
    }

    private fun authOk(headers: Map<String, String>, path: String): Boolean {
        if (token.isEmpty()) return true
        val h = headers["authorization"].orEmpty()
        if (h == "Bearer $token") return true
        return path.contains("token=" + token)
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 16384) break
        }
        return buf.toString("UTF-8")
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) buf else buf.copyOf(off)
    }

    companion object {
        const val LATEST = "2025-06-18"
        const val APP_VERSION = "1.4.0"
    }
}
