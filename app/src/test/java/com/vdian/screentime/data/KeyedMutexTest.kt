package com.vdian.screentime.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [KeyedMutex] 是修复"同帧连点被吞"的关键：三条 toggle 路径的读-改-写
 * 都靠它串行化同一格子上的操作。这里直接验证串行语义。
 */
class KeyedMutexTest {

    @Test
    fun same_key_blocks_concurrently() = runBlocking {
        val mutex = KeyedMutex()
        val inside = AtomicInteger(0)
        var maxConcurrent = 0

        val jobs = (1..20).map {
            async {
                mutex.withLock("same") {
                    val now = inside.incrementAndGet()
                    if (now > maxConcurrent) maxConcurrent = now
                    delay(1)
                    inside.decrementAndGet()
                }
            }
        }
        jobs.awaitAll()

        assertEquals("同一 key 上的临界区不能重叠", 1, maxConcurrent)
    }

    @Test
    fun different_keys_do_not_block_each_other() = runBlocking {
        val mutex = KeyedMutex()
        val inside = AtomicInteger(0)
        var maxConcurrent = 0

        // 两个不同 key 必须能同时在临界区内，否则退化成全局锁
        coroutineScope {
            listOf("a", "b").map { key ->
                launch {
                    mutex.withLock(key) {
                        val now = inside.incrementAndGet()
                        if (now > maxConcurrent) maxConcurrent = now
                        delay(50)
                        inside.decrementAndGet()
                    }
                }
            }
        }

        assertTrue("不同 key 不应互相阻塞,实测并发数=$maxConcurrent", maxConcurrent >= 2)
    }

    /**
     * 复现连点竞态：无锁时两次点击都读到同一份旧值、都写回同一结果。
     * 有锁后必须严格交替。
     */
    @Test
    fun alternating_taps_are_serialized_like_the_real_toggle() = runBlocking {
        val mutex = KeyedMutex()
        var state = false // 对应"该格是否已确认"
        var reads = 0

        suspend fun tap() = mutex.withLock("cell") {
            val seen = state          // 读
            reads++
            delay(1)                  // 给竞态留出窗口
            state = !seen             // 改 + 写
        }

        (1..2).map { async { tap() } }.awaitAll()

        // 两次交替点击后应回到初始态；若被吞掉一次则会停在 true
        assertEquals("两次点击必须交替,不能第二次空转", false, state)
        assertEquals(2, reads)
    }
}
