# 下一班 · nextbus

> 通勤公交 + 小米澎湃OS 超级岛到站提醒。
> App 名「下一班」，项目/仓库名 `nextbus`，包名 `com.yg.busisland`。

![icon](docs/screenshots/app_icon.png)

## 1. 目录

```
车来了.apk                 样本
tools/probe.mjs            协议参考实现 / 命令行探针（Node 18+，无依赖）
tools/jadx/                反编译工具（本地）
build/classes6.dex         SDK dex（从 apk 提取）
build/src6/sources/        jadx 反编译产物
docs/api.md                协议文档
app/                       Android 工程（Kotlin，无第三方依赖）
```

## 2. 协议验证

```bash
node tools/probe.mjs search --city 027 --key 1
node tools/probe.mjs route  --city 027 --line 010-1-0 --name 1
node tools/probe.mjs real   --city 027 --line 010-1-0 --name 1 --auto
```

要点（详见文档）：

- 公共参数 `s=android&v=7.6.8&vc=436&udid=..&sign=..`
- `sign = Base64(DESede/ECB/PKCS5("woqunimalegebi1234567890", 毫秒时间戳))`
- 实时接口额外需要 `timeStamp` + `cryptoSignStr = MD5("action=<path>&udid=..&timeStamp=.." + "#qwihrnbtmj")`
- 加密响应：`data.encryptResult` → `Base64(iv12 || AES-256-GCM(..) || tag16)`，key `5A3FC1D0BB4DFFE2D18BF10363BCE72C`

## 3. 构建

本机已配置 Android SDK（`D:\Android\Sdk`）与 JDK 17。

```powershell
$env:JAVA_HOME="C:\Users\yzj03\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2"
$gradle="C:\Users\yzj03\.gradle\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat"
& $gradle "-Dorg.gradle.java.home=C:/Users/yzj03/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2" :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（compileSdk 33 / minSdk 26 / targetSdk 33 + 高德定位 SDK）

### 3.1 高德 Key 与签名

定位用高德 SDK（与车来了同源，Wi-Fi/基站库一致），Key 在 `local.properties`：

```properties
amap.key=你的32位key
```

打包使用固定签名 `keystore/busisland.jks`（别名 `busisland`，口令 `busisland123`；**该文件不随仓库分发**，需自己生成并在本地保留，否则换签名后装机会导致数据被清空），高德后台绑定信息必须是：

| 项 | 值 |
|---|---|
| 包名 | `com.yg.busisland` |
| SHA1 | `7A:8D:B4:69:69:D5:69:70:97:BB:62:5D:94:A5:51:FF:6B:95:C3:1B` |
| SHA256 | `AE:AC:12:84:06:E7:C0:21:A9:B7:D3:91:DB:07:B2:1B:88:39:B7:53:34:7D:02:BC:72:12:46:52:ED:A0:8C:0A` |

未配置 key 时 App 自动回退系统 `fused/network/gps` 定位，不会崩。

## 4. 安装与授权（小米澎湃OS）

1. 开发者选项 → 打开「USB 安装」（需登录小米账号，部分机型需插卡）。
2. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. 打开 App，授予 **定位** 与 **通知** 权限。
4. **关键**：设置 → 通知与控制中心 → 应用通知管理 → 公交灵动岛 → 打开「允许通知」；如需官方岛模板再打开「焦点通知」。
5. 设置 → 应用设置 → 公交灵动岛 → 省电策略 → 无限制（避免后台被杀）。

App 内「超级岛」页显示系统支持、协议版本、焦点通知权限。

### 4.1 上岛方式（实测结论）

本机 HyperOS 3.0 / Android 16（`persist.sys.feature.island=1`，`notification_focus_protocol=3`）实测：

| 方式 | 结果 |
|---|---|
| 官方 `miui.focus.param`（protocol 1，`param_v2`） | 通知被系统识别为 `focusType=PARAMS`，但 SystemUI `FocusPlugin` 报 `onAuthFailed` 并 `removeByKey` —— 第三方未获小米授权时无法上岛 |
| **媒体通知（MediaStyle + MediaSession）** | **成功**：状态栏出现本应用图标的岛，点开为大岛卡片，显示「1 路 · 3 分钟 / 往 新首钢 · 下一班 8 分钟」 |

因此 App 默认 `mediaMode=true`（媒体通道上岛）；若你的应用在小米侧拿到了焦点通知授权，可在「超级岛」页切回 `miui.focus.param` 模式。

![app](docs/screenshots/app_main.png)
![island](docs/screenshots/island_expanded.png)

## 5. 使用

1. **城市**：App 会用「家/公司坐标」或「当前位置」调车来了 `localCity` 自动识别（实测定位到乌鲁木齐 → 自动切到 `001`），也可手动按中文名或拼音搜索切换（如 `beijing`）。
2. 设置「家」「公司」：点进去是**选点页**——
   - 「📍 用当前位置」：**高德定位**（实测 2 秒、精度 30m），失败回退系统定位
   - 搜索地点名（车来了 POI 接口，按当前城市返回本地结果）
   - 「📋 粘贴坐标」：从高德/微信复制 `116.397,39.908` 直接粘贴
   - 「手动输入经纬度」兜底
3. 「通勤时段 / 工作日」：早晚各一段，支持跨零点。
4. 「订阅线路」：选绑定地点 → 输入线路关键词 → 选线路 → 选上车站。
5. 打开「启用通勤监控」。

![picker](docs/screenshots/place_picker.png)

![picker](docs/screenshots/place_picker.png)

运行逻辑：
- 时段内 + 距家/公司 ≤ 半径（默认 800m）→ 每 45s 轮询订阅线路
- 有车：岛上显示「线路 · 最快一班 · 往 X · 下一班 Y」
- 无车/停运：显示线路状态（如「不在运营时间」）
- 离开围栏 / 非时段 / 关闭开关 → 撤销岛通知
- 非澎湃OS或未授权 → 自动降级为普通常驻通知


## 5.1 界面

Material 3（`com.google.android.material:material:1.9.0` + `androidx.appcompat`），edge-to-edge，深色模式跟随系统：

- **主页**：大标题 + 状态胶囊（● 监控中 / 🏝 岛状态）→ 「通勤监控」卡（开关、城市、灵动岛、最近状态）→ 「地点」卡（家/公司）→ 「提醒规则」卡（时段、订阅线路列表）→ 「高级」卡（围栏与刷新、调试）→ 底部主按钮「立即查询一次 / 发送测试岛」
- **选点页**：顶栏返回 + 搜索框（回车即搜）→「用当前位置 / 粘贴坐标」双按钮 + 手动输入 → 结果卡片列表，点卡片弹保存确认
- 状态栏、导航栏透明，内容延伸到状态栏下方，图标色跟随深浅色自动切换

![main](docs/screenshots/app_main.png)
![picker](docs/screenshots/place_picker.png)


## 5.2 临时查询 / 缓存兜底 / 后台保活

- **临时查询**：主页「立即查询一次」不再受通勤时段和围栏限制——写入 `adhocUntil = now + 10min`，服务在这 10 分钟内按 20s 巡检、抓一次订阅线路就上岛，到点自动收回。开关没开也能用（`evaluate()` 先判断 adhoc 再判断 enabled）。状态胶囊会变成「● 临时查询」。
- **缓存兜底**：每次成功渲染都把标题/时间/副标题存进 `lastOk*`；请求失败时不再撤岛，而是显示「3 分钟前 · 往 泰山街加气站」，避免断网时岛突然消失。
- **后台保活**：主页新增「后台运行」卡片，三项各自跳系统页：
  - 自启动 → `com.miui.securitycenter/…AutoStartManagementActivity`（找不到就退回应用详情）
  - 电池优化 → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（MIUI 不支持时退回应用详情），右侧实时显示「已忽略 / 去设置」
  - 省电策略 → `com.miui.powerkeeper/…HiddenAppsConfigActivity`
- 无 ETA 时标题只显示线路名（不再出现「203路 · —」），副标题补线路状态，如「往 泰山街加气站 · 本线路末班时间已过」


## 5.3 到站提醒 / 首末班 / 无障碍

- **到站提醒**：主页「提醒规则 → 到站提醒」可设提前 1/2/3/5/10 分钟；命中时发一条 `IMPORTANCE_HIGH` 通知（渠道 `bus_arrival`，标题「203路 3 分钟后到站」，可响铃/震动），同一条线路同一分钟数 5 分钟内只提醒一次。对话框里还有「发一条测试提醒」。
- **首末班**：无车时副标题优先显示接口的线路状态（如「本线路末班时间已过」），有 `firstTime/lastTime` 时补「首班/末班」。
- **多条线路**：轮播基础上副标题追加「共 N 条」。
- **无障碍**：每个可点击行都设置 `contentDescription`（标题 + 当前值）；深色模式走 Material3 DayNight，胶囊、卡片、按钮均取自主题色（已实机切 Night 验证）。

### 附近站点 → 线路 → 直接订阅

接口最后是找到了，两个端点串起来用：

```
GET /bus/stop!encryptedNearPhysicalStns.action?encLoc=<enc>&cityId=001&<公共参数>
→ data.nearStations[] = { sId, physicalStId, sn, distance, lat, lng }

GET /bus/stop!encryptedPhyStnDetail.action?physicalStId=<id>&encLoc=<enc>&cityId=001&<公共参数>
→ data.stationList[].lines[].line = { lineId, lineNo, name, direction,
                                       startSn, endSn, firstTime, lastTime, stationsNum, price … }
```

- `encLoc` = `Base64(iv12 ‖ AES-256-GCM("lat=<lat>&lng=<lng>&gpstype=gcj"))`，key 用 `AKey.KEY`（与响应解密同一把）
- 期间试错记录：`encryptedRecommLine` 任何参数组合都返回空（是「按用户历史推荐」，不是按站点）；`line!busList` 需要 `lineId`（是「某线路的车」，参数齐全后报 `10004 线路已变更`）；`encryptedHomePage` 解密后 `nearSts/lines` 恒空

新页面 `NearbyLinesActivity`：选地点 → 附近站点卡片（名称 + 距离）→ 点站点弹出该站线路（`203路 往 北郊客运站 首 07:30 末 22:00`）→ 点线路即订阅。订阅时用 `/bus/line!lineRoute.action` 找到上车站在该线路里的 `order` 作为 `targetOrder`，再连同 `sId / stationName / place` 写进 `prefs.lines`。

入口：主页「订阅线路 → 新增 → 选地点 → 看附近站点（推荐）」，另一条路仍是「按线路号搜索」。

实测：家附近 25 个站点，`新钢物资` 有 2 条线路，选 `5032路` 订阅成功（targetOrder=19，stationId=0991-621），重复订阅会提示「已经订阅过了」。

![nearby](docs/screenshots/nearby_lines.png)


### 在陌生地方看附近有什么公交

主页「地点」卡底部多了一行 **🧭 看看附近有什么公交**，进入 `NearbyLinesActivity` 的「当前位置」模式（`EXTRA_USE_CURRENT=true`）：

1. 高德单次定位（失败回退系统 fused/network/gps 缓存），标题显示「当前位置 · 精度 30m」
2. 列出附近站点（名称 + 距离，按距离排序）
3. 点站点 → 一次性拉该站所有线路的**实时到站时间**：对每条线路用 `lineRoute` 求上车站的 `order`（结果按 lineId 缓存），再调 `encRefreshLineDetail` 取 ETA，弹窗显示 `203路 往 北郊客运站 5 分钟 首 07:30 末 22:00`
4. 想留下就选一个地点订阅（`只看不订` 也可以），订阅逻辑与绑地点模式共用

同一页面在「附近站点选线」入口下仍按地点坐标工作，两种模式只是坐标来源不同。



### 线路名称与起终点

`LineSub` 增加 `startSn / endSn`（旧数据缺省空），三处展示：

- 附近公交/选线弹窗：`203路  北郊客运站 ↔ 黄山街   39 分钟   首 07:50 末 22:30`
- 主页订阅列表：`· 203路  泰山街加气站  黄山街 ↔ 北郊客运站  家`
- 岛上无车时副标题：`黄山街 ↔ 北郊客运站 · 本线路末班时间已过 · 泰山街加气站`

来源：站点详情接口里 `line.startSn/endSn` 直接给；「按线路号搜索」这条路没有该字段，取 `lineRoute` 首末站的 `sn` 补上。老订阅在 App 启动时由 `backfillEndpoints()` 补一次（只对缺起终点的跑，命中后写回 prefs）。


### 附近公交：进页面即展示（不点不展开）

原来要点站点才弹线路，现在改成**整页平铺**：

```
🚏 泰山街加气站                     71m
   203路  北郊客运站 ↔ 黄山街        19 分钟
   首 07:50 末 22:30
🚏 嵩山街                           478m
   309路  红桥 ↔ 黄山街              23 分钟
   527路  检查站 ↔ 黄山街            12 分钟
   536A路 家乐佳超市 ↔ 黄山街          —
```

- 取最近的 6 个站点、每站最多 5 条线路，4 线程并发抓完一次性渲染
- **到站时间不再额外请求**：`encryptedPhyStnDetail` 里每条的 `stnStates[0]` 就带 `arrivalTime`（毫秒时间戳）和 `travelTime`（秒），直接换算成「N 分钟 / 即将到站 / —」；顺带 `targetStation.order` 就是上车站在线路里的序号，订阅时直接可用，省掉之前每条线路两次请求的 `lineRoute` + `realtime`
- 点某条线路才弹「订阅到哪个地点」，其余情况零点击

实测一屏：泰山街加气站 71m（203路 19 分钟）、嵩山街 478m（309路 23 分钟、527路 12 分钟）、开发区管委会 483m（309路 21 分钟、527路 10 分钟）。

![nearby inline](docs/screenshots/nearby_inline.png)


## 9. 名称与图标

- 名称：**下一班**（`res/values/strings.xml` → `app_name`），副标题「公交到站 · 上岛提醒」
- 图标：Pillow 生成，5 个密度各一份 `ic_launcher.png` / `ic_launcher_round.png`
  - 深蓝渐变圆角方形底
  - 顶部琥珀色胶囊＝灵动岛
  - 白色公交车头 + 深蓝挡风玻璃 + 两个琥珀色大灯
- 生成脚本逻辑在 `tools/`（Pillow：渐变 + 圆角蒙版 + 缩放 LANCZOS）
- 注意：MIUI 桌面/设置对图标缓存很顽固，换图标后旧缓存可能还显示占位图，重启一次即可刷新；APK 内的资源已核对（`aapt2 dump badging` → `icon='res/mipmap-mdpi-v4/ic_launcher.png'`）


### 到站提醒的提示音

- 新渠道 `bus_arrival_sound`：`IMPORTANCE_HIGH` + 系统默认通知音（`RingtoneManager.TYPE_NOTIFICATION`，`USAGE_NOTIFICATION`）+ 震动（0/250/200/250）+ 锁屏可见
- 另建静音渠道 `bus_arrival_silent`（`setSound(null,null)`），「到站提醒」对话框里可切「提示音：开/关」，切换即换渠道
- 渠道属性创建后不可改，所以用两个 id 而不是改同一个渠道
- 岛本身走 `bus_island` 渠道且 `setOnlyAlertOnce(true)`，同一条通知只在首次出现时响一次，不会每 45 秒叫


### 提示音（跟随系统通知管理）

「提醒规则 → 到站提醒」里 `提示音：<当前铃声>（点击修改）`，点它直接跳到 **设置 → 通知管理 → 下一班 → 到站提醒 → 声音**。

那个页面是系统的：有中文名（水滴、南美雨林、东非草原、宿鸟流萤…）、点一下试听、**选中后还要按「应用/确定」**才生效。

App 侧逻辑：

- 渠道 `bus_arrival_v6` 带系统默认铃声；渠道里是什么铃声就由系统响
- 渠道被设成「无铃声」时，App 兜底用 `Sfx` 自己响一声（3 秒截断），避免完全没声
- 渠道自己有铃声时不重复播放（只系统播一次）
- 对话框标题实时显示渠道当前铃声名（读 `NotificationChannel.getSound()`）
- 静音渠道 `bus_arrival_v6_silent` 供「提示音开关」关闭时使用


### 通知去重

早期每个版本改过渠道 id（`bus_arrival`→`_sound`→`_v3`→`_v5`→`_v6`…），MIUI 会把不同渠道的提醒当不同通知，于是通知栏堆出好几条「到站提醒」。

- `Notify.cleanupOldChannels()`：启动时删除所有历史渠道（`deleteNotificationChannel`，变成 deleted 墓碑，不再出现在通知管理里）
- `Notify.clearReminders()`：启动时 `cancel(2001)` 清一次残留
- 发提醒不再「先 cancel 再 notify」（MIUI 会当成新通知），固定 id 2001 覆盖更新
- 现在只剩三个渠道：`bus_monitor`（后台监控，MIN）、`bus_island`（公交灵动岛）、`bus_arrival_v6`（到站提醒，HIGH，发提醒时创建）

## 6. 调试

```powershell
adb logcat -s BusIslandSelfTest:I
adb shell am start -n com.yg.busisland/.MainActivity --ez selftest true
```

自检会串行执行 搜索 → 站点 → 实时，把结果写入 logcat 与界面。

App 内「调试 / 协议信息」显示最近一次请求 URL 与响应片段。

## 7. 超级岛：为什么用媒体卡片

官方「焦点通知」模板（打车 App 那种岛）在 `miui.systemui.plugin` 的 `FocusNotificationController.fetchAuthResult()` 里鉴权：

```
1. 包名 ∈ config_canShowFocusPackages（小米白名单）→ 放行
2. IS_INTERNATIONAL_BUILD → 放行
3. 无 miui.focus.rv 自定义视图 → SignatureChecker（白名单签名），否则 AuthManager 云端鉴权
4. 有自定义视图 → canCustomFocus(config_canShowCustomFocusPackages) 或 promoted-ongoing → 放行
```

自签名 App 会走到 `AuthManager`，`onAuthFailed` 后通知被 `removeByKey` 删除。实测三条绕路均失败：

| 尝试 | 结果 |
|---|---|
| `miui.focus.param` + `param_v2`（协议 1/3、A/B 区都试过） | `onAuthFailed`，通知被移除 |
| 加 `miui.focus.rv` 自定义 RemoteViews | 仍被判为非焦点通知 |
| 上述 + Android 16 `FLAG_PROMOTED_ONGOING` + `miui.focus.isPromoted` | 仍 `onAuthFailed` |

正式开通要发邮件到 `mipush-permission@xiaomi.com`（模板强制要求公司主体、appid、channel_id、场景与 UI 方案），通过后由小米平台给包名配权限——个人开发者走不通。

因此当前采用**媒体通道**（MediaSession + MediaStyle）上岛：免授权、开箱可用；代价是外壳带音乐卡样式（专辑图、进度条、耳机图标），App 侧无法去除。

将来若拿到白名单，把 `prefs.mediaMode` 置 false（主页「超级岛 → 切换样式」）即走官方 `miui.focus.param` 模板，代码无需改动。

## 8. 省电与显示策略

**只在通勤时段工作**

- 非通勤时段：服务直接 `stopSelf()`，撤销前台通知、取消岛、注销所有定位监听，只用 `AlarmManager` 在下一个时段起点唤醒（`Scheduler.nextBoundary` 覆盖开始/结束两端）
- 通勤时段内才注册定位（fused/GPS/network），并且按距离分级：

| 到常去地点距离 | 定位方式 | 巡检间隔 | 轮询间隔 |
|---|---|---|---|
| ≤ 半径（默认 800m） | fused + GPS + network，60s/100m | 20s | `intervalSec`，>0.7 倍半径时 ×2 |
| 半径 ~ 2 倍半径 | 同上 | 40s | — |
| > 2 倍半径 | 只 fused + network，180s/500m | 120s | 不轮询、撤岛 |

**到站信息只显示时间**

`ChelaileClient.normalizeEta()` 统一归一：

- 接口给时刻（`22:03`）→ 换算成剩余分钟（跨零点自动 +24h；≤0 显示「即将到站」）
- 接口给距离（米/公里）→ 按 15km/h（250 m/min）折算成分钟
- 已经是「N 分钟 / 即将到站 / 等待发车 / 未发车」→ 原样保留
- 岛上不再出现「距站 800m」「1.2 公里」这类数值

## 9. 已知限制

- 到站时间字段随城市/时段不同，客户端已做 `arriveTime / travelTime / preArrivalTime` 多字段兜底，仍可能显示距离而非分钟。
- 深夜 `buses` 为空属正常（`line.desc = 不在运营时间`）。
- 未实现登录/收藏/换乘；仅使用匿名 `udid`。
- 媒体通道上岛会带媒体控件外观（暂停/进度条）；官方焦点通知模板需小米授权后才能使用。
- 焦点通知开关与「允许通知」是两套开关，缺一不可（前者影响 `canShowFocus`，后者影响通知能否发出）。
