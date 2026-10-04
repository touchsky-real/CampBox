# v3.1.0

## 更新内容

### ⚡ 优化

- 代码整理：移除未使用的依赖、无引用的资源文件与残留代码，安装包更小
- 偏好存储类更名（PointsTaskStateStore → UserPrefsStore），用户设置数据不受影响
- 修复蓝牙扫描与定位权限的 Lint 错误

### 🔧 其他

- README 重写：注明本 App 适用于太原理工大学（对接胖乖生活饮水机与趣智校园热水系统）
- 全新自动发布工作流：推送 Git Tag 即可在云端完成编译、签名并挂载 APK 至 Release
- Release 签名支持 GitHub Secrets 注入正式密钥；未配置时自动回退内置签名，发布不中断
- 版本号现由 Git Tag 驱动（versionName/versionCode 自动计算），修复此前 Release 版本号不随 Tag 变化的问题

> 首次 Tag 发布。升级安装可直接覆盖，签名与历史版本一致。
