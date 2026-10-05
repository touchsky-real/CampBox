# 扫码喝水

参考 APK 的包名为 `com.qiekj.user`，`versionName=1.142.0`、`versionCode=279`（已用 aapt 检查 APK 内的清单）。所有胖乖 API 使用 `QEUser/1.142.0` User-Agent，包含实际版本码 279；请求签名遵循官方 `...&version=1.142.0&/接口路径` 格式。不能只改 Version 请求头或把版本码猜成 1142。

`goods/scan/v2` 缺少 `token` 参数时会误报“版本过低”，此报错与版本号无关。2026-10-06 使用用户提供的饮水机照片实测：固定版本、签名算法和非空测试 token，所有请求均带 `Authorization`，结果如下。

| token 请求头 | token 表单字段 | scan/v2 结果 |
| --- | --- | --- |
| 无 | 无 | code=-1，版本过低 |
| 有 | 无 | code=0，成功 |
| 无 | 有 | code=0，成功 |
| 有 | 有 | code=0，成功 |

因此，单独的 `Authorization` 不能满足扫码接口要求，但 `token` 请求头或表单字段均可。拦截器保留 `token` 和 `Authorization` 两个请求头；两个头、表单和签名使用同一份 token，取值顺序为请求中显式指定的 `token` 头、表单 token、本地保存的 token。

测试 token 并非登录凭证：它可以完成该设备的扫码和详情查询，但 `goods/normal/skus` 返回 code=2、未登录。扫码与详情成功不能证明已登录或能够实际出水，后续接水流程仍需有效账号和真机验证。

参考用户提供的 `C:/Users/touchsky/Desktop/pgdecode/apk-jadx/sources/com/qiekj/user`：

- `ui/activity/scan/HomeScanCodeAct.java`：扫码入口依次检查 `NQT`、`IMEI`、`SN`。
- `viewmodel/ScanCodeVm.java` 和 `http/ApiService.java`：`POST goods/scan/v2`，表单键是对应设备码类型，返回设备 `id` 和 `categoryCode`。
- `entity/scan/GoodsDetail.java`：`goods/normal/details` 返回 `name`、`goodsId`、`categoryCode`、`isBluetooth` 等。
- `p000enum/GoodsTypeEnum.java`：饮水机类别为 `04`，淋浴为 `08`。

实现仅提取二维码设备参数并请求固定的胖乖 API，不打开二维码网址或读取其中的登录凭证。设备识别不触发开水；选择设备后由原有开水按钮调用位置、积分、后付费和订单确认流程。蓝牙设备和其他类别明确提示不支持。

相机使用 ZXing Android Embedded 4.3.0，在独立 Activity 中运行，不需要 Google Play 服务。只有点击扫码时请求相机权限，取消扫码不会改变设备或发起开水。

新增二维码解析和设备类型校验单元测试。真实摄像头识别及校园饮水机接口仍需真机验证。
