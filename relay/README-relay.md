# Natalie relay — Phase 1

Single Cloudflare Worker, one SQLite-backed Durable Object per conversation.

## Setup

```bash
npm install
npx wrangler secret put PHONE_TOKEN
npx wrangler secret put NATALIE_TOKEN
npm run deploy
```

Generate independent, high-entropy tokens. Protect the Worker URL and secrets; deploy over HTTPS only. Never use a shared token for both actors. This prototype assumes **one trusted phone and one trusted Natalie scheduler**; the two shared tokens grant access to **all** conversation IDs. Provision per-conversation authorization before multi-user deployment. Configure production log redaction and rate limiting.

All API calls include `Authorization: Bearer <role-token>` and `X-Conversation-Id: demo`.

## Endpoints

* `POST /v1/messages` (phone): `{ "id": "<client-generated UUID>", "body": "Hi" }`. Reuse `id` verbatim on retries; 201 new, 200 identical replay, 409 conflicting payload.
* `GET /v1/messages?cursor=0` (Natalie): unclaimed or expired-lease messages, max 50. **Queue cursor is advisory**, not a strict `seq > cursor` filter; this is intentional so old uncompleted messages reappear after a crash.
* `POST /v1/messages/{id}/claim` (Natalie): `{ "lease_seconds": 180 }`, returns `lease_token` and expiry; 409 if actively leased or already replied. Claim before processing.
* `PUT /v1/replies/{message_id}` (Natalie): `{ "body": "Hello", "lease_token": "<from claim>" }`; atomic once-only insert. Identical retry returns 200 even after expiry; conflicting retry 409. Replies become phone-visible immediately after commit.
* `POST /v1/messages/{id}/complete` (Natalie): `{ "lease_token": "<from claim>" }`; acknowledges committed reply. Idempotent with matching token.
* `GET /v1/replies?cursor=0` (phone): strictly increasing reply sequence; save returned cursor **only after** displaying/persisting the entire page. At most 50 per page; use `has_more` to drain backlog.

The message body is an opaque UTF-8 string, max 32,768 JS characters. The relay does not execute prompts.

## Important recovery behavior

Reply insertion atomically changes the message state to `replied`, preventing redelivery as work. `complete` follows separately and is retryable. If a crash happens *after processing but before reply insertion*, the message is retried and processing may happen again: make external tool side effects independently idempotent. Claims expire after 30–900 seconds; scheduled jobs should choose leases longer than their processing deadline. If jobs may exceed 900 seconds, add lease-renewal support before deployment.

The `replies` endpoint is an append-only sync feed. No server-side read acknowledgements, retention limits, message encryption, or per-conversation credentials are included in this Phase 1 prototype. For sensitive communications, add client-to-client encryption before putting private content into the relay. Keep the phone's cursor on durable local storage.
