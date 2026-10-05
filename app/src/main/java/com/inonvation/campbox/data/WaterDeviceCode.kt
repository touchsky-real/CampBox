package com.inonvation.campbox.data

import java.net.URLDecoder

data class WaterDeviceCode(val type: String, val value: String)

class WaterScanException(val step: String, val serverCode: Int?, message: String) : Exception(message)

/** 对齐胖乖 HomeScanCodeAct 的 NQT / IMEI / SN 入口；只提取设备码，不打开二维码里的网址。 */
fun parseWaterDeviceCode(raw: String): WaterDeviceCode? {
    val text = raw.trim()
    if (text.isEmpty() || text.length > 4096) return null
    val query = text.substringAfter('?', text).substringBefore('#').replace("&amp;", "&")
    val pairs = query.split('&').mapNotNull { part ->
        if ('=' !in part) return@mapNotNull null
        runCatching {
            URLDecoder.decode(part.substringBefore('='), "UTF-8").uppercase() to
                URLDecoder.decode(part.substringAfter('='), "UTF-8").trim()
        }.getOrNull()
    }
    for (type in listOf("NQT", "IMEI", "SN")) {
        val values = pairs.filter { it.first == type }.map { it.second }.filter { it.isNotBlank() }.distinct()
        if (values.size > 1) return null
        val value = values.singleOrNull() ?: continue
        if (!value.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) return null
        return WaterDeviceCode(type, value)
    }
    return null
}

data class WaterScanData(val id: String? = null, val categoryCode: String? = null)
data class WaterDeviceDetails(
    val goodsId: String? = null,
    val name: String = "",
    val categoryCode: String? = null,
    val isBluetooth: Int? = null,
)

/** 识别和详情均来自官方接口；淋浴、洗衣机及蓝牙饮水机不能误用云端开水接口。 */
internal fun scannedWaterDevice(scan: WaterScanData, details: WaterDeviceDetails): DeviceItem {
    val category = details.categoryCode?.takeIf { it.isNotBlank() } ?: scan.categoryCode
    require(category == "04" && (scan.categoryCode.isNullOrBlank() || scan.categoryCode == "04")) {
        "这不是支持的胖乖饮水机二维码，请扫描饮水机机身上的设备码"
    }
    require(details.isBluetooth != 1) { "这台饮水机需要蓝牙开水，请使用胖乖生活 App" }
    val id = details.goodsId?.takeIf { it.isNotBlank() } ?: scan.id?.takeIf { it.isNotBlank() }
        ?: error("平台未返回设备编号，请重新扫描")
    return DeviceItem(goodsId = id, id = id, goodsName = details.name.ifBlank { "饮水机 $id" })
}
