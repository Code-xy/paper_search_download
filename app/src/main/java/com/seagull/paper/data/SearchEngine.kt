package com.seagull.paper.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** 多源并发搜索：谁先回来谁先上屏，单源翻车不影响其它源。 */
object SearchEngine {

    fun stream(sources: List<PaperSource>, query: String, page: Int): Flow<SourceEvent> = channelFlow {
        val jobs = sources.map { src ->
            launch {
                send(SourceEvent.Started(src.id, src.name))
                val t0 = System.currentTimeMillis()
                // 国内网络抖动很常见，网络类失败给一次机会，别直接判死
                var attempt = 0
                while (true) {
                    try {
                        val items = src.search(query, page)
                        send(SourceEvent.Done(src.id, src.name, items, System.currentTimeMillis() - t0))
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        attempt++
                        val netIssue = e is java.io.IOException
                        if (attempt < 2 && netIssue) {
                            delay(900)
                            continue
                        }
                        send(
                            SourceEvent.Failed(
                                src.id, src.name,
                                e.message ?: e.javaClass.simpleName,
                                System.currentTimeMillis() - t0
                            )
                        )
                        break
                    }
                }
            }
        }
        jobs.joinAll()
    }
}
