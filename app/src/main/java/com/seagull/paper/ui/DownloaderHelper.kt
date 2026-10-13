package com.seagull.paper.ui

import android.content.Context
import android.content.Intent
import com.seagull.paper.data.Downloader
import java.io.File

/** 薄封装：让 UI 层不直接依赖 data 层的 FileProvider 细节。 */
object DownloaderHelper {
    fun open(ctx: Context, file: File): Intent = Downloader.openIntent(ctx, file)
    fun share(ctx: Context, file: File): Intent = Downloader.shareIntent(ctx, file)
}
