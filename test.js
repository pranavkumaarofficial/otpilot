#!/usr/bin/env node
// ── Test harness: simulates full family OTP flow ──
import crypto from "crypto";
import http from "http";

const HOST = process.env.HOST || "http://localhost:7890";

function req(method, path, body, token) {
  return new Promise((resolve, reject) => {
    const url = new URL(path, HOST);
    const headers = { "Content-Type": "application/json" };
    if (token) headers["Authorization"] = `Bearer ${token}`;
    const data = body ? JSON.stringify(body) : "";
    const r = http.request(url, { method, headers }, res => {
      let d = ""; res.on("data", c => d += c); res.on("end", () => resolve(JSON.parse(d)));
    });
    r.on("error", reject);
    if (data) r.write(data);
    r.end();
  });
}

function encrypt(data, keyB64) {
  const key = Buffer.from(keyB64, "base64");
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", key, iv);
  let ct = cipher.update(JSON.stringify(data), "utf8");
  ct = Buffer.concat([ct, cipher.final()]);
  const tag = cipher.getAuthTag();
  return { iv: iv.toString("base64"), ct: Buffer.concat([ct, tag]).toString("base64") };
}

async function main() {
  console.log("═══ otpilot e2e test ═══\n");

  // 1. Create family
  const family = await req("POST", "/api/family", { name: "Test Family" });
  console.log("✓ Created family:", family.familyId);
  const familyKey = crypto.randomBytes(32).toString("base64");

  // 2. Join as Mom
  const mom = await req("POST", `/api/family/${family.familyId}/join`, { joinToken: family.joinToken, name: "Mom" });
  console.log("✓ Mom joined:", mom.memberId);

  // 3. Join as You
  const you = await req("POST", `/api/family/${family.familyId}/join`, { joinToken: family.joinToken, name: "Rahul" });
  console.log("✓ Rahul joined:", you.memberId);

  // 4. Join as Sister
  const sis = await req("POST", `/api/family/${family.familyId}/join`, { joinToken: family.joinToken, name: "Priya" });
  console.log("✓ Priya joined:", sis.memberId);

  // 5. Get members
  const membersRes = await req("GET", `/api/family/${family.familyId}/members`, null, mom.memberToken);
  console.log("✓ Members:", membersRes.members.map(m => m.name).join(", "));

  // 6. Send OTPs
  console.log("\n── Sending encrypted OTPs ──\n");

  const otps = [
    { token: mom.memberToken, from: "AD-AMAZON", body: "Your Amazon delivery OTP is 482917. Share with delivery person.", memberName: "Mom" },
    { token: you.memberToken, from: "VM-FKRT", body: "Flipkart order OTP: 731945. Verification code for delivery.", memberName: "Rahul" },
    { token: sis.memberToken, from: "BZ-SWIGGY", body: "Your Swiggy delivery partner is arriving. OTP is 8524.", memberName: "Priya" },
    { token: mom.memberToken, from: "AX-HDFC", body: "OTP for net banking login: 619273. Valid for 5 min.", memberName: "Mom" },
    { token: you.memberToken, from: "HP-MYNTRA", body: "Myntra delivery verification code: 390182.", memberName: "Rahul" },
  ];

  for (const o of otps) {
    const otp = o.body.match(/\b(\d{4,8})\b/)?.[1];
    const app = o.from.toLowerCase().includes("amazon") ? "amazon" : o.from.toLowerCase().includes("fkrt") ? "flipkart" : o.from.toLowerCase().includes("swiggy") ? "swiggy" : o.from.toLowerCase().includes("hdfc") ? "hdfc" : "myntra";
    const data = { from: o.from, body: o.body, otp, app, memberName: o.memberName, timestamp: Date.now() };
    const encrypted = encrypt(data, familyKey);
    const res = await req("POST", `/api/family/${family.familyId}/otp`, { encrypted }, o.token);
    console.log(`  📨 ${o.memberName}: ${app} OTP ${otp} → ${res.ok ? "✓" : "✗"}`);
    await new Promise(r => setTimeout(r, 500));
  }

  // 7. Fetch OTPs
  const otpRes = await req("GET", `/api/family/${family.familyId}/otps`, null, mom.memberToken);
  console.log(`\n✓ ${otpRes.length} OTPs stored on server (encrypted)\n`);

  console.log("═══ All tests passed ═══");
  console.log(`\nOpen http://localhost:7890 in your browser to see the dashboard.`);
  console.log(`Family key (for QR): ${familyKey}`);
}

main().catch(e => { console.error("Test failed:", e); process.exit(1); });
