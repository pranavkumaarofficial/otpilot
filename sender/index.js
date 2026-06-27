#!/usr/bin/env node
// ──────────────────────────────────────────────────────────────────
//  otpilot sender — runs on each family member's Android phone
//  Requires: Termux + Termux:API + Node.js
//  Install:  pkg install termux-api nodejs
//  Usage:    RELAY=http://100.x.y.z:7890 FAMILY=f_xxx TOKEN=xxx KEY=xxx NAME=Mom node sender.js
// ──────────────────────────────────────────────────────────────────
import { execSync } from "child_process";
import crypto from "crypto";
import https from "https";
import http from "http";

const RELAY   = process.env.RELAY;
const FAMILY  = process.env.FAMILY;
const TOKEN   = process.env.TOKEN;
const KEY_B64 = process.env.KEY;
const NAME    = process.env.NAME || "phone";
const POLL_S  = parseInt(process.env.POLL || "3");
const FILTER  = (process.env.FILTER || "otp|code|pin|verification|verify|delivery|parcel|order").toLowerCase();

if (!RELAY || !FAMILY || !TOKEN || !KEY_B64) {
  console.error("Missing env vars. Required: RELAY, FAMILY, TOKEN, KEY");
  process.exit(1);
}

// ── OTP extraction ───────────────────────────────────────────────
const OTP_RE = [
  /\b(\d{4,8})\b.*(?:otp|code|pin|verification|verify)/i,
  /(?:otp|code|pin|verification|verify).*\b(\d{4,8})\b/i,
  /\b(\d{6})\b/,
];

function extractOTP(text) {
  for (const re of OTP_RE) { const m = text.match(re); if (m) return m[1]; }
  return null;
}

const APP_MAP = {
  amazon:/amazon|amzn/i, flipkart:/flipkart|fkrt/i, swiggy:/swiggy/i, zomato:/zomato/i,
  dunzo:/dunzo/i, blinkit:/blinkit/i, zepto:/zepto/i, bigbasket:/bigbasket/i,
  myntra:/myntra/i, meesho:/meesho/i, google:/google/i, whatsapp:/whatsapp/i,
  uber:/uber/i, ola:/\bola\b/i, rapido:/rapido/i,
  phonepe:/phonepe/i, paytm:/paytm/i, gpay:/gpay|googlepay/i,
  hdfc:/hdfc/i, icici:/icici/i, sbi:/\bsbi\b/i, axis:/axis/i, kotak:/kotak/i,
};

function guessApp(from, body) {
  const t = `${from} ${body}`;
  for (const [name, re] of Object.entries(APP_MAP)) { if (re.test(t)) return name; }
  return "unknown";
}

// ── AES-256-GCM encryption ──────────────────────────────────────
function encrypt(data) {
  const key = Buffer.from(KEY_B64, "base64");
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", key, iv);
  const pt = JSON.stringify(data);
  let ct = cipher.update(pt, "utf8");
  ct = Buffer.concat([ct, cipher.final()]);
  const tag = cipher.getAuthTag();
  return {
    iv: iv.toString("base64"),
    ct: Buffer.concat([ct, tag]).toString("base64"),
  };
}

// ── HTTP POST ────────────────────────────────────────────────────
function post(payload) {
  return new Promise((resolve, reject) => {
    const url = new URL(`${RELAY}/api/family/${FAMILY}/otp`);
    const mod = url.protocol === "https:" ? https : http;
    const body = JSON.stringify({ encrypted: payload });
    const req = mod.request(url, { method: "POST", headers: { "Content-Type": "application/json", "Authorization": `Bearer ${TOKEN}`, "Content-Length": Buffer.byteLength(body) } }, res => {
      let d = ""; res.on("data", c => d += c); res.on("end", () => resolve(d));
    });
    req.on("error", reject);
    req.setTimeout(10000, () => { req.destroy(); reject(new Error("timeout")); });
    req.end(body);
  });
}

// ── SMS polling ──────────────────────────────────────────────────
const seenFile = new URL(".otpilot_seen", `file://${process.env.HOME}/`).pathname;
let seen = new Set();
try { seen = new Set(require("fs").readFileSync(seenFile, "utf8").split("\n").filter(Boolean)); } catch {}

function saveSeen() {
  const arr = [...seen].slice(-200);
  require("fs").writeFileSync(seenFile, arr.join("\n"));
}

async function poll() {
  try {
    const raw = execSync("termux-sms-list -l 10 -t inbox", { encoding: "utf8", timeout: 10000 });
    const msgs = JSON.parse(raw);
    const filterRe = new RegExp(FILTER, "i");

    for (const sms of msgs) {
      const from = sms.number || sms.address || "unknown";
      const body = sms.body || "";
      const received = sms.received || "";
      const id = `${from}_${received}_${body.slice(0, 20)}`;

      if (seen.has(id)) continue;
      if (!filterRe.test(body)) continue;

      const otp = extractOTP(body);
      const app = guessApp(from, body);
      const data = { from, body, otp, app, memberName: NAME, timestamp: Date.now() };
      const encrypted = encrypt(data);

      try {
        await post(encrypted);
        const time = new Date().toLocaleTimeString();
        console.log(`\x1b[36m[${time}]\x1b[0m 📨 ${app} OTP: \x1b[1m${otp || "?"}\x1b[0m from ${from}`);
      } catch (e) {
        console.log(`\x1b[31m[ERR]\x1b[0m Failed to send: ${e.message}`);
      }

      seen.add(id);
    }
    saveSeen();
  } catch (e) {
    if (!e.message.includes("ENOENT")) console.log(`\x1b[31m[ERR]\x1b[0m SMS poll: ${e.message}`);
  }
}

// ── Main ─────────────────────────────────────────────────────────
console.log(`
  ╔═══════════════════════════════════════════╗
  ║       ✈️  otpilot sender v0.2.0           ║
  ║    Forwarding OTPs for: ${NAME.padEnd(16)}  ║
  ╠═══════════════════════════════════════════╣
  ║  Relay:   ${RELAY.slice(0, 30).padEnd(30)}  ║
  ║  Family:  ${FAMILY.padEnd(30)}  ║
  ║  Filter:  ${FILTER.slice(0, 30).padEnd(30)}  ║
  ╚═══════════════════════════════════════════╝
  Polling every ${POLL_S}s — press Ctrl+C to stop.
`);

setInterval(poll, POLL_S * 1000);
poll();
