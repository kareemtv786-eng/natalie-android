# Natalie Android Voice Assistant

Custom on-device voice assistant for Kareem's Samsung phone. Say **"Hi Natalie"** and talk.

**Priority goal:** a smooth voice conversation — wake word, speak naturally, hear her reply,
interrupt her mid-sentence, and keep talking without pressing buttons.

---

## Architecture

```
Phone (this repo)                Cloudflare (relay/ dir)              VM
┌─────────────────────┐          ┌──────────────────────┐          ┌──────────────┐
│ "Hi Natalie"        │          │ natalie-relay worker │          │ Muse         │
│  → openWakeWord     │  HTTPS / │  + Durable Objects   │  hook /  │ (agent)      │
│    (ONNX, on-device)│  WSS     │  (message queue,     │  cron    │              │
│ SpeechRecognizer    │─────────▶│   auth, WS push)     │─────────▶│ Vanessa TTS  │
│  (STT)              │          │                      │  ◀───────│ (voice)      │
│ MediaPlayer         │◀─────────│  audio + text replies│          └──────────────┘
│  (Vanessa voice)    │          └──────────────────────┘
└─────────────────────┘
```

- **Wake word:** "Hi Natalie" via openWakeWord (ONNX Runtime, fully on-device, no network/key/fees)
- **Speech-to-text:** Android `SpeechRecognizer`
- **Transport:** HTTPS + WebSocket to the Cloudflare relay (`relay/` in this repo)
- **Brain:** Muse agent on a VM, claimed via relay message queue
- **Voice:** Vanessa (`avocado_v2:Vanessa`) — her voice is her identity, never substitute
- **Zero-cost rule:** no paid APIs, GPU rentals, subscriptions, or infrastructure

---

## What works (tested on Kareem's phone)

- ✅ Custom on-device **"Hi Natalie"** wake word (openWakeWord)
- ✅ Speech transcription via Android SpeechRecognizer
- ✅ Cloudflare relay message send/receive (HTTPS + WebSocket push)
- ✅ **Full voice loop:** wake → speak → transcription → Muse reply → Vanessa voice playback
- ✅ Foreground listening service with WakeLock (works with screen off)
- ✅ M2.6 **wake hint**: phone pings the relay the instant the wake word fires, so the
  backend starts warming up while Kareem is still speaking (best case ~5s claim vs ~22s cold)
- ✅ Missed-reply recovery (if the phone was asleep when a reply landed)
- ✅ Copy-buttons on replies
- ✅ Reminder scheduling through the relay (e.g. "remind me at 2 o'clock I have a zoom")
- ✅ 12-second follow-up listening window after a reply (no wake word needed) — *partially, see below*

## Implemented but NOT yet reliable / untested on phone

- ⚠️ **Follow-up window is flaky:** the 12s follow-up listen often ends after ~2s with
  SpeechRecognizer error 7 ("no speech recognized"). Needs retry-until-deadline logic.
- ⚠️ **Wake-hint latency is inconsistent:** measured 4.7s / 24.1s / 37.7s claim delays in
  live tests. Real improvement, not yet dependable. Duplicate `wake_requested` events seen.
- ⚠️ **Conversation history:** implemented in relay (`GET /v1/history`) but the live relay
  returns `{"error":"forbidden"}` — phone conversations are currently **stateless**.
  Follow-ups like "what about tomorrow?" don't work yet.
- ⚠️ **Interruption / barge-in:** NOT implemented. Saying "Natalie, stop" while she talks
  does nothing. This is the top priority (see Roadmap).
- ❌ Locked-screen / full screen-off wake → reply → playback: not explicitly verified
- ❌ Bluetooth earbuds, noisy rooms, extended battery behavior: not tested
- ❌ **Wake-lease coordinator** (dedupe concurrent activations): built, 37/37 tests pass,
  **not deployed** — awaiting hardened revision per review
- ❌ **Sentence-level Vanessa audio** (speak before full reply is ready): backend built,
  20/20 protocol tests pass, **not deployed** — Android player not built yet
- ❌ **Smart Turn** endpointing (detect finished thought vs thinking pause): evaluated,
  not integrated
- ❌ Fast-lane deterministic replies (time/date/arithmetic): prototype only, disabled
- ❌ Action framework (calls, reminders, app control): framework built and mock-tested,
  no live adapters

**Do not claim** Spotify/YouTube playback, calls, or general phone commands work. They don't yet.

---

## Roadmap (agreed priority order)

| Phase | Change | Benefit |
|-------|--------|---------|
| V1 | `RealtimeConversationController` (state machine + generation counter) | Reliable interruption & cancellation — a late Muse reply can never start talking over Kareem |
| V2 | Keep mic open + Android AEC during Vanessa playback | Detect Kareem while Natalie is speaking |
| V3 | Smart Turn v3.2 endpointing (ONNX, 8MB, CPU) | Natural pauses, faster turn detection |
| V4 | Sentence-level Vanessa audio queue | Speak before the entire reply finishes |
| V5 | Persistent Muse invocation | Remove repeated ~20s agent wake-up delays |

Standing guidance: run major technical decisions by ChatGPT first;
study mature implementations (LiveKit, Pipecat, Smart Turn) instead of
rediscovering voice-assistant patterns through trial and error.

---

## Build & install

### Prerequisites
- Android Studio (or command-line Gradle 8.x + Android SDK 34)
- JDK 17

### Build the APK
```bash
# From the repo root:
./gradlew :app:assembleDebug
# APK lands at: app/build/outputs/apk/debug/app-debug.apk
```

### Install on the phone
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Or copy the APK to the phone and tap it (allow "install unknown apps").

### First run
1. Open the Natalie app, grant microphone + notification permissions
2. The app starts a foreground listening service ("Hi Natalie" always-on)
3. Say **"Hi Natalie"**, wait for the chime, then speak
4. Her reply arrives as text, then Vanessa's voice plays it back

---

## Backend configuration (no secrets in this repo)

The app talks to the Cloudflare relay in `relay/`. Two tokens exist:

| Token | Role | Lives in |
|-------|------|----------|
| `NATALIE_TOKEN` | Server: claim messages, write replies, upload audio | VM only, Cloudflare secret |
| `PHONE_TOKEN` | Phone: send messages, read own replies | Android app at runtime |

**Setup (no hardcoded secrets):**
1. Deploy the worker: `cd relay && npx wrangler deploy`
2. Set secrets (never in git):
   ```bash
   npx wrangler secret put PHONE_TOKEN
   npx wrangler secret put NATALIE_TOKEN
   ```
3. In the Android app, copy `RelaySecrets.kt.example` → `RelaySecrets.kt`
   and paste the `PHONE_TOKEN` value. `RelaySecrets.kt` is git-ignored.
   (Production: replace with a setup screen storing the token in
   EncryptedSharedPreferences.)

The relay URL is `https://natalie-relay.kareemtv786.workers.dev` (see `RelayClient.kt`).

---

## Repo layout

```
app/                          Android app (Kotlin)
  src/main/java/com/natalie/assistant/
    MainActivity.kt           UI + conversation wiring
    ListeningForegroundService.kt   Always-on wake-word service
    RelayClient.kt / RelayWebSocket.kt  Relay transport
    VoiceState.kt             Conversation state
    Config.kt                 Tunables (thresholds, timeouts)
    RelaySecrets.kt.example   Template — copy to RelaySecrets.kt (git-ignored)
  src/main/java/com/openwakeword/   Wake-word engine wrapper
  src/main/assets/openwakeword/     ONNX models (incl. custom hi_natalie.onnx)
relay/                        Cloudflare Worker backend (TypeScript)
  index.ts                    Relay: auth, message queue, WS push, audio upload
  actions.ts / fast-lane.ts   (prototypes — not wired into production path)
  wrangler.toml               Worker config (no secrets)
```

---

## For reviewers (ChatGPT)

Start with `app/src/main/java/com/natalie/assistant/MainActivity.kt`
(microphone, playback, conversation-state code) and `relay/index.ts`
(message lifecycle, auth, audio upload). The immediate review target is the
V1 `RealtimeConversationController` design against the existing
mic/playback/state code, per the phased roadmap above.
