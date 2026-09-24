package com.vdian.screentime.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 按 key 串行化的锁池。用于把"读-改-写"包成一个原子段。
 *
 * 为什么需要：`toggleParentConfirm` 是 `getByDay` → 改 → `upsert` 三步，
 * 两次连点落在同一帧时都读到旧状态、都写回同一个结果，第二次点击被静默吞掉
 * （正是简报清单第 5 条"连点同一格两次"要防的）。按 (epochDay, item) 加锁后，
 * 后一次点击会等前一次写完再读，于是正确交替。
 *
 * 不同格子互不阻塞——锁按 key 分桶，而不是全局一把。
 */
class KeyedMutex {

    private val locks = mutableMapOf<Any, Mutex>()

    /** 同一 key 上的 [block] 串行执行；不同 key 并行。 */
    suspend fun <T> withLock(key: Any, block: suspend () -> T): T {
        val mutex = synchronized(locks) { locks.getOrPut(key) { Mutex() } }
        return mutex.withLock { block() }
    }
}
