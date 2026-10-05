package com.inonvation.campbox.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WaterDeviceCodeTest {
    @Test fun `识别官方三种设备码及 URL 编码`() {
        assertEquals(WaterDeviceCode("IMEI", "123456789012345"),
            parseWaterDeviceCode("https://h5.qiekj.com/?IMEI=123456789012345"))
        assertEquals(WaterDeviceCode("NQT", "ABC-123"),
            parseWaterDeviceCode("https://h5.qiekj.com/#/scan?NQT=ABC%2D123"))
        assertEquals(WaterDeviceCode("SN", "SN_001"), parseWaterDeviceCode("sn=SN_001"))
    }

    @Test fun `多个参数按扫码入口优先级识别 不使用二维码里的 Token`() {
        assertEquals(WaterDeviceCode("NQT", "new-code"),
            parseWaterDeviceCode("https://example.com/?IMEI=123&NQT=new-code&token=untrusted"))
    }

    @Test fun `拒绝付款码 水卡码 空值和冲突设备编号`() {
        listOf("", "https://qr.alipay.com/payment", "https://h5.qiekj.com/?qeykid=11223344",
            "12345678", "IMEI=", "SN=a&SN=b", "NQT=%00", "SN=%ZZ", "SN=" + "a".repeat(129))
            .forEach { assertNull(parseWaterDeviceCode(it), it) }
    }

    @Test fun `普通饮水机转换为可选设备`() {
        val device = scannedWaterDevice(WaterScanData("G1", "04"),
            WaterDeviceDetails("G1", "图书馆饮水机", "04", 0))
        assertEquals("G1", device.goodsId)
        assertEquals("G1", device.id)
        assertEquals("图书馆饮水机", device.goodsName)
    }

    @Test fun `缺少类别或扫描和详情类别冲突时不允许开水`() {
        assertFailsWith<IllegalArgumentException> {
            scannedWaterDevice(WaterScanData("G1"), WaterDeviceDetails("G1"))
        }
        assertFailsWith<IllegalArgumentException> {
            scannedWaterDevice(WaterScanData("G1", "08"), WaterDeviceDetails("G1", categoryCode = "04"))
        }
        assertFailsWith<IllegalArgumentException> {
            scannedWaterDevice(WaterScanData("G1", "04"), WaterDeviceDetails("G1", categoryCode = "08"))
        }
    }

    @Test fun `蓝牙饮水机不能误用云端开水接口`() {
        assertFailsWith<IllegalArgumentException> {
            scannedWaterDevice(WaterScanData("G1", "04"), WaterDeviceDetails("G1", categoryCode = "04", isBluetooth = 1))
        }
    }

    @Test fun `详情缺少类别时使用扫码接口明确的饮水机类别`() {
        assertEquals("G1", scannedWaterDevice(WaterScanData("G1", "04"), WaterDeviceDetails(name = "饮水机")).goodsId)
    }
}
