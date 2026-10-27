#!/usr/bin/env node
/**
 * 车来了 API 探针 / 参考实现
 *
 * 用法:
 *   node tools/probe.mjs search  --city 027 --key 1
 *   node tools/probe.mjs route   --city 027 --line 010-1-0 --name 1
 *   node tools/probe.mjs real    --city 027 --line 010-1-0 --name 1 --dir 0 --order 2 --station 新首钢 --sid 010-23889
 *   node tools/probe.mjs real    --city 027 --line 010-1-0 --name 1 --auto   # 自动取第 2 站
 *
 * 协议来源: com.ygkj.chelaile.standard 7.6.8 (classes6.dex, dev.xesam.chelaile.sdk.*)
 */
import crypto from "node:crypto";
import https from "node:https";
import { URL } from "node:url";

// ---------------------------------------------------------------- 常量
const HOST = "api.chelaile.net.cn";
const SIGN_KEY = Buffer.from("woqunimalegebi1234567890", "utf8"); // DefaultRequestSigner 3DES key
const GCM_KEY = Buffer.from("5A3FC1D0BB4DFFE2D18BF10363BCE72C", "utf8"); // AKey.KEY (AES-256, 32B ASCII)
const CRYPT_SUFFIX = "#qwihrnbtmj";
const APP_VERSION = "7.6.8";
const APP_VC = "436";
const SYS = "android";
const UA = "okhttp/4.9.0";

const md5 = (s) => crypto.createHash("md5").update(s, "utf8").digest("hex");

/** sign = Base64(DESede/ECB/PKCS5(key, String(currentTimeMillis))) */
function requestSign() {
  const cipher = crypto.createCipheriv("des-ede3", SIGN_KEY, null);
  const ts = String(Date.now());
  return Buffer.concat([cipher.update(ts, "utf8"), cipher.final()]).toString("base64");
}

/** 与 ParamProviderUtil.inflateQueries 等价（URLEncoder.encode，空格为 +） */
function inflate(obj) {
  return Object.entries(obj)
    .map(([k, v]) => `${k}=${encodeURIComponent(v == null ? "" : String(v))}`)
    .join("&");
}

/** CryptoUtil.getUrl：追加 timeStamp + cryptoSignStr */
function cryptoSignedUrl(path, params) {
  const t = Date.now();
  const udid = params.udid ?? "";
  const accountId = params.accountId ?? "";
  let raw = `action=${path}`;
  if (udid) raw += `&udid=${udid}`;
  if (accountId) raw += `&accountId=${accountId}`;
  raw += `&timeStamp=${t}${CRYPT_SUFFIX}`;
  return `https://${HOST}${path}?${inflate({ ...params, timeStamp: t, cryptoSignStr: md5(raw) })}`;
}

function get(url) {
  return new Promise((resolve, reject) => {
    const req = https.get(
      new URL(url),
      { headers: { "User-Agent": UA, "Accept-Encoding": "identity" } },
      (res) => {
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString("utf8"), headers: res.headers }));
      }
    );
    req.on("error", reject);
    req.setTimeout(20000, () => req.destroy(new Error("timeout")));
  });
}

/** **YGKJ{...}YGKJ## 去壳 + encryptResult 解密 */
function parseEnvelope(body) {
  const m = body.match(/\*\*YGKJ([\s\S]*?)YGKJ##/);
  if (!m) throw new Error(`bad envelope: ${body.slice(0, 200)}`);
  const json = JSON.parse(m[1]);
  const jr = json.jsonr ?? {};
  if (jr.data && jr.data.encryptResult) {
    const raw = Buffer.from(jr.data.encryptResult, "base64");
    const iv = raw.subarray(0, 12);
    const tag = raw.subarray(raw.length - 16);
    const ct = raw.subarray(12, raw.length - 16);
    const d = crypto.createDecipheriv("aes-256-gcm", GCM_KEY, iv);
    d.setAuthTag(tag);
    const plain = Buffer.concat([d.update(ct), d.final()]).toString("utf8");
    jr.data = JSON.parse(plain);
  }
  return jr;
}

function commonParams(cityId, udid) {
  return {
    s: SYS,
    v: APP_VERSION,
    vc: APP_VC,
    sv: "16",
    udid,
    sign: requestSign(),
    cityId,
  };
}

// ---------------------------------------------------------------- 业务
async function search(cityId, keyword) {
  const p = { ...commonParams(cityId), key: keyword, fileds: "line" };
  const { body } = await get(`https://${HOST}/bus/query!encryptedSearchMore.action?${inflate(p)}`);
  const jr = parseEnvelope(body);
  if (jr.status !== "00") throw new Error(`${jr.status} ${jr.errmsg}`);
  return jr.data.result?.lines ?? [];
}

async function route(cityId, lineId, lineName) {
  const p = { ...commonParams(cityId), lineId, lineName };
  const { body } = await get(`https://${HOST}/bus/line!lineRoute.action?${inflate(p)}`);
  const jr = parseEnvelope(body);
  if (jr.status !== "00") throw new Error(`${jr.status} ${jr.errmsg}`);
  return jr.data;
}

async function realtime(cityId, lineId, lineName, dir, targetOrder, stationName, stationId, extra = {}) {
  const params = {
    ...commonParams(cityId),
    lineId,
    lineName,
    lineNo: extra.lineNo ?? lineName,
    direction: String(dir),
    targetOrder: String(targetOrder),
    specialTargetOrder: "0",
    stationName,
    stationId,
    filter: "0",
    isNewLineDetail: "1",
    hpGnv: "1",
  };
  const url = cryptoSignedUrl("/bus/line!encRefreshLineDetail.action", params);
  const { body } = await get(url);
  const jr = parseEnvelope(body);
  if (jr.status !== "00") throw new Error(`${jr.status} ${jr.errmsg}`);
  const mod = (jr.data.modules ?? []).find((m) => m.data && m.data.buses);
  return mod ? mod.data : jr.data;
}

// ---------------------------------------------------------------- CLI
function arg(name, def) {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? process.argv[i + 1] : def;
}

function fmtBus(b) {
  const bits = [b.busId ?? "?"];
  if (b.nextStationName) bits.push(`下一站 ${b.nextStationName}`);
  if (typeof b.distanceToWaitStn === "number") bits.push(`距站 ${b.distanceToWaitStn}m`);
  if (b.busStatusDesc) bits.push(b.busStatusDesc);
  if (b.arriveTime) bits.push(`到达 ${b.arriveTime}`);
  return bits.join(" | ");
}

async function main() {
  const cmd = process.argv[2] ?? "search";
  const city = arg("city", "027");
  const udid = arg("udid", "866254041234567");

  if (cmd === "search") {
    const lines = await search(city, arg("key", "1"));
    for (const l of lines.slice(0, 20)) {
      console.log(`${l.lineId}\t${l.name}\t${l.startSn} -> ${l.endSn}\tdir=${l.direction}`);
    }
    console.log(`\ntotal: ${lines.length}`);
    return;
  }

  const lineId = arg("line");
  if (!lineId) throw new Error("--line required");
  const lineName = arg("name", lineId);

  if (cmd === "route") {
    const d = await route(city, lineId, lineName);
    console.log("line:", JSON.stringify(d.line));
    for (const s of d.stations ?? []) {
      console.log(`${s.order}\t${s.sId}\t${s.sn}\t${s.lat},${s.lng}`);
    }
    return;
  }

  if (cmd === "real") {
    let order = arg("order");
    let station = arg("station");
    let sid = arg("sid");
    if (arg("auto") !== undefined || !order) {
      const d = await route(city, lineId, lineName);
      const st = (d.stations ?? [])[1] ?? (d.stations ?? [])[0];
      order = st.order;
      station = st.sn;
      sid = st.sId;
    }
    const data = await realtime(city, lineId, lineName, arg("dir", "0"), order, station, sid, {
      lineNo: arg("no", lineName),
    });
    console.log("line:", JSON.stringify(data.line));
    console.log("preArrivalTime:", data.preArrivalTime ?? data.hlineElement?.preArrivalTime ?? "-");
    if (arg("raw")) { console.log(JSON.stringify(data.buses ?? [], null, 1)); return; }
    console.log("realData:", data.realData, "buses:", (data.buses ?? []).length);
    for (const b of data.buses ?? []) console.log("  " + fmtBus(b));
    return;
  }

  throw new Error(`unknown command: ${cmd}`);
}

main().catch((e) => {
  console.error("ERROR:", e.message);
  process.exit(1);
});
