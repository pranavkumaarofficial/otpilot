# otpilot

Self-hosted OTP sharing for families. End-to-end encrypted. Runs on any local network or Tailscale.

Mom ordered something on Amazon. She's stuck in a meeting at work. The delivery guy shows up at your door and needs the OTP that went to her phone. She can't pick up.

This happens every day in Indian households. The existing SMS forwarding apps are closed-source, route your messages through someone else's cloud, and can read everything. otpilot runs on your own network and the server literally cannot read your OTPs because it never has the encryption key.

The whole thing is about 1000 lines of code with a single npm dependency (`ws`).

## How it works

```
Phone A (sender)  ---[encrypted OTP]--->  otpilot relay  ---[encrypted OTP]--->  Phone B (receiver)
Phone B (sender)  ---[encrypted OTP]--->  (your Pi/NAS)  ---[encrypted OTP]--->  Phone A (receiver)
                                          can't decrypt
```

Each family member's phone does two things:
1. Sends OTP messages from that phone to the relay, encrypted with the family key
2. Receives OTP messages from all other family members, decrypted in the browser

The relay server just passes encrypted blobs around. It never has the family key and cannot read any OTP.

## What you get

- AES-256-GCM end-to-end encryption. The server only sees ciphertext.
- Runs on a Raspberry Pi, laptop, NAS, or Docker container. Your OTPs stay on your network.
- QR code onboarding. Create a family, scan a QR to join. No accounts, no passwords, no cloud signup.
- Tabbed dashboard showing OTPs from every family member, filtered by person.
- OTPs auto-expire after 5 minutes (configurable to 1, 3, 5, or 10 min). Nothing is written to disk.
- Color-coded OTP cards by category: delivery (Amazon, Flipkart), food (Swiggy, Zomato), transport (Uber, Ola), banking (HDFC, ICICI).
- Admin panel for the family creator: stats, member list, remove devices, regenerate invite codes.
- Settings panel: OTP expiry, SMS filter keywords, blocked senders, browser notifications, sound alerts.
- Works over Tailscale, local WiFi, or any network where devices can reach each other.
- PWA that you can add to your home screen. Offline support for cached pages.
- One npm dependency. The server uses `ws` for WebSocket support. That's it.

## Tech stack

| Component | Technology | Details |
|-----------|-----------|---------|
| Relay server | Node.js, `ws` | ~280 lines, HTTP + WebSocket server, cannot decrypt OTPs |
| Web app | Vanilla JavaScript (PWA) | No framework. Uses Web Crypto API for AES-256-GCM, IndexedDB for local state, Service Worker for offline |
| SMS sender | Node.js on Termux | Polls `termux-sms-list`, encrypts with Node.js `crypto`, forwards over WebSocket |
| Android app | Kotlin | BroadcastReceiver for SMS, WorkManager for background sending, AES-256-GCM |
| Transport | WebSocket | Bidirectional, real-time. Works over Tailscale, WiFi, or LAN |
| Encryption | AES-256-GCM | 256-bit key generated in the browser, shared via QR code, never sent to the server |
| UI | Inter font, vanilla CSS | Design system loosely based on Airtable's visual language |

## Getting started

### 1. Start the relay

Run the relay on any machine that stays on. A Pi, a NAS, an old laptop, whatever.

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

The server starts on port 7890 and prints your LAN IP.

### 2. Create a family

Open `http://<your-lan-ip>:7890` in a browser. Enter a family name and your name. You get a QR code with the invite payload (relay address, family ID, join token, encryption key).

### 3. Add members

Each family member opens the same URL on their phone, taps "Join family", and scans the QR code. Takes about 30 seconds per person. No manual configuration.

### 4. Set up SMS forwarding

On each phone that should forward OTPs, install Termux and Termux:API from F-Droid:

```bash
pkg install termux-api nodejs
```

Then copy the sender command from the app (Settings > Sender setup) and run it in Termux. It polls for new SMS, filters by keywords (otp, code, pin, verification, etc.), encrypts matching messages, and sends them to the relay.

### 5. Done

OTPs show up on every family member's screen within a second. Tap a card to copy the code.

## Security

| What | How |
|------|-----|
| Encryption | AES-256-GCM. Every OTP is encrypted in the browser before it leaves the device. The server only sees ciphertext. |
| Key management | The family key is generated in the browser and shared via QR code. The server never has it. |
| Network | OTPs travel over your local network or Tailscale mesh. They don't touch the public internet. |
| Device pairing | Joining a family requires physically scanning a QR code. No passwords to guess. |
| Expiry | OTPs are deleted from memory after the configured TTL (default 5 minutes). |
| Filtering | Only SMS containing OTP-related keywords are forwarded. Personal messages stay on the phone. |
| Storage | Everything is in memory. Nothing is written to disk. Server restart clears all data. |

## Configuration

### OTP expiry

The admin can change OTP expiry from the Settings panel: 1 min, 3 min, 5 min, or 10 min. This syncs to all family members over WebSocket.

### SMS filter keywords

Each device configures its own filter keywords in Settings > Filter rules. Defaults:

```
otp, code, pin, verification, verify, delivery, parcel, order
```

Only SMS messages containing these keywords get forwarded. Everything else stays on the phone.

### Blocked senders

You can block specific sender IDs (like `AD-SPAM` or `DM-PROMO`) so their messages are never forwarded.

### Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `7890` | Server port |
| `RELAY_HOST` | auto-detected LAN IP | Override the IP used in QR codes |

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
- [x] Name changes that sync across devices
- [x] Tailscale IP detection
- [ ] Native Android app (replace Termux with a proper APK)
- [ ] Cloud relay mode with E2E encryption (Railway/Fly.io)
- [ ] iOS sender workaround (Shortcuts or similar)
- [ ] Telegram/Discord notification channels
- [ ] PIN or biometric lock on the PWA
- [ ] Sender-ID prefix filtering (VK-SBI, AD-AMAZON, etc.)
- [ ] Multi-family support per device

## Contributing

The codebase is small on purpose. PRs welcome, especially:

- Android app to replace the Termux workaround with a proper background SMS listener
- iOS sender using any workaround for SMS access
- OTP detection for non-English SMS
- Filter rule UI improvements

## License

MIT

---

Built because my mom was stuck in a meeting and I couldn't collect her Amazon package.
