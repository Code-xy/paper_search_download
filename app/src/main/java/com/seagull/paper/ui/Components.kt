package com.seagull.paper.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seagull.paper.data.PaperItem
import com.seagull.paper.data.SourceState
import com.seagull.paper.data.SourceStatus
import com.seagull.paper.data.TaskState
import com.seagull.paper.data.Util

private val OK_GREEN = Color(0xFF0F9D58)
private val WARN_ORANGE = Color(0xFFE8710A)

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("标题 / 关键词 / DOI / arXiv ID", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.5.sp) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = onClear) { Icon(Icons.Filled.Clear, contentDescription = "清空") }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
        )
    )
}

@Composable
fun Pill(text: String, fg: Color, bg: Color) {
    Surface(color = bg, shape = RoundedCornerShape(7.dp)) {
        Text(
            text = text,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun SourceFilterRow(
    list: List<SourceStatus>,
    counts: Map<String, Int>,
    total: Int,
    filter: String,
    onPick: (String) -> Unit,
    onError: (SourceStatus) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            label = "全部 " + total,
            bg = if (filter.isEmpty()) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            fg = if (filter.isEmpty()) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            selected = filter.isEmpty(),
            spinner = false,
            onClick = { onPick("") }
        )
        for (s in list) {
            val cs = MaterialTheme.colorScheme
            val n = counts[s.id] ?: 0
            val bg: Color = when (s.state) {
                SourceState.RUNNING -> cs.primaryContainer
                SourceState.DONE -> cs.secondaryContainer
                SourceState.EMPTY -> cs.surfaceVariant
                SourceState.FAILED -> cs.errorContainer
                SourceState.IDLE -> cs.surfaceVariant
            }
            val fg: Color = when (s.state) {
                SourceState.RUNNING -> cs.onPrimaryContainer
                SourceState.DONE -> cs.onSecondaryContainer
                SourceState.EMPTY -> cs.onSurfaceVariant
                SourceState.FAILED -> cs.onErrorContainer
                SourceState.IDLE -> cs.onSurfaceVariant
            }
            val label = when (s.state) {
                SourceState.RUNNING -> s.name + " …"
                SourceState.DONE -> s.name + " " + n
                SourceState.EMPTY -> s.name + " 0"
                SourceState.FAILED -> s.name + " 失败"
                SourceState.IDLE -> s.name
            }
            FilterChip(
                label = label,
                bg = bg,
                fg = fg,
                selected = filter == s.id,
                spinner = s.state == SourceState.RUNNING,
                onClick = {
                    if (s.state == SourceState.FAILED) onError(s) else onPick(s.id)
                },
                onLongClick = { onError(s) }
            )
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    bg: Color,
    fg: Color,
    selected: Boolean,
    spinner: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = bg,
        shape = RoundedCornerShape(9.dp),
        border = if (selected) BorderStroke(1.5.dp, cs.primary) else null,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (spinner) {
                CircularProgressIndicator(modifier = Modifier.size(9.dp), strokeWidth = 1.4.dp, color = fg)
                Spacer(Modifier.width(5.dp))
            }
            Text(label, color = fg, fontSize = 11.5.sp, maxLines = 1)
        }
    }
}

@Composable
fun PaperCard(
    item: PaperItem,
    task: TaskState?,
    fav: Boolean,
    onFav: () -> Unit,
    onDownload: () -> Unit,
    onOpen: () -> Unit,
    onCopyDoi: () -> Unit,
    onDetail: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = cs.surface,
        border = BorderStroke(1.dp, cs.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = item.title,
                    modifier = Modifier.weight(1f),
                    fontSize = 14.5.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onFav, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "收藏",
                        tint = if (fav) cs.tertiary else cs.outline
                    )
                }
            }

            if (item.byline.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    item.byline,
                    fontSize = 11.5.sp,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Pill(item.sourceName, cs.onPrimaryContainer, cs.primaryContainer)
                if (item.hasPdf) Pill("PDF 直链", OK_GREEN, OK_GREEN.copy(alpha = 0.12f))
                else if (item.oa) Pill("开放获取", OK_GREEN, OK_GREEN.copy(alpha = 0.12f))
                else Pill("需解析", WARN_ORANGE, WARN_ORANGE.copy(alpha = 0.12f))
                if (item.doi.isNotEmpty()) Pill("DOI", cs.onSurfaceVariant, cs.surfaceVariant)
                if (item.citeText.isNotEmpty()) Pill("被引 " + item.citeText, cs.onSurfaceVariant, cs.surfaceVariant)
                if (!task?.provider.isNullOrEmpty()) {
                    Pill("命中 " + task.provider, cs.onSecondaryContainer, cs.secondaryContainer)
                }
            }

            if (item.abstractText.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    item.abstractText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (task != null && (task.running || task.error.isNotEmpty() || task.stage.isNotEmpty())) {
                Spacer(Modifier.height(10.dp))
                if (task.running) {
                    LinearProgressIndicator(
                        progress = { task.pct },
                        modifier = Modifier.fillMaxWidth().height(4.dp)
                    )
                    Spacer(Modifier.height(5.dp))
                }
                Text(
                    text = when {
                        task.error.isNotEmpty() -> "失败：" + task.error
                        task.sizeText.isNotEmpty() -> task.stage + "  ·  " + task.sizeText
                        else -> task.stage
                    },
                    fontSize = 11.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (task.error.isNotEmpty()) cs.error else cs.primary
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilledTonalButton(
                    onClick = onDownload,
                    shape = RoundedCornerShape(12.dp),
                    enabled = task?.running != true
                ) {
                    Text(if (item.hasPdf) "下载 PDF" else "解析并下载", fontSize = 12.5.sp)
                }
                if (item.landingUrl != null) {
                    OutlinedButton(onClick = onOpen, shape = RoundedCornerShape(12.dp)) {
                        Text("网页", fontSize = 12.5.sp)
                    }
                }
                Column(Modifier.weight(1f)) {}
                TextButton(onClick = onCopyDoi) {
                    Text(if (item.doi.isNotEmpty()) "复制 DOI" else "复制标题", fontSize = 12.sp)
                }
                TextButton(onClick = onDetail) { Text("详情", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = cs.surface,
        border = BorderStroke(1.dp, cs.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, fontSize = 11.5.sp, color = cs.onSurfaceVariant)
                }
            }
            androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
fun ClickRow(title: String, subtitle: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = cs.surface,
        border = BorderStroke(1.dp, cs.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, fontSize = 11.5.sp, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun FileRow(
    name: String,
    meta: String,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onOpen() },
        shape = RoundedCornerShape(16.dp),
        color = cs.surface,
        border = BorderStroke(1.dp, cs.outlineVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(name, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(meta, fontSize = 11.5.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpen) { Text("打开", fontSize = 12.sp) }
                TextButton(onClick = onExport) { Text("存到下载目录", fontSize = 12.sp) }
                TextButton(onClick = onShare) { Text("分享", fontSize = 12.sp) }
                Box(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text("删除", fontSize = 12.sp, color = cs.error) }
            }
        }
    }
}

@Composable
fun KeyValue(label: String, value: String) {
    if (value.isBlank()) return
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 12.5.sp)
    }
}
