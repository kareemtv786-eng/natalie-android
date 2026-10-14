# Natalie Android Voice Assistant

Custom on-device voice assistant for Kareem's Samsung Z Fold.

## Architecture

- **Wake word**: "Hi Natalie" via openWakeWord (ONNX, on-device)
- **STT**: Android SpeechRecognizer
- **Backend**: Cloudflare Workers relay → Muse
- **TTS**: Vanessa voice (`avocado_v2:Vanessa`)
- **Transport**: WebSocket push + HTTP fallback

## Milestones

- M1: Wake word detection
- M2.1: Transcription
- M2.2: Relay integration
- M2.3: Full voice loop
- M2.4: Hands-free mode
- M2.5: WebSocket, follow-up mode
- M2.6: Early wake hint

See `../natalie-android/` for build notes and handoff docs.
