// 试验：/bus/stop!encryptedNearPhysicalStns.action 为什么解不出坐标（userLat=-1）
// node tools/near.mjs
import crypto from "node:crypto";

const HOST = "api.chelaile.net.cn";
const SIGN_KEY = Buffer.from("woqunimalegebi1234567890", "utf8");
const GCM_KEY = Buffer.from("5A3FC1D0BB4DFFE2D18BF10363BCE72C", "utf8");
const CRYPT_SUFFIX = "#qwihrnbtmj";
const UDID = "5690c587980e427f";

const md5 = (s) => crypto.createHash("md5").update(s, "utf8").digest("hex");

function requestSign() {
  const cipher = crypto.createCipheriv("des-ede3", SIGN_KEY, null);
  return Buffer.concat([
    cipher.update(String(Date.now()), "utf8"),
    cipher.final(),
  ]).toString("base64");
}

function encLoc(plain) {
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv("aes-256-gcm", GCM_KEY, iv);
  const ct = Buffer.concat([c.update(plain, "utf8"), c.final()]);
  return Buffer.concat([iv, ct, c.getAuthTag()]).toString("base64");
}

function common(cityId) {
  return {
    s: "android",
    v: "7.6.8",
    vc: "436",
    sv: "16",
    udid: UDID,
    sign: requestSign(),
    cityId,
  };
}

function url(path, params, encode) {
  const t = Date.now();
  const raw = `action=${path}&udid=${UDID}&timeStamp=${t}${CRYPT_SUFFIX}`;
  const all = { ...params, timeStamp: t, cryptoSignStr: md5(raw) };
  const qs = Object.entries(all)
    .map(([k, v]) => {
      const s = String(v ?? "");
      // encLoc 里的 + / = 是否再编码，两种都试
      const skip = !encode && k === "encLoc";
      return `${k}=${skip ? s : encodeURIComponent(s)}`;
    })
    .join("&");
  return `https://${HOST}${path}?${qs}`;
}

async function get(u) {
  const r = await fetch(u, { headers: { "User-Agent": "okhttp/4.9.0" } });
  return await r.text();
}

function parseEnvelope(body) {
  const m = body.match(/\*\*YGKJ([\s\S]*?)YGKJ##/);
  if (!m) return { error: body.slice(0, 160) };
  const jr = JSON.parse(m[1]).jsonr ?? {};
  if (jr.data?.encryptResult) {
    const buf = Buffer.from(jr.data.encryptResult, "base64");
    const iv = buf.subarray(0, 12);
    const tag = buf.subarray(buf.length - 16);
    const ct = buf.subarray(12, buf.length - 16);
    const d = crypto.createDecipheriv("aes-256-gcm", GCM_KEY, iv);
    d.setAuthTag(tag);
    try {
      jr.data = JSON.parse(Buffer.concat([d.update(ct), d.final()]).toString("utf8"));
    } catch (e) {
      jr.data = { decryptError: String(e) };
    }
  }
  return jr;
}

const LAT = 43.859546;
const LNG = 87.509362;
const path = "/bus/stop!encryptedNearPhysicalStns.action";
const plain = `lat=${LAT}&lng=${LNG}&gpstype=gcj`;

const cases = [
  ["E 只 encLoc（gcj），坐标 43.90/87.60", { ...common("001"), encLoc: encLoc(`lat=43.90&lng=87.60&gpstype=gcj`) }, true],
  ["F 明文 lat/lng=43.90/87.60 + encLoc 垃圾串", { ...common("001"), encLoc: "AAAAAAAAAAAAAAAAAAAAAA==", lat: 43.90, lng: 87.60, gpstype: "gcj" }, true],
  ["G 明文 lat/lng=43.8595/87.5094 + encLoc 垃圾串", { ...common("001"), encLoc: "AAAAAAAAAAAAAAAAAAAAAA==", lat: LAT, lng: LNG, gpstype: "gcj" }, true],
  ["H 只 encLoc（wgs 标注，坐标 43.90/87.60）", { ...common("001"), encLoc: encLoc(`lat=43.90&lng=87.60&gpstype=wgs`) }, true],
];

for (const [name, params, encode] of cases) {
  try {
    const body = await get(url(path, params, encode));
    const jr = parseEnvelope(body);
    const d = jr.data ?? {};
    const n = Array.isArray(d.nearStations) ? d.nearStations.length : -1;
    console.log(`${name}\n  status=${jr.status} userLat=${d.userLat} userLng=${d.userLng} gpstype=${d.gpstype} near=${n}`);
    if (n > 0) console.log("  first:", JSON.stringify(d.nearStations[0]).slice(0, 180));
  } catch (e) {
    console.log(`${name}\n  ERROR ${e}`);
  }
}
