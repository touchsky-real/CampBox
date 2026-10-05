package com.inonvation.campbox.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** TLS close_notify 也可能写 socket，必须在 IO 线程关闭；清理失败不能覆盖认证结果。 */
internal suspend fun cleanupCampusResources(vararg actions: () -> Unit) {
    withContext(NonCancellable + Dispatchers.IO) {
        actions.forEach { action ->
            try { action() } catch (_: Exception) { /* 尽力释放其余资源，保留原认证结果。 */ }
        }
    }
}
