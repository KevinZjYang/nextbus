# 车来了 API 协议（实测可用）

样本：`com.ygkj.chelaile.standard` v7.6.8 (versionCode 436)，`classes6.dex` 中 `dev.xesam.chelaile.sdk.*`。
以下结论均已在 2026-10-27 线上验证（HTTP 200 / status `00`）。

---

## 1. 网关与信封

| 项 | 值 |
|---|---|
| Base | `https://api.chelaile.net.cn` |
| 备用 | `https://cdn.api.chelaile.net.cn`（新疆等地区，`DomainConfig` 里按城市切换） |
| 包裹 | `**YGKJ{json}YGKJ##` |
| 业务状态 | `jsonr.status == "00"` 为成功；`errmsg` 为错误文案 |
| 传输 | 明文 JSON 或 `jsonr.data.encryptResult`（AES-256-GCM，见 §4） |

错误码参考：`10002 参数错误`、`10004 线路已变更`、`12009 该版本已不提供服务`（旧 `lineDetail` 已下线）。

> 已知 action 但参数不全时，服务端会返回 **200 + 0 字节**；未知 action 返回 404 Spring 白标页。可用此判断"参数是否凑齐"。

---

## 2. 公共参数（缺一不可）

来自 `KpiFixedConfig` / `DefaultRequestSigner` / `FireflyApp.getAppParams`：

| key | 值 | 说明 |
|---|---|---|
| `s` | `android` | `KpiFixedConstant.FIX_PARAM_KEY_SYSTEM_NAME`，**不是**加密串 |
| `v` | `7.6.8` | versionName |
| `vc` | `436` | versionCode |
| `sv` | `16` | Android release（可随意，建议真实值） |
| `udid` | 设备串 | 任意稳定字符串即可（首次为随机/IMEI） |
| `sign` | 见 §3 | 防重放 |
| `cityId` | `027` | 城市 id（027=北京） |
| `accountId` | 可选 | 登录用户 id |

`paramsMakeUp=is` 在客户端补齐参数时出现，服务端不校验，可省略。

### 3. 签名

`DefaultRequestSigner.getSign()`：

```
sign = Base64( DESede/ECB/PKCS5Padding( key = "woqunimalegebi1234567890" (24B ASCII),
                                       data = String(System.currentTimeMillis()) ) )
```

即：把当前毫秒时间戳当明文做 3DES-ECB-PKCS5 加密后 Base64。**无参数参与**，仅时间戳。

另有两个按需使用的摘要（`BusRequest` / `CryptoUtil`）：

```
CRYPT_SUFFIX = "#qwihrnbtmj"
cryptoSign   = MD5( inflate(params).toLowerCase() + CRYPT_SUFFIX )        // 部分接口的 cryptoSign 参数
cryptoSignStr= MD5( "action=" + path + "&udid=" + udid + [&accountId=..] +
                   "&timeStamp=" + ts + CRYPT_SUFFIX )                    // 实时接口的 URL 级签名
```

### 4. 响应解密

`ResponseUtil`：当 `jsonr.data.encryptResult` 存在（或响应头 `cllEncType: aes-gcm-v2`）：

```
raw   = Base64Decode(encryptResult)
iv    = raw[0:12]
tag   = raw[-16:]
ct    = raw[12:-16]
plain = AES-256-GCM(key = "5A3FC1D0BB4DFFE2D18BF10363BCE72C" (32B ASCII), iv, ct, tag)
```

明文为 JSON（如 `{"result":{"lines":[...]}}`）。`AKey.KEY_BEFORE=445AA658EB0912577EE304B5D312C47F` 为灰度旧 key。

### 5. 位置加密（可选）

`ToUrlStringUtil.encryptLocationInfo`：

```
encLoc = Base64( iv(12B) || AES-256-GCM( key=AKey.KEY, plaintext = "lat=..&lng=..&gpstype=gcj" ) )
```

传 `encLoc` 时同时移除明文的 `lat/lng/gpstype`。做公交到站查询可以完全不传位置。

---

## 6. 接口

### 6.0 城市识别

```
GET /goocity/city/localCity?lat=43.8596&lng=87.5094&gpstype=wgs&<公共参数>
→ data.localCity = {cityId:"001", cityName:"乌鲁木齐", lat, lng, pinyin:"WuLuMuQi", ...}

GET /goocity/city/moreCities?<公共参数>
→ data.cities[] = 全部城市（cityId/cityName/pinyin/lat/lng）
```

城市 id 不是行政区划码：北京=027、乌鲁木齐=001。线路 id 形如 `010-1-0`（北京）、`0991-1路-0`（乌鲁木齐）。

### 6.1 搜索线路
```
GET /bus/query!encryptedSearchMore.action
    ?key=<关键词>&fileds=line&<公共参数>
```

`fileds`（原文拼写如此）取值：`line` / `stop` / `poi` / `mapstop`。
响应：`data.encryptResult` → 解密后 `result.lines[]`：

```json
{"lineId":"010-1-0","lineNo":"1","name":"1","direction":0,
 "startSn":"首钢园公交场站","endSn":"四惠枢纽站"}
```

### 6.2 线路走向 / 站点

```
GET /bus/line!lineRoute.action?lineId=<lineId>&lineName=<name>&<公共参数>
```

明文响应：

```json
{"line":{"lineId":"010-1-0","name":"1"},
 "stations":[{"order":1,"sId":"010-23888","sn":"首钢园公交场站","lat":39.9055,"lng":116.1518}],
 "route":[{"stopOrder":1,"lat":..,"lng":..}]}
```

### 6.3 实时到站（线路详情）

```
GET /bus/line!encRefreshLineDetail.action?<参数>&timeStamp=<ms>&cryptoSignStr=<md5>
```

参数（`LineDetailRequest.queryGrayBusesDetail`）：

| key | 例 | 说明 |
|---|---|---|
| `lineId` | `010-1-0` | |
| `lineName` / `lineNo` | `1` | |
| `direction` | `0` | 上行/下行 |
| `targetOrder` | `2` | **目标站序**（从 `lineRoute.stations[].order` 取） |
| `stationId` | `010-23889` | 目标站 `sId` |
| `stationName` | `新首钢` | |
| `specialTargetOrder` | `0` | 默认 0 |
| `filter` | `0` | |
| `isNewLineDetail` | `1` | |
| `hpGnv` | `1` | |
| `timeStamp` | 毫秒 | |
| `cryptoSignStr` | md5 | 见 §3 |

响应（明文 JSON，`data.modules[]`）：

```json
{"modules":[{"type":1,"data":{
   "line":{...},"preArrivalTime":"","realData":true,
   "buses":[{"busId":"..","nextStationName":"..","distanceToWaitStn":1200,
             "order":3,"speed":18,"busStatusDesc":"..","arriveTime":null}]}}]}
```

要点：
- `buses` 空 + `line.desc = "不在运营时间"` / `line.state = -12` 属于正常（夜间）。
- 到站时间字段在不同城市/时段可能为 `arriveTime`、`travelTime` 或模块级 `preArrivalTime`（人类可读，如 `"3分钟"`），客户端应做多字段兜底。
- 传 `lat/lng/gpstype`（GCJ）可让服务端返回基于当前位置的排序；不传也能用。

### 6.4 其他可用接口（同公共参数）

| Path | 用途 |
|---|---|
| `/bus/stop!encryptedHomePage.action` | 首页附近站点 |
| `/bus/stop!encryptedPhyStnDetail.action` | 物理站详情 |
| `/bus/stop!encryptedNearPhysicalStns.action` | 附近物理站（白名单，不做位置剔除） |
| `/bus/line!encNLineDetail.action` | 新线路详情（需同款 cryptoSignStr） |
| `/bus/line!summary.action` | 发车时刻表 |
| `/bus/line!busList.action` | 时间表 |
| `/goocity/config/notices` | 公告 |

---

## 7. 复现

```bash
node tools/probe.mjs search --city 027 --key 1
node tools/probe.mjs route  --city 027 --line 010-1-0 --name 1
node tools/probe.mjs real   --city 027 --line 010-1-0 --name 1 --auto
```

## 8. 逆向索引（jadx）

| 逻辑 | 类 |
|---|---|
| 签名 | `dev.xesam.chelaile.sdk.core.DefaultRequestSigner` |
| 公共参数 | `dev.xesam.chelaile.app.core.FireflyApp#getAppParams`、`dev.xesam.chelaile.kpi.KpiFixedConfig` |
| URL 组装 | `dev.xesam.chelaile.sdk.ToUrlStringUtil`、`DomainConfig`、`ParamProviderUtil` |
| URL 级签名 | `dev.xesam.chelaile.sdk.query.data.source.CryptoUtil` |
| 实时接口参数 | `dev.xesam.chelaile.sdk.query.data.source.request.LineDetailRequest` |
| 搜索参数 | `...request.SearchRequest` |
| 响应解密 | `dev.xesam.chelaile.sdk.core.ResponseUtil`、`AesGcmUtil` |
| 密钥 | `dev.xesam.chelaile.sdk.core.AKey` |

反编译产物：`build/src6sources`（`tools/jadx` + `build/classes6.dex`）。
