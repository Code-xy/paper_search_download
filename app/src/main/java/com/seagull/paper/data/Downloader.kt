package com.seagull.paper.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.seagull.paper.Net
import okhttp3.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 下载器：候选链接按顺序试，不是 PDF 就换下一个（很多站会给登录页/HTML 假 404）。
 * 文件落在 App 私有外置目录，再支持导出到系统「下载/学术搜」。
 */
object Downloader {

    data class Result(val file: File, val bytes: Long, val provider: String, val url: String)

    fun dir(ctx: Context): File {
        val d = ctx.getExternalFilesDir("pdf") ?: File(ctx.filesDir, "pdf")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.lowercase().endsWith(".pdf") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun totalSize(ctx: Context): Long = list(ctx).sumOf { it.length() }

    suspend fun download(
        ctx: Context,
        candidates: List<Candidate>,
        fileName: String,
        referer: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Result = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) throw IOException("没有可下载的链接")
        val fails = ArrayList<String>()
        for ((i, c) in candidates.withIndex()) {
            try {
                return@withContext fetch(ctx, c, fileName, referer, onProgress)
            } catch (e: Exception) {
                fails.add((i + 1).toString() + ") " + describe(c, e))
            }
        }
        // 把每个候选挂在哪一步都说清楚，用户才知道是「要订阅」「要梯子」还是「站被掐」
        throw IOException("候选链接全部失败：\n" + fails.joinToString("\n"))
    }

    /** 一句话说清这个候选为什么没下成。 */
    private fun describe(c: Candidate, e: Exception): String {
        val host = Util.host(c.url).ifEmpty { c.url.take(40) }
        val why = when {
            e is PdfResolver.NotPdfException -> e.message.orEmpty()
            e.message?.contains("follow-up", true) == true -> "重定向循环（服务器要 Cookie 或反爬）"
            e.message?.contains("403") == true -> "403 被拒（要订阅 / 要梯子 / 防盗链）"
            e.message?.contains("404", false) == true -> "404 链接已失效"
            e.message?.contains("too small", true) == true -> "下到的不是 PDF（多半是登录页/门户页）"
            else -> Net.friendly(e.message ?: e.javaClass.simpleName)
        }
        return "[" + c.provider + "] " + host + " — " + why
    }

    /** 出版社 PDF 常要带着站点 Cookie 才给，第一次被拒就先去访问一次首页再重试。 */
    private fun openPdf(cand: Candidate, referer: String?): Response {
        val ref = cand.referer ?: referer
        return try {
            Net.openLong(cand.url, ref)
        } catch (e: IOException) {
            val u = try {
                java.net.URI(cand.url)
            } catch (ex: Exception) {
                null
            }
            val base = if (u?.scheme != null && u.host != null) u.scheme + "://" + u.host + "/" else ""
            if (base.isNotEmpty() && base != cand.url) {
                try {
                    Net.get(base, ref)
                } catch (ignored: Exception) {
                    // 预热失败也照样重试一次
                }
            }
            Net.openLong(cand.url, ref)
        }
    }

    private fun fetch(
        ctx: Context,
        cand: Candidate,
        fileName: String,
        referer: String?,
        onProgress: (Long, Long) -> Unit
    ): Result {
        val resp = openPdf(cand, referer)
        resp.use { r ->
            val body = r.body ?: throw IOException("空响应")
            val ctype = r.header("Content-Type").orEmpty().lowercase()
            val head = ByteArray(5)
            val peeked = body.source().peek().read(head)
            val magic = if (peeked >= 4) String(head, 0, 4, Charsets.ISO_8859_1) else ""
            val looksPdf = magic == "%PDF" || ctype.contains("pdf")
            if (!looksPdf) throw PdfResolver.NotPdfException(
                cand.provider + " 返回的不是 PDF（" + (if (ctype.isBlank()) "未知类型" else ctype) + "）"
            )

            val total = body.contentLength()
            val tmp = File(dir(ctx), ".part")
            if (tmp.exists()) tmp.delete()
            var done = 0L
            var lastTick = 0L
            FileOutputStream(tmp).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastTick > 250L) {
                            lastTick = now
                            onProgress(done, total)
                        }
                    }
                }
            }
            onProgress(done, total)
            if (done < 1024) {
                tmp.delete()
                throw IOException("下到的文件太小（" + done + " B），八成不是真 PDF")
            }
            val target = unique(File(dir(ctx), fileName))
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            return Result(target, done, cand.provider, cand.url)
        }
    }

    private fun unique(f: File): File {
        if (!f.exists()) return f
        val name = f.name.removeSuffix(".pdf")
        for (i in 1..50) {
            val c = File(f.parentFile, name + " (" + i + ").pdf")
            if (!c.exists()) return c
        }
        return f
    }

    /** 导出到系统下载目录「Download/学术搜」，返回展示路径；不支持时返回 null。 */
    suspend fun export(ctx: Context, file: File): String? = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/学术搜")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext null
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "下载/学术搜/" + file.name
            } else {
                @Suppress("DEPRECATION")
                val d = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "学术搜"
                )
                if (!d.exists() && !d.mkdirs()) return@withContext null
                val t = File(d, file.name)
                file.inputStream().use { i -> FileOutputStream(t).use { o -> i.copyTo(o) } }
                "下载/学术搜/" + t.name
            }
        } catch (e: Exception) {
            null
        }
    }

    fun uri(ctx: Context, file: File): Uri =
        FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)

    fun openIntent(ctx: Context, file: File): Intent {
        val uri = uri(ctx, file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun shareIntent(ctx: Context, file: File): Intent {
        val uri = uri(ctx, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
