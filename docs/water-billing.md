# 开水账单与积分核对

参考 `C:/Users/touchsky/Desktop/pgdecode/apk-jadx/sources/com/qiekj/user/` 的官方 1.142.0 客户端：

- `ui/activity/scan/StartStatusAct.java`：收到启动指令回执后轮询 `goods/water/sync`；`status=3/5` 表示结束，其他使用状态继续等待，间隔 1500ms。不能把 `workStatus` 暂时空闲当成订单结束。
- `viewmodel/my/OrderDetailsViewModel.java#getOrderDetailsByOrderNo`：`POST order/detail` 可以只传 `orderNo`（及登录 token），不必先获取 `orderId`。
- `viewmodel/DeviceStartupVm.java#afterPay`：建单仍传当前订单号。补查始终匹配自己的订单，不能拿设备最后一笔其他人的订单代替。
- `ui/activity/scan/AfterPayUseAct.java#unLock`：积分选择传 `promotionType=8`，关闭传 `-8`；其余默认项为 `-6/-7`。
- 同文件积分开关监听：`checkUserIsRisk` 返回 `true` 时取消积分选择并显示实名认证提示。
- `viewmodel/AfterPayVm.java#userIntegral`：使用条件来自 `userIntegral/limitRule`。反编译模型属性为 `isUserIntegral`，实际接口还使用 `userIntegral`，客户端兼容两个名字。

2026-10-06 只读核对的脱敏结果：已存在的订单 `order/detail` 返回 `orderStatus=2`、原价与实际支付均为 `0.16`、`promotionList=[]`；对应设备 sync 已返回 `status=6`、`amount=null`。因此设备状态不应作为读取已结算账单的唯一入口。

同次账户查询返回 `integral=32`、`integralAmount=0.08`，积分规则返回 `minUsageCount=40`、`userIntegral=false`，风控返回 `true`。积分余额不是人民币，也不能按固定“100 积分抵 1 元”换算。本应用只展示账单中实际返回的积分抵扣金额和接口给出的限制原因。

账单查询先按订单号读取已有记录，未生成时再尝试建单，并按 1500ms 间隔重试。单请求最多 5 秒，前台结算最多 45 秒；仍未确认则保留订单，延后补查，查看订单或返回 App 时继续查询。补回后同步更新首页卡片与统计。

首页累计花费从本机现存记录迁移，之后按订单更新差额；不包含之前已被裁剪或仅在官方 App 中产生的订单。待确认金额暂不计入，重复补查不重复累计；小票属于支付余额，使用小票也计入花费，积分与优惠才从原价中扣除。累计值单独保存，不随历史列表超过 50 条而减少；退出账号时与历史一起清空。
