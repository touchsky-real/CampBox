package com.inonvation.campbox.data

import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CampusNetCleanupTest {
    @Test fun `在后台清理且某一步失败不覆盖认证结果或跳过剩余释放`() = runTest {
        val caller = Thread.currentThread()
        var released = false
        val result = try { "认证成功" } finally {
            cleanupCampusResources(
                { assertNotEquals(caller, Thread.currentThread()); throw IllegalStateException("TLS close failed") },
                { released = true },
            )
        }
        assertEquals("认证成功", result)
        assertEquals(true, released)
    }

    @Test fun `认证被取消时仍释放连接`() = runTest {
        var released = false
        val job = launch {
            try { cancel() } finally { cleanupCampusResources({ released = true }) }
        }
        job.join()
        assertEquals(true, released)
    }
}
