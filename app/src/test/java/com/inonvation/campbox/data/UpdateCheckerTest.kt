package com.inonvation.campbox.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateCheckerTest {
    @Test
    fun `补丁版本更新则判定为新版本`() {
        assertTrue(isNewerVersion("3.1.2", "3.1.1"))
    }

    @Test
    fun `相同版本不算新版本`() {
        assertFalse(isNewerVersion("3.1.2", "3.1.2"))
    }

    @Test
    fun `旧版本不算新版本`() {
        assertFalse(isNewerVersion("3.0.9", "3.1.0"))
    }

    @Test
    fun `主版本号升级判定为新版本`() {
        assertTrue(isNewerVersion("4.0.0", "3.9.9"))
    }

    @Test
    fun `段数不同时按数值比较`() {
        assertTrue(isNewerVersion("3.2", "3.1.5"))
        assertFalse(isNewerVersion("3.1", "3.1.0"))
    }

    @Test
    fun `当前版本为空时任何版本均为新版本`() {
        assertTrue(isNewerVersion("0.0.1", ""))
    }

    @Test
    fun `带v前缀的标签同样可以比较`() {
        assertTrue(isNewerVersion("v3.1.2", "3.1.1"))
    }
}
