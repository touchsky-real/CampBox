package com.inonvation.campbox.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CampusConnectivityTest {
    @Test fun `一路成功立即返回并取消仍在等待的探针`() = runTest {
        var slowCancelled = false
        val online = anyCampusProbeSucceeds(listOf("fast", "slow")) { address ->
            if (address == "fast") {
                delay(100)
                true
            } else {
                try { awaitCancellation() } finally { slowCancelled = true }
            }
        }
        assertTrue(online)
        assertTrue(slowCancelled)
        assertEquals(100L, testScheduler.currentTime)
    }

    @Test fun `先完成的失败探针不能遮住稍后的成功结果`() = runTest {
        val online = anyCampusProbeSucceeds(listOf("failed", "working")) { address ->
            delay(if (address == "failed") 100L else 200L)
            address == "working"
        }
        assertTrue(online)
        assertEquals(200L, testScheduler.currentTime)
    }

    @Test fun `所有探针都失败后才判定不通`() = runTest {
        val completed = mutableSetOf<String>()
        val online = anyCampusProbeSucceeds(listOf("first", "second")) { address ->
            delay(if (address == "first") 100L else 200L)
            completed += address
            false
        }
        assertFalse(online)
        assertEquals(setOf("first", "second"), completed)
        assertEquals(200L, testScheduler.currentTime)
    }

    @Test fun `取消联网检查会停止所有探针且不返回联网结果`() = runTest {
        var cancelled = 0
        var returned = false
        val job = launch {
            anyCampusProbeSucceeds(listOf("first", "second")) {
                try { awaitCancellation() } finally { cancelled++ }
            }
            returned = true
        }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(2, cancelled)
        assertFalse(returned)
    }

    @Test fun `意外错误继续向调用方传递并释放其余探针`() = runTest {
        var cancelled = false
        assertFailsWith<IllegalStateException> {
            anyCampusProbeSucceeds(listOf("broken", "waiting")) { address ->
                if (address == "broken") {
                    delay(100)
                    error("unexpected failure")
                } else {
                    try { awaitCancellation() } finally { cancelled = true }
                }
            }
        }
        assertTrue(cancelled)
    }

    @Test fun `没有探针时不会声称已联网`() = runTest {
        assertFalse(anyCampusProbeSucceeds(emptyList()) { error("不应发起请求") })
    }
}
