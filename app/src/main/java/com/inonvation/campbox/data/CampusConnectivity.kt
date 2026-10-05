package com.inonvation.campbox.data

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 首个成功结果即可确认联网；调用方负责单次探测的超时及网络错误处理。 */
internal suspend fun anyCampusProbeSucceeds(
    probes: List<String>,
    probe: suspend (String) -> Boolean,
): Boolean = coroutineScope {
    val results = Channel<Boolean>(probes.size)
    val jobs = probes.map { address ->
        launch { results.send(probe(address)) }
    }
    try {
        repeat(probes.size) {
            if (results.receive()) return@coroutineScope true
        }
        false
    } finally {
        jobs.forEach { it.cancel() }
        results.cancel()
    }
}
