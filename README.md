<p align="center">
  <h1 align="center">otpilot</h1>
  <p align="center">
    Share OTPs across family phones in real time. Self-hosted. End-to-end encrypted. The server can't read anything.
  </p>
</p>

<p align="center">
  <a href="#getting-started">Getting started</a> &middot;
  <a href="#how-it-works">How it works</a> &middot;
  <a href="#security">Security</a> &middot;
  <a href="#api">API</a>
</p>

<p align="center">
  <img alt="License" src="https://img.shields.io/github/license/pranavkumaarofficial/otpilot">
  <img alt="Node.js" src="https://img.shields.io/badge/node-%3E%3D18-brightgreen">
  <img alt="Docker" src="https://img.shields.io/badge/docker-ready-blue">
  <img alt="Android" src="https://img.shields.io/badge/android-app-3DDC84?logo=android&logoColor=white">
  <img alt="Dependencies" src="https://img.shields.io/badge/dependencies-1-orange">
  <img alt="Encryption" src="https://img.shields.io/badge/encryption-AES--256--GCM-blueviolet">
</p>

---

## The problem

Mom ordered something on Amazon. She's in a meeting at work. The delivery guy is at your door asking for the OTP that went to her phone. She can't pick up.

This happens every day in Indian households. The existing SMS forwarding apps are closed-source, route messages through someone else's cloud, and can read everything in plaintext.

otpilot runs on your own network. The relay server never has the encryption key, so it literally cannot read your OTPs. One npm dependency. About 300 lines of server code.

## How it works

```
Phone A (sender)  ---[encrypted OTP]--->  otpilot relay  ---[encrypted OTP]--->  Phone B (viewer)
Phone B (sender)  ---[encrypted OTP]--->  (your Pi/NAS)  ---[encrypted OTP]--->  Phone A (viewer)
                                          can't decrypt
```

Every family member's phone does two things:
1. **Sends** - intercepts OTP SMS on that phone, encrypts with the family key, uploads to the relay
2. **Receives** - gets encrypted OTPs from other family members via WebSocket, decrypts locally

The relay just passes ciphertext around. It never has the key.

## Features

- **End-to-end encrypted** - AES-256-GCM. The server only sees ciphertext. Key is generated in the browser and shared via QR code.
- **Self-hosted** - runs on a Raspberry Pi, NAS, old laptop, or Docker container. OTPs never leave your network.
- **Native Android app** - no Termux needed. Installs as a regular APK, reads SMS via notification listener, works on Samsung.
- **Real-time dashboard** - OTPs appear within a second. Tabbed by family member. Color-coded by category (delivery, food, transport, banking).
- **QR code onboarding** - create a family, scan to join. No accounts, no passwords.
- **Auto-expiry** - OTPs disappear after 5 minutes (configurable: 1, 3, 5, or 10 min). Nothing touches disk.
- **Smart filtering** - only forwards SMS containing OTP-related keywords. Personal messages stay on the phone.
- **Works over Tailscale** - or local WiFi, or any network where devices can reach each other.
- **PWA** - add to home screen, works offline.
- **Admin panel** - stats, member list, remove devices, regenerate invite codes.
- **One dependency** - the server uses `ws` for WebSocket. That's the entire dependency tree.

## Tech stack

| Component | Technology | Details |
|-----------|-----------|---------|
| Relay server | Node.js, `ws` | ~300 lines. HTTP + WebSocket. Cannot decrypt OTPs. |
| Web app | Vanilla JS (PWA) | No framework. Web Crypto API, IndexedDB, Service Worker. |
| Android app | Kotlin | NotificationListener + WorkManager + WebSocket dashboard. Samsung-compatible. |
| Termux sender | Node.js | Alternative sender for phones where the APK isn't installed. |
| Encryption | AES-256-GCM | 256-bit key generated client-side, shared via QR, never sent to server. |
| Transport | WebSocket | Bidirectional, real-time. Tailscale / WiFi / LAN. |

## Getting started

### 1. Start the relay

Run on any machine that stays on. A Pi, a NAS, an old laptop, whatever.

```bash
git clone https://github.com/pranavkumaarofficial/otpilot.git
cd otpilot
npm install
npm start
```

Or with Docker:

```bash
docker compose up -d
```

The server starts on port `7890` and prints your LAN IP.

### 2. Create a family

Open `http://<your-lan-ip>:7890` in a browser. Enter a family name and your name. You get a QR code containing the relay address, family ID, join token, and encryption key.

### 3. Add family members

Each person opens the same URL on their phone, taps "Join family", and scans the QR. About 30 seconds per person.

### 4. Set up SMS forwarding

**Android app (recommended)**

Install the otpilot APK. Open it, scan the QR code, grant notification access. Done. The app reads incoming SMS via Android's notification listener, encrypts them, and forwards to the relay in the background. Works on Samsung phones that block direct SMS broadcast access.

**Termux (alternative)**

If you prefer a terminal:

```bash
pkg install termux-api nodejs
```

Copy the sender command from the web app (Settings > Sender setup) and run it in Termux.

### 5. Done

OTPs show up on every family member's screen within a second. Tap a card to copy the code.

## Security

| Layer | Implementation |
|-------|---------------|
| Encryption | AES-256-GCM. OTPs are encrypted on the device before leaving. Server sees only ciphertext. |
| Key management | Family key generated in the browser, shared via QR code. Server never has it. |
| Network | Traffic stays on your local network or Tailscale mesh. Nothing hits the public internet. |
| Device pairing | Requires physically scanning a QR code. No passwords to guess or phish. |
| Expiry | OTPs deleted from memory after TTL (default 5 min). |
| Filtering | Only SMS with OTP-related keywords are forwarded. Personal messages stay on the phone. |
| Storage | Everything in memory. Nothing on disk. Server restart clears all data. |

## Configuration

### OTP expiry

The admin can set OTP expiry from Settings: 1 min, 3 min, 5 min, or 10 min. Syncs to all members over WebSocket.

### SMS filter keywords

Each device configures its own keywords in Settings > Filter rules. Defaults:

```
otp, code, pin, verification, verify, delivery, parcel, order
```

Only SMS containing these keywords get forwarded.

### Blocked senders

Block specific sender IDs (`AD-SPAM`, `DM-PROMO`, etc.) so their messages are never forwarded.

### Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `7890` | Server port |
| `RELAY_HOST` | auto-detected LAN IP | Override the relay IP in QR codes |

## Project structure

```
otpilot/
  server/index.js      # Relay server (Node.js + ws)
  public/index.html     # PWA frontend (vanilla JS)
  public/sw.js          # Service Worker for offline caching
  public/manifest.json  # PWA manifest
  sender/index.js       # Termux SMS sender script
  android-app/          # Native Android app (Kotlin)
  test.js               # End-to-end test harness
  Dockerfile            # Docker build
  docker-compose.yml    # Docker Compose config
```

## API

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/api/family` | none | Create a new family |
| POST | `/api/family/:id/join` | join token | Join a family |
| POST | `/api/family/:id/otp` | member token | Submit an encrypted OTP |
| GET | `/api/family/:id/otps` | member token | Get recent OTPs |
| GET | `/api/family/:id/members` | member token | List family members |
| GET | `/api/family/:id/stats` | member token | Get family stats |
| PATCH | `/api/family/:id/settings` | admin token | Update family settings (TTL) |
| PATCH | `/api/family/:id/name` | member token | Update display name |
| DELETE | `/api/family/:id/members/:mid` | admin token | Remove a member |
| POST | `/api/family/:id/regenerate-invite` | admin token | Rotate the join token |
| GET | `/health` | none | Health check |
| WebSocket | `/ws` | member token | Real-time OTP stream |

## Roadmap

- [x] End-to-end encryption (AES-256-GCM)
- [x] QR code onboarding
- [x] Multi-member tabbed dashboard
- [x] Admin panel with stats and member management
- [x] Settings panel (expiry, filters, notifications)
- [x] Color-coded OTP cards by category
- [x] Tailscale IP detection
- [x] Native Android app (replaces Termux)
- [ ] Cloud relay mode with E2E encryption (Railway/Fly.io)
- [ ] iOS sender (Shortcuts or similar)
- [ ] Telegram/Discord notification channels
- [ ] PIN or biometric lock on the PWA
- [ ] Sender-ID prefix filtering (VK-SBI, AD-AMAZON, etc.)
- [ ] Multi-family support per device

## FAQ

**Why not just use Google Messages web?**
Google Messages web requires your phone to be on and connected. If your phone dies or loses signal, everyone loses access. It also only lets one person view one phone. otpilot lets the whole family see OTPs from every phone simultaneously.

**Does the server store my OTPs?**
No. OTPs live in memory and auto-delete after the TTL expires. Nothing is written to disk. Restart the server and everything is gone.

**Can the server admin read my OTPs?**
No. The encryption key is generated in your browser and shared via QR code. The server never receives it. Even if someone dumps the server's memory, they get ciphertext.

**Does it work on Samsung?**
Yes. Samsung blocks the standard SMS broadcast for non-default messaging apps, so the Android app uses a NotificationListener instead. It reads the SMS content from the notification posted by Google Messages or Samsung Messages.

**What about iOS?**
iOS doesn't allow third-party apps to read SMS. There's no workaround yet. PRs welcome if you find one.

**Is this safe to use for bank OTPs?**
The encryption is the same standard (AES-256-GCM) used by Signal, WhatsApp, and most banking apps. OTPs auto-expire and never touch disk. That said, you're trusting everyone in your family group with access to your OTPs, so only add people you trust.

## Contributing

The codebase is small on purpose. PRs welcome, especially:

- iOS sender using any workaround for SMS access
- OTP detection for non-English SMS
- Filter rule UI improvements

## License

[MIT](LICENSE)

---

Built because my mom was stuck in a meeting and I couldn't get her Amazon package delivered.
