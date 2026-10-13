package com.seagull.paper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.seagull.paper.data.Store
import com.seagull.paper.mcp.McpService
import com.seagull.paper.ui.PaperTheme
import com.seagull.paper.ui.RootScaffold

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppRef.set(application)
        if (Store(this).mcpEnabled) {
            try {
                McpService.start(this)
            } catch (e: Exception) {
                // 前台服务权限没给就算了
            }
        }
        setContent {
            PaperTheme {
                RootScaffold()
            }
        }
    }
}
