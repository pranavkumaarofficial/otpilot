import { WebSocketServer } from "ws";
import http from "http";
import crypto from "crypto";
import fs from "fs";
import path from "path";
import os from "os";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC = path.join(__dirname, "..", "public");
const PORT = parseInt(process.env.PORT || "7890");

// Detect LAN IPs so QR codes use the right address (not localhost)
function getLanIPs() {
  const nets = os.networkInterfaces();
  const ips = [];
  for (const name of Object.keys(nets)) {
    for (const net of nets[name]) {
      if (net.family === "IPv4" && !net.internal) ips.push(net.address);
    }
  }
  // Prefer local RFC-1918 IPs (192.168.x, 10.x, 172.16-31.x) over Tailscale (100.x)
  ips.sort((a, b) => {
    const aLocal = a.startsWith("192.168.") || a.startsWith("10.") || /^172\.(1[6-9]|2\d|3[01])\./.test(a);
    const bLocal = b.startsWith("192.168.") || b.startsWith("10.") || /^172\.(1[6-9]|2\d|3[01])\./.test(b);
    if (aLocal && !bLocal) return -1;
    if (!aLocal && bLocal) return 1;
    return 0;
  });
  return ips;
}
const LAN_IPS = getLanIPs();
const DEFAULT_OTP_TTL = 5 * 60 * 1000;
const VALID_TTLS = [60000, 180000, 300000, 600000];
const MAX_OTPS = 100;

// ── In-memory state ──────────────────────────────────────────────────
const families = new Map();

function createFamily(name) {
  const id = "f_" + crypto.randomBytes(6).toString("base64url");
  const adminToken = crypto.randomBytes(24).toString("base64url");
  const joinToken = crypto.randomBytes(16).toString("base64url");
  families.set(id, {
    name, adminToken, joinToken, adminId: null,
    members: new Map(), otps: [], wsClients: new Map(),
    otpTtl: DEFAULT_OTP_TTL,
    stats: { totalOtps: 0 }
  });
  return { id, adminToken, joinToken };
}

function addMember(family, name) {
  const id = "m_" + crypto.randomBytes(6).toString("base64url");
  const token = crypto.randomBytes(24).toString("base64url");
  family.members.set(id, { id, name, token, joinedAt: Date.now(), online: false });
  return { id, token };
}

// ── Expire old OTPs (uses per-family TTL) ────────────────────────────
setInterval(() => {
  const now = Date.now();
  for (const fam of families.values()) {
    fam.otps = fam.otps.filter(o => now - o.timestamp < fam.otpTtl);
  }
}, 30_000);

// ── MIME types ────────────────────────────────────────────────────────
const MIME = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".json": "application/json", ".png": "image/png", ".svg": "image/svg+xml", ".ico": "image/x-icon", ".webmanifest": "application/manifest+json" };

// ── HTTP server ──────────────────────────────────────────────────────
const server = http.createServer((req, res) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS");
  if (req.method === "OPTIONS") return res.writeHead(204).end();

  const url = new URL(req.url, `http://${req.headers.host}`);

  // ── API routes ──
  if (url.pathname === "/api/family" && req.method === "POST") return handleCreateFamily(req, res);
  if (url.pathname.match(/^\/api\/family\/[^/]+\/join$/) && req.method === "POST") return handleJoin(req, res, url);
  if (url.pathname.match(/^\/api\/family\/[^/]+\/otp$/) && req.method === "POST") return handleOtp(req, res, url);
  if (url.pathname.match(/^\/api\/family\/[^/]+\/otps$/) && req.method === "GET") return handleGetOtps(req, res, url);
  if (url.pathname.match(/^\/api\/family\/[^/]+\/members$/) && req.method === "GET") return handleGetMembers(req, res, url);
  if (url.pathname.match(/^\/api\/family\/[^/]+\/members\/[^/]+$/) && req.method === "DELETE") return handleRemoveMember(req, res, url);
  if (url.pathname.match(/^\/api\/family\/[^/]+\/settings$/) && req.method === "PATCH") return handleUpdateSettings(req, res, url);
  if (url.pathname.match(/^\/api\/family\/[^/]+\/stats$/) && req.method === "GET") return handleGetStats(req, res, url);
  if (url.pathname.match(/^\/api\/family\/[^/]+\/regenerate-invite$/) && req.method === "POST") return handleRegenerateInvite(req, res, url);
  if (url.pathname.match(/^\/api\/family\/[^/]+\/name$/) && req.method === "PATCH") return handleUpdateName(req, res, url);
  if (url.pathname === "/health") return res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ ok: true, families: families.size, uptime: process.uptime() | 0, lanIPs: LAN_IPS }));
  if (url.pathname === "/api/relay-url") {
    const host = process.env.RELAY_HOST || (LAN_IPS.length ? LAN_IPS[0] : req.headers.host.split(":")[0]);
    const proto = req.headers["x-forwarded-proto"] || "http";
    return res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({ url: `${proto}://${host}:${PORT}`, ips: LAN_IPS }));
  }

  // ── Static files ──
  let filePath = path.join(PUBLIC, url.pathname === "/" ? "index.html" : url.pathname);
  if (!filePath.startsWith(PUBLIC)) return res.writeHead(403).end();
  const ext = path.extname(filePath);
  fs.stat(filePath, (err, stat) => {
    if (err) return res.writeHead(404).end("not found");
    const noCache = ext === ".html" || url.pathname === "/sw.js";
    res.writeHead(200, { "Content-Type": MIME[ext] || "application/octet-stream", "Content-Length": stat.size, "Cache-Control": noCache ? "no-cache" : "public, max-age=86400" });
    fs.createReadStream(filePath).pipe(res);
  });
});

// ── API handlers ─────────────────────────────────────────────────────
function readBody(req) {
  return new Promise((resolve, reject) => {
    let d = ""; req.on("data", c => d += c); req.on("end", () => { try { resolve(JSON.parse(d)); } catch { reject(); } });
  });
}
function json(res, code, data) { res.writeHead(code, { "Content-Type": "application/json" }).end(JSON.stringify(data)); }
function getFamilyId(url) { return url.pathname.split("/")[3]; }
function getMemberId(url) { return url.pathname.split("/")[5]; }
function authMember(req, family) {
  const t = (req.headers.authorization || "").replace("Bearer ", "");
  for (const [id, m] of family.members) { if (m.token === t) return m; }
  return null;
}

async function handleCreateFamily(req, res) {
  try {
    const { name } = await readBody(req);
    if (!name) return json(res, 400, { error: "name required" });
    const { id, adminToken, joinToken } = createFamily(name);
    json(res, 201, { familyId: id, adminToken, joinToken, name });
  } catch { json(res, 400, { error: "invalid body" }); }
}

async function handleJoin(req, res, url) {
  try {
    const fam = families.get(getFamilyId(url));
    if (!fam) return json(res, 404, { error: "family not found" });
    const { joinToken, name } = await readBody(req);
    if (joinToken !== fam.joinToken) return json(res, 403, { error: "invalid join token" });
    if (!name) return json(res, 400, { error: "name required" });
    const member = addMember(fam, name);
    if (!fam.adminId) fam.adminId = member.id;
    broadcastEvent(fam, { type: "member_joined", data: { id: member.id, name } });
    json(res, 201, { memberId: member.id, memberToken: member.token, familyName: fam.name });
  } catch { json(res, 400, { error: "invalid body" }); }
}

async function handleOtp(req, res, url) {
  const fam = families.get(getFamilyId(url));
  if (!fam) return json(res, 404, { error: "family not found" });
  const member = authMember(req, fam);
  if (!member) return json(res, 401, { error: "unauthorized" });
  try {
    const body = await readBody(req);
    const otp = { id: crypto.randomUUID(), memberId: member.id, memberName: member.name, encrypted: body.encrypted, timestamp: Date.now() };
    fam.otps.unshift(otp);
    if (fam.otps.length > MAX_OTPS) fam.otps.length = MAX_OTPS;
    fam.stats.totalOtps++;
    broadcastEvent(fam, { type: "new_otp", data: otp });
    console.log(`[${fam.name}] OTP from ${member.name}`);
    json(res, 200, { ok: true, id: otp.id });
  } catch { json(res, 400, { error: "invalid body" }); }
}

function handleGetOtps(req, res, url) {
  const fam = families.get(getFamilyId(url));
  if (!fam) return json(res, 404, { error: "family not found" });
  if (!authMember(req, fam)) return json(res, 401, { error: "unauthorized" });
  const now = Date.now();
  json(res, 200, fam.otps.filter(o => now - o.timestamp < fam.otpTtl));
}

function handleGetMembers(req, res, url) {
  const fam = families.get(getFamilyId(url));
  if (!fam) return json(res, 404, { error: "family not found" });
  if (!authMember(req, fam)) return json(res, 401, { error: "unauthorized" });
  const members = [...fam.members.values()].map(m => ({ id: m.id, name: m.name, online: m.online, joinedAt: m.joinedAt }));
  json(res, 200, { members, familyName: fam.name, adminId: fam.adminId });
}

async function handleRemoveMember(req, res, url) {
  const fam = families.get(getFamilyId(url));
  if (!fam) return json(res, 404, { error: "family not found" });
  const t = (req.headers.authorization || "").replace("Bearer ", "");
  if (t !== fam.adminToken) return json(res, 403, { error: "admin only" });
  const mid = getMemberId(url);
  const removed = fam.members.get(mid);
  const removedName = removed ? removed.name : "Unknown";
  fam.members.delete(mid);
  const memberWs = fam.wsClients.get(mid);
  if (memberWs) { memberWs.close(4010, "removed"); fam.wsClients.delete(mid); }
  broadcastEvent(fam, { type: "member_left", data: { id: mid, name: removedName } });
  json(res, 200, { ok: true });
}

async function handleUpdateSettings(req, res, url) {
  const fam = families.get(getFamilyId(url));
  if (!fam) return json(res, 404, { error: "family not found" });
  const t = (req.headers.authorization || "").replace("Bearer ", "");
  if (t !== fam.adminToken) return json(res, 403, { error: "admin only" });
  try {
    const body = await readBody(req);
    if (body.otpTtl && VALID_TTLS.includes(body.otpTtl)) {
      fam.otpTtl = body.otpTtl;
    }
    broadcastEvent(fam, { type: "settings_updated", data: { otpTtl: fam.otpTtl } });
    json(res, 200, { ok: true, otpTtl: fam.otpTtl });
  } catch { json(res, 400, { error: "invalid body" }); }
}

function handleGetStats(req, res, url) {
  const fam = families.get(getFamilyId(url));
  if (!fam) return json(res, 404, { error: "family not found" });
  if (!authMember(req, fam)) return json(res, 401, { error: "unauthorized" });
  const now = Date.now();
  json(res, 200, {
    totalMembers: fam.members.size,
    onlineMembers: [...fam.members.values()].filter(m => m.online).length,
    totalOtps: fam.stats.totalOtps,
    activeOtps: fam.otps.filter(o => now - o.timestamp < fam.otpTtl).length,
    otpTtl: fam.otpTtl
  });
}

async function handleRegenerateInvite(req, res, url) {
  const fam = families.get(getFamilyId(url));
  if (!fam) return json(res, 404, { error: "family not found" });
  const t = (req.headers.authorization || "").replace("Bearer ", "");
  if (t !== fam.adminToken) return json(res, 403, { error: "admin only" });
  fam.joinToken = crypto.randomBytes(16).toString("base64url");
  json(res, 200, { joinToken: fam.joinToken });
}

async function handleUpdateName(req, res, url) {
  const fam = families.get(getFamilyId(url));
  if (!fam) return json(res, 404, { error: "family not found" });
  const member = authMember(req, fam);
  if (!member) return json(res, 401, { error: "unauthorized" });
  try {
    const { name } = await readBody(req);
    if (!name || !name.trim()) return json(res, 400, { error: "name required" });
    member.name = name.trim();
    broadcastEvent(fam, { type: "members", data: [...fam.members.values()].map(m => ({ id: m.id, name: m.name, online: m.online })), adminId: fam.adminId });
    json(res, 200, { ok: true, name: member.name });
  } catch { json(res, 400, { error: "invalid body" }); }
}

// ── WebSocket ────────────────────────────────────────────────────────
const wss = new WebSocketServer({ server });

wss.on("connection", (ws, req) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const familyId = url.searchParams.get("familyId");
  const memberId = url.searchParams.get("memberId");
  const token = url.searchParams.get("token");
  const fam = families.get(familyId);

  if (!fam) return ws.close(4004, "family not found");
  const member = fam.members.get(memberId);
  if (!member || member.token !== token) return ws.close(4001, "unauthorized");

  member.online = true;
  fam.wsClients.set(memberId, ws);
  broadcastEvent(fam, { type: "presence", data: { id: memberId, name: member.name, online: true } });

  // Send recent OTPs on connect
  const now = Date.now();
  const recent = fam.otps.filter(o => now - o.timestamp < fam.otpTtl);
  ws.send(JSON.stringify({ type: "history", data: recent }));

  // Send member list with admin info
  const members = [...fam.members.values()].map(m => ({ id: m.id, name: m.name, online: m.online }));
  ws.send(JSON.stringify({ type: "members", data: members, adminId: fam.adminId }));

  // Send current settings
  ws.send(JSON.stringify({ type: "settings_updated", data: { otpTtl: fam.otpTtl } }));

  ws.on("close", () => {
    member.online = false;
    fam.wsClients.delete(memberId);
    broadcastEvent(fam, { type: "presence", data: { id: memberId, name: member.name, online: false } });
  });

  ws.on("error", () => { member.online = false; fam.wsClients.delete(memberId); });
});

function broadcastEvent(family, event) {
  const msg = JSON.stringify(event);
  for (const ws of family.wsClients.values()) {
    if (ws.readyState === 1) ws.send(msg);
  }
}

// ── Start ────────────────────────────────────────────────────────────
server.listen(PORT, () => {
  const lanUrl = LAN_IPS.length ? `http://${LAN_IPS[0]}:${PORT}` : "(no LAN IP found)";
  console.log(`
  otpilot v0.3.0 — Family OTP mesh

  Local:    http://localhost:${PORT}
  Network:  ${lanUrl}

  Open the Network URL on other devices to join.
  `);
  if (LAN_IPS.length > 1) console.log(`  Other IPs: ${LAN_IPS.slice(1).join(", ")}\n`);
});
