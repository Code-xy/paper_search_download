# 学术搜（PaperSearch）

聚合搜论文 + 能下的直接下、不能下的自动解析全文再下。UI 跟磁力搜同一套（Kotlin + Compose + Material3，薄荷青 `#0E9F94`）。

## 搜索源（并发，单源失败不影响其它）

| 源 | 类型 | PDF |
|---|---|---|
| arXiv | Atom API | 直链 |
| Google 学术 | 网页抓取（多镜像轮询，验证码自动换） | 结果里的 [PDF] |
| Semantic Scholar | Graph API | openAccessPdf |
| OpenAlex | API | best_oa_location.pdf_url |
| Europe PMC | REST | 免费全文 PDF |
| Crossref | API | 落地页 |
| DOAJ | API | 全 OA |
| 自定义 | sources.json 里的 CSS 选择器 | 可选 |

## 全文解析链（没有 PDF 直链时）

`Unpaywall → OpenAlex → Europe PMC → 预印本改写 → Sci-Hub 镜像 → 落地页解析`

- 预印本改写：`10.1101/*` → bioRxiv/medRxiv PDF；`10.48550/arXiv.*` → arXiv PDF
- Sci-Hub：镜像列表轮询，识别「人机验证 / 库里没有 / 解析不到 PDF」并继续下一个
- 落地页解析：`citation_pdf_url`、`eprints.document_url`、iframe/embed、正文 PDF 链接、JS 跳转
- 每一步的结果都记在「详情 → 解析过程」里，能看出到底卡在哪一环
- 下载器按候选顺序试，**返回的不是 PDF（登录页/HTML）自动换下一个**

## 内置阅读器

抓取失败或出版社落地页，用 WebView 打开；页面上任意位置点「解析本页 PDF」，现场扒 PDF 并下载。WebView 里点到 .pdf 链接也会被拦截成下载。

## 下载

- 存到 App 私有目录（免权限），「下载」页可打开/分享/删除，一键导出到系统 `下载/学术搜`
- 文件名 = 第一作者 + 年份 + 标题
- 可选「仅 Wi-Fi 下载」

## 远程热更新

仓库根 `sources.json` 控制：Sci-Hub 镜像列表、学术镜像列表、解析顺序、Unpaywall 邮箱、网页预览站、自定义选择器源。改完在设置里点「更新配置」，不用重装。

## 构建

推 main 触发 GitHub Actions；签名沿用仓库内置 `signing/dev-release.jks`（alias `paper`），保证覆盖升级。

## 内嵌 MCP 服务（v1.2.0）

App 内置一个 MCP 服务器（Kotlin 原生，不依赖 Python），只绑 `127.0.0.1`，给 Claude / Cursor / 任意 Agent 直接调。

- 传输：JSON-RPC 2.0 over **Streamable HTTP**（`POST /mcp`，GET 提供 SSE 保活通道）
- 地址：`http://127.0.0.1:8765/mcp`（端口可改），可选 Bearer Token
- 保活：前台服务 + 常驻通知（Android 会杀后台 socket）

### 工具（10 个）

| 工具 | 作用 |
|---|---|
| `search_papers` | 并发多源搜索，返回标题/作者/年份/DOI/PDF 直链/摘要 |
| `list_sources` | 列出可用源与启用状态 |
| `search_by_doi` | Crossref + OpenAlex 取元数据 |
| `resolve_pdf` | 跑全文解析链，回候选链接 + 每步结果 |
| `download_pdf` | 解析并下载到本机，回路径/大小/命中来源 |
| `list_downloads` | 已下载 PDF 清单 |
| `read_paper` | PDF 取文本；`mode=sections` 按章节切，支持 offset/max_chars 分段 |
| `get_citations` | OpenAlex 引文关系（被引 / 参考文献） |
| `save_to_downloads` | 导出到系统 `Download/学术搜/` |
| `diagnose` | 网络自检：区分没配代理 / 节点挂了 / 被墙 / 人机验证 / 限流 / 被劫持 |

### 连接方式

```bash
# 同机（手机上的 Agent）
http://127.0.0.1:8765/mcp

# PC 用 USB 转发
adb forward tcp:8765 tcp:8765
# 然后客户端连 http://127.0.0.1:8765/mcp

# 只支持 stdio 的客户端（Claude Desktop 等）用 supergateway 桥一下
npx -y supergateway --streamableHttp http://127.0.0.1:8765/mcp --header "Authorization: Bearer <token>"
```

友好度对齐 `openags/paper-search-mcp` 的工具语义（search_/resolve_/download_/read_），但跑在手机上、复用本 App 的源与解析链，零 Python 依赖。

