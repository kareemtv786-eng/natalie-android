import { DurableObject } from "cloudflare:workers";

interface Env {
  CONVERSATIONS: DurableObjectNamespace;
  PHONE_TOKEN: string;
  NATALIE_TOKEN: string;
  // Feature flag for server-side conversation history (GET /v1/history).
  // Set to "false" to disable; any other value (or unset) means enabled.
  CONVERSATION_HISTORY_ENABLED?: string;
}

type Role = "phone" | "natalie";
type Message = {
  seq: number; id: string; body: string; created_at: number;
  status: string; lease_token: string | null; lease_until: number | null;
};
type Reply = { seq: number; message_id: string; body: string; created_at: number };

const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), {
  status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" }
});
const error = (status: number, message: string) => json({ error: message }, status);
const uuid = (s: unknown): s is string => typeof s === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(s);
const textOk = (s: unknown): s is string => typeof s === "string" && s.length > 0 && s.length <= 32768;
const tokenEq = (a: string, b: string): boolean => {
  const aBytes = new TextEncoder().encode(a);
  const bBytes = new TextEncoder().encode(b);
  let diff = aBytes.length ^ bBytes.length;
  for (let i = 0; i < Math.max(aBytes.length, bBytes.length); i++) diff |= (aBytes[i] ?? 0) ^ (bBytes[i] ?? 0);
  return diff === 0;
};
const readBody = async (r: Request, maxSize: number = 40000): Promise<Record<string, unknown>> => {
  if (Number(r.headers.get("content-length") ?? "0") > maxSize) throw new Error("body too large");
  const raw = await r.text();
  if (raw.length > maxSize) throw new Error("body too large");
  const parsed: unknown = JSON.parse(raw);
  if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") throw new Error("JSON object required");
  return parsed as Record<string, unknown>;
};

// Public Worker: authentication + per-conversation routing. Never trust caller-provided roles.
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/health" && request.method === "GET") return json({ ok: true });
    
    // WebSocket upgrade: allow token via query param (browsers can't send custom WS headers)
    let bearer = request.headers.get("authorization")?.match(/^Bearer (.+)$/i)?.[1] ?? "";
    let conversation = request.headers.get("x-conversation-id") ?? "";
    if (url.pathname === "/v1/ws" && !bearer) {
      bearer = url.searchParams.get("token") ?? "";
      conversation = url.searchParams.get("conversation_id") ?? "";
    }
    
    const role: Role | null = env.PHONE_TOKEN && tokenEq(bearer, env.PHONE_TOKEN) ? "phone"
      : env.NATALIE_TOKEN && tokenEq(bearer, env.NATALIE_TOKEN) ? "natalie" : null;
    if (!role) return error(401, "unauthorized");
    if (!/^[a-zA-Z0-9_-]{1,80}$/.test(conversation)) return error(400, "invalid x-conversation-id");

    const path = url.pathname;
    const phoneAllowed = request.method === "POST" && path === "/v1/messages"
      || request.method === "POST" && path === "/v1/wake_hint"
      || request.method === "GET" && path === "/v1/replies"
      || request.method === "GET" && path === "/v1/events"
      || request.method === "GET" && path === "/v1/ws"
      || request.method === "GET" && /^\/v1\/replies\/[^/]+\/audio$/.test(path)
      || request.method === "GET" && /^\/v1\/replies\/[^/]+\/audio\/segments$/.test(path)
      || request.method === "POST" && path === "/v1/devices/register";
    const natalieAllowed = request.method === "GET" && path === "/v1/messages"
      || request.method === "GET" && path === "/v1/messages/wait"
      || request.method === "GET" && path === "/v1/wake_hint"
      || request.method === "GET" && path === "/v1/history"
      || request.method === "POST" && /^\/v1\/messages\/[^/]+\/(claim|complete|renew)$/.test(path)
      || request.method === "PUT" && /^\/v1\/replies\/[^/]+$/.test(path)
      || request.method === "POST" && /^\/v1\/messages\/[^/]+\/event$/.test(path)
      || request.method === "POST" && /^\/v1\/messages\/[^/]+\/wake_requested$/.test(path)
      || request.method === "PUT" && /^\/v1\/replies\/[^/]+\/audio$/.test(path)
      || request.method === "PUT" && /^\/v1\/replies\/[^/]+\/audio\/segments\/[0-9]+$/.test(path);
    if ((role === "phone" && !phoneAllowed) || (role === "natalie" && !natalieAllowed)) return error(403, "forbidden");

    const object = env.CONVERSATIONS.get(env.CONVERSATIONS.idFromName(conversation));
    const headers = new Headers(request.headers);
    headers.delete("authorization");
    headers.set("x-authorized-role", role);

    // Long-poll: hold the connection open waiting for a new message (natalie only).
    if (request.method === "GET" && path === "/v1/messages/wait" && role === "natalie") {
      const timeoutSec = Math.min(90, Math.max(5, parseInt(url.searchParams.get("timeout") ?? "60", 10) || 60));
      const cursor = url.searchParams.get("cursor") ?? "0";
      const deadline = Date.now() + timeoutSec * 1000;
      while (Date.now() < deadline) {
        const pollReq = new Request(`https://do/v1/messages?cursor=${encodeURIComponent(cursor)}`, { headers });
        const pollRes = await object.fetch(pollReq);
        if (pollRes.ok) {
          const data = await pollRes.json() as any;
          if (data.messages && data.messages.length > 0) return json(data);
        }
        const remaining = deadline - Date.now();
        if (remaining <= 0) break;
        await new Promise(r => setTimeout(r, Math.min(2000, remaining)));
      }
      return json({ messages: [], cursor: parseInt(cursor, 10) || 0, timed_out: true });
    }

    return object.fetch(new Request(request, { headers }));
  }
};

export class Conversation extends DurableObject<Env> {
  private sql: SqlStorage;
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec(`
      CREATE TABLE IF NOT EXISTS messages (
        seq INTEGER PRIMARY KEY AUTOINCREMENT,
        id TEXT NOT NULL UNIQUE,
        body TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        status TEXT NOT NULL DEFAULT 'pending',
        lease_token TEXT,
        lease_until INTEGER
      );
      CREATE INDEX IF NOT EXISTS messages_queue ON messages(status, lease_until, seq);
      CREATE TABLE IF NOT EXISTS replies (
        seq INTEGER PRIMARY KEY AUTOINCREMENT,
        message_id TEXT NOT NULL UNIQUE REFERENCES messages(id),
        body TEXT NOT NULL,
        created_at INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS message_events (
        seq INTEGER PRIMARY KEY AUTOINCREMENT,
        message_id TEXT NOT NULL,
        status TEXT NOT NULL,
        attempt INTEGER NOT NULL DEFAULT 1,
        lease_token TEXT,
        occurred_at INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS message_events_cursor ON message_events(seq);
      CREATE INDEX IF NOT EXISTS message_events_msg ON message_events(message_id, seq);
      CREATE TABLE IF NOT EXISTS reply_audio (
        message_id TEXT PRIMARY KEY REFERENCES messages(id),
        audio_base64 TEXT NOT NULL,
        created_at INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS devices (
        device_id TEXT PRIMARY KEY,
        paired_at INTEGER NOT NULL,
        last_seen INTEGER NOT NULL,
        revoked INTEGER NOT NULL DEFAULT 0,
        label TEXT
      );
      CREATE TABLE IF NOT EXISTS wake_hints (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL
      );
      -- Durable wake-up coordinator: atomic lease ensuring at most one
      -- outstanding activation per conversation. Prevents duplicate
      -- wake_requested events when the hook fires multiple times for the
      -- same voice session (M2.6 reliability fix, Oct 14 2026).
      -- The DO is per-conversation, so a single 'default' row acts as the
      -- conversation-scoped lease.
      CREATE TABLE IF NOT EXISTS wake_leases (
        conversation_id TEXT PRIMARY KEY,
        attempt_id TEXT NOT NULL,
        device_id TEXT,
        session_id TEXT,
        status TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL
      );
      -- Sentence-level TTS audio segments (M2.7, Oct 14 2026).
      -- Each row is one synthesized sentence's MP3 for a reply, numbered
      -- 0..N in sentence order. Backward compatible: the legacy single-MP3
      -- path (reply_audio) is untouched; GET /v1/replies/{id}/audio prefers
      -- segments when present and falls back to reply_audio otherwise.
      CREATE TABLE IF NOT EXISTS reply_audio_segments (
        message_id TEXT NOT NULL,
        seq INTEGER NOT NULL,
        audio_base64 TEXT NOT NULL,
        sentence_text TEXT,
        is_final INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL,
        PRIMARY KEY (message_id, seq)
      );
      CREATE INDEX IF NOT EXISTS audio_segments_msg ON reply_audio_segments(message_id, seq);
    `);
  }

  private emitEvent(message_id: string, status: string, attempt: number = 1, lease_token: string | null = null) {
    this.sql.exec(
      "INSERT INTO message_events(message_id,status,attempt,lease_token,occurred_at) VALUES(?,?,?,?,?)",
      message_id, status, attempt, lease_token, Date.now()
    );
    // Broadcast to connected WebSockets
    this.broadcast({ type: "event", message_id, status, attempt, occurred_at: Date.now() });
  }

  private broadcast(data: unknown) {
    const msg = JSON.stringify(data);
    for (const ws of this.ctx.getWebSockets()) {
      try {
        ws.send(msg);
      } catch {
        // Ignore failed sends; closed sockets are cleaned up automatically
      }
    }
  }

  private verifyDevice(request: Request): boolean {
    const deviceId = request.headers.get("x-device-id");
    if (!deviceId || !uuid(deviceId)) return false;
    const row = this.sql.exec("SELECT revoked FROM devices WHERE device_id=?", deviceId).toArray()[0] as any;
    if (!row || row.revoked) return false;
    // Update last_seen
    this.sql.exec("UPDATE devices SET last_seen=? WHERE device_id=?", Date.now(), deviceId);
    return true;
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer) {
    // Handle incoming WebSocket messages (ping/pong for keepalive)
    try {
      const data = JSON.parse(typeof message === "string" ? message : new TextDecoder().decode(message));
      if (data.type === "ping") {
        ws.send(JSON.stringify({ type: "pong" }));
      }
    } catch {
      // Ignore malformed messages
    }
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string, wasClean: boolean) {
    // Hibernation API handles cleanup automatically
    ws.close(code, reason);
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;
    const role = request.headers.get("x-authorized-role");
    const now = Date.now();
    try {
      // WebSocket upgrade for real-time event push (phone only)
      // Auth already validated by outer worker via Authorization header
      if (path === "/v1/ws" && role === "phone") {
        const upgradeHeader = request.headers.get("Upgrade");
        if (upgradeHeader !== "websocket") {
          return error(400, "expected websocket upgrade");
        }
        const pair = new WebSocketPair();
        const [client, server] = Object.values(pair);
        this.ctx.acceptWebSocket(server);
        // Send current cursor so client can sync missed events
        const maxEvent = this.sql.exec<{ max_seq: number }>("SELECT COALESCE(MAX(seq),0) AS max_seq FROM message_events").toArray()[0].max_seq;
        const maxReply = this.sql.exec<{ max_seq: number }>("SELECT COALESCE(MAX(seq),0) AS max_seq FROM replies").toArray()[0].max_seq;
        server.send(JSON.stringify({ type: "hello", event_cursor: maxEvent, reply_cursor: maxReply }));
        return new Response(null, { status: 101, webSocket: client });
      }

      if (request.method === "POST" && path === "/v1/messages" && role === "phone") {
        const { id, body } = await readBody(request);
        if (!uuid(id) || !textOk(body)) return error(400, "id must be UUID; body must be 1-32768 chars");
        return this.ctx.storage.transactionSync(() => {
          // Device verification (grace period: allow if no device header yet, but log it)
          const deviceId = request.headers.get("x-device-id");
          if (deviceId) {
            if (!this.verifyDevice(request)) return error(403, "device not registered or revoked");
          }
          const old = this.sql.exec<Message>("SELECT * FROM messages WHERE id=?", id).toArray()[0];
          if (old) return old.body === body ? json({ id, seq: old.seq, duplicate: true }, 200) : error(409, "message ID conflict");
          this.sql.exec("INSERT INTO messages(id,body,created_at) VALUES(?,?,?)", id, body, now);
          const row = this.sql.exec<Message>("SELECT * FROM messages WHERE id=?", id).toArray()[0];
          this.emitEvent(id, "received");
          return json({ id, seq: row.seq, duplicate: false }, 201);
        });
      }

      if (request.method === "GET" && path === "/v1/messages" && role === "natalie") {
        // Cursor is an advisory high-watermark; this is a work queue, not a filtered event stream.
        // Old, uncompleted/expired messages MUST reappear even if their seq <= cursor.
        const cursor = parseCursor(url.searchParams.get("cursor"));
        if (cursor === null) return error(400, "invalid cursor");
        const rows = this.sql.exec<Message>(`
          SELECT * FROM messages
          WHERE status='pending' OR (status='leased' AND lease_until<=?)
          ORDER BY seq LIMIT 50`, now).toArray();
        const watermark = this.sql.exec<{ max_seq: number }>("SELECT COALESCE(MAX(seq),0) AS max_seq FROM messages").toArray()[0].max_seq;
        return json({ messages: rows.map(r => ({ id: r.id, seq: r.seq, body: r.body, created_at: r.created_at })), cursor: Math.max(cursor, watermark) });
      }

      const claim = /^\/v1\/messages\/([^/]+)\/claim$/.exec(path);
      if (request.method === "POST" && claim && role === "natalie") {
        const id = claim[1];
        if (!uuid(id)) return error(400, "invalid ID");
        const payload = await readBody(request);
        const leaseSeconds = payload.lease_seconds === undefined ? 180 : payload.lease_seconds;
        if (!Number.isInteger(leaseSeconds) || (leaseSeconds as number) < 30 || (leaseSeconds as number) > 900) return error(400, "lease_seconds must be 30-900");
        const token = crypto.randomUUID();
        return this.ctx.storage.transactionSync(() => {
          const row = this.sql.exec<Message>("SELECT * FROM messages WHERE id=?", id).toArray()[0];
          if (!row) return error(404, "not found");
          if (row.status === "replied" || row.status === "completed") return error(409, "already replied");
          if (row.status === "leased" && (row.lease_until ?? 0) > now) return error(409, "lease active");
          const until = now + (leaseSeconds as number) * 1000;
          this.sql.exec("UPDATE messages SET status='leased',lease_token=?,lease_until=? WHERE id=?", token, until, id);
          this.emitEvent(id, "claimed", 1, token);
          // Wake-up coordinator: the hint has served its purpose — the real
          // message arrived and was claimed. Mark the lease complete so a
          // subsequent "Hi Natalie" can acquire a fresh lease immediately
          // instead of waiting out the 60s TTL.
          this.sql.exec("UPDATE wake_leases SET status='complete' WHERE conversation_id='default' AND status!='complete'");
          return json({ id, lease_token: token, lease_until: until });
        });
      }

      const reply = /^\/v1\/replies\/([^/]+)$/.exec(path);
      if (request.method === "PUT" && reply && role === "natalie") {
        const id = reply[1];
        if (!uuid(id)) return error(400, "invalid ID");
        const { body, lease_token } = await readBody(request);
        if (!textOk(body) || !uuid(lease_token)) return error(400, "body and lease_token required");
        return this.ctx.storage.transactionSync(() => {
          const existing = this.sql.exec<Reply>("SELECT * FROM replies WHERE message_id=?", id).toArray()[0];
          if (existing) return existing.body === body ? json({ message_id: id, seq: existing.seq, duplicate: true }) : error(409, "reply already committed with different content");
          const msg = this.sql.exec<Message>("SELECT * FROM messages WHERE id=?", id).toArray()[0];
          if (!msg) return error(404, "message not found");
          if (msg.status !== "leased" || msg.lease_token !== lease_token || (msg.lease_until ?? 0) <= now) return error(409, "missing, stale or expired lease");
          this.sql.exec("INSERT INTO replies(message_id,body,created_at) VALUES(?,?,?)", id, body, now);
          this.sql.exec("UPDATE messages SET status='replied' WHERE id=?", id);
          this.emitEvent(id, "replied", 1, lease_token as string);
          const saved = this.sql.exec<Reply>("SELECT * FROM replies WHERE message_id=?", id).toArray()[0];
          // Broadcast the reply itself for instant delivery
          this.broadcast({ type: "reply", message_id: id, seq: saved.seq, body, created_at: now });
          return json({ message_id: id, seq: saved.seq, duplicate: false }, 201);
        });
      }

      const complete = /^\/v1\/messages\/([^/]+)\/complete$/.exec(path);
      if (request.method === "POST" && complete && role === "natalie") {
        const id = complete[1];
        if (!uuid(id)) return error(400, "invalid ID");
        const { lease_token } = await readBody(request);
        if (!uuid(lease_token)) return error(400, "lease_token required");
        return this.ctx.storage.transactionSync(() => {
          const msg = this.sql.exec<Message>("SELECT * FROM messages WHERE id=?", id).toArray()[0];
          if (!msg) return error(404, "not found");
          if (msg.lease_token !== lease_token) return error(409, "wrong lease");
          if (msg.status === "completed") return json({ id, completed: true, duplicate: true });
          if (msg.status !== "replied") return error(409, "reply must be committed first");
          this.sql.exec("UPDATE messages SET status='completed' WHERE id=?", id);
          return json({ id, completed: true, duplicate: false });
        });
      }

      const renew = /^\/v1\/messages\/([^/]+)\/renew$/.exec(path);
      if (request.method === "POST" && renew && role === "natalie") {
        const id = renew[1];
        if (!uuid(id)) return error(400, "invalid ID");
        const payload = await readBody(request);
        const { lease_token } = payload;
        if (!uuid(lease_token)) return error(400, "lease_token required");
        const leaseSeconds = payload.lease_seconds === undefined ? 180 : payload.lease_seconds;
        if (!Number.isInteger(leaseSeconds) || (leaseSeconds as number) < 30 || (leaseSeconds as number) > 900) return error(400, "lease_seconds must be 30-900");
        return this.ctx.storage.transactionSync(() => {
          const msg = this.sql.exec<Message>("SELECT * FROM messages WHERE id=?", id).toArray()[0];
          if (!msg) return error(404, "not found");
          if (msg.status !== "leased" || msg.lease_token !== lease_token) return error(409, "no active lease for this token");
          if ((msg.lease_until ?? 0) <= now) return error(409, "lease already expired");
          const until = now + (leaseSeconds as number) * 1000;
          this.sql.exec("UPDATE messages SET lease_until=? WHERE id=?", until, id);
          return json({ id, lease_token, lease_until: until, renewed: true });
        });
      }

      if (request.method === "GET" && path === "/v1/replies" && role === "phone") {
        const cursor = parseCursor(url.searchParams.get("cursor"));
        if (cursor === null) return error(400, "invalid cursor");
        const rows = this.sql.exec<Reply>("SELECT * FROM replies WHERE seq>? ORDER BY seq LIMIT 50", cursor).toArray();
        return json({ replies: rows, cursor: rows.length ? rows[rows.length - 1].seq : cursor, has_more: rows.length === 50 });
      }

      // Status events: phone reads, natalie writes custom statuses (e.g. "processing")
      if (request.method === "GET" && path === "/v1/events" && role === "phone") {
        const cursor = parseCursor(url.searchParams.get("cursor"));
        if (cursor === null) return error(400, "invalid cursor");
        const rows = this.sql.exec(
          "SELECT seq, message_id, status, attempt, occurred_at FROM message_events WHERE seq>? ORDER BY seq LIMIT 100",
          cursor
        ).toArray();
        return json({ events: rows, cursor: rows.length ? (rows[rows.length - 1] as any).seq : cursor });
      }

      const customEvent = /^\/v1\/messages\/([^/]+)\/event$/.exec(path);
      if (request.method === "POST" && customEvent && role === "natalie") {
        const id = customEvent[1];
        if (!uuid(id)) return error(400, "invalid ID");
        const { status, lease_token } = await readBody(request);
        if (typeof status !== "string" || !/^[a-z_]{1,32}$/.test(status)) return error(400, "invalid status");
        if (!uuid(lease_token)) return error(400, "lease_token required");
        return this.ctx.storage.transactionSync(() => {
          const msg = this.sql.exec<Message>("SELECT * FROM messages WHERE id=?", id).toArray()[0];
          if (!msg) return error(404, "not found");
          if (msg.status !== "leased" || msg.lease_token !== lease_token) return error(409, "no active lease for this token");
          if ((msg.lease_until ?? 0) <= now) return error(409, "lease expired");
          this.emitEvent(id, status, 1, lease_token as string);
          return json({ message_id: id, status, ok: true });
        });
      }

      // Hook signals that it has fired and a worker is starting (no lease required).
      // Idempotent per message: the hook polls every ~10s, so without this
      // guard a single slow message would accumulate duplicate
      // wake_requested events (observed 3x in M2.6 morning test, Oct 14).
      const wakeReq = /^\/v1\/messages\/([^/]+)\/wake_requested$/.exec(path);
      if (request.method === "POST" && wakeReq && role === "natalie") {
        const id = wakeReq[1];
        if (!uuid(id)) return error(400, "invalid ID");
        return this.ctx.storage.transactionSync(() => {
          const msg = this.sql.exec<Message>("SELECT * FROM messages WHERE id=?", id).toArray()[0];
          if (!msg) return error(404, "not found");
          const alreadyEmitted = this.sql.exec(
            "SELECT 1 FROM message_events WHERE message_id=? AND status='wake_requested' LIMIT 1",
            id
          ).toArray()[0];
          let emitted = false;
          if (msg.status === "pending" && !alreadyEmitted) {
            this.emitEvent(id, "wake_requested");
            emitted = true;
          }
          return json({ message_id: id, ok: true, emitted });
        });
      }

      // Early wake hint (phone only): "Hi Natalie" was detected, a voice
      // query is incoming. This is a HINT, not a command — never generate
      // a reply from it. Lets the ingress agent warm up during speech
      // capture + transcription (~5-15s overlap).
      //
      // Durable wake-up coordinator (M2.6 reliability fix): at most one
      // outstanding activation lease per conversation. Duplicate hints for
      // the same voice session (or hook re-fires) return the existing
      // attempt_id with is_duplicate=true and do NOT broadcast again.
      // Lease TTL is 60s; a successful message claim marks it complete.
      if (request.method === "POST" && path === "/v1/wake_hint" && role === "phone") {
        const hintBody = await readBody(request).catch(() => ({} as Record<string, unknown>));
        const hintDeviceId = typeof hintBody.device_id === "string" && hintBody.device_id.length <= 128 ? hintBody.device_id : null;
        const hintSessionId = typeof hintBody.session_id === "string" && hintBody.session_id.length <= 128 ? hintBody.session_id : null;
        return this.ctx.storage.transactionSync(() => {
          // Expire old hints and leases; keep the tables small
          this.sql.exec("DELETE FROM wake_hints WHERE expires_at<=?", now);
          this.sql.exec("DELETE FROM wake_leases WHERE expires_at<=?", now);

          // Atomic check: is there already an outstanding lease?
          const existing = this.sql.exec<{
            attempt_id: string; status: string; created_at: number; session_id: string | null;
          }>("SELECT attempt_id, status, created_at, session_id FROM wake_leases WHERE conversation_id='default' LIMIT 1").toArray()[0];

          if (existing && existing.status !== "complete") {
            // Outstanding lease: acknowledge the duplicate, do NOT broadcast.
            // A new session_id while a lease is active still counts as a
            // duplicate — the phone rate-limits hints to 1/30s, and allowing
            // a second concurrent activation is exactly the contention bug
            // we're fixing. The active lease expires in <=60s anyway.
            return json({
              ok: true,
              attempt_id: existing.attempt_id,
              is_duplicate: true,
              status: existing.status,
              hint_at: existing.created_at,
            });
          }

          // No outstanding lease: create a new activation attempt.
          const attempt_id = crypto.randomUUID();
          this.sql.exec(
            `INSERT OR REPLACE INTO wake_leases
             (conversation_id, attempt_id, device_id, session_id, status, created_at, expires_at)
             VALUES ('default', ?, ?, ?, 'pending', ?, ?)`,
            attempt_id, hintDeviceId, hintSessionId, now, now + 60000
          );
          // Keep the legacy wake_hints row for backward compat with older
          // hook versions that only read the hints table.
          this.sql.exec(
            "INSERT INTO wake_hints(created_at,expires_at) VALUES(?,?)",
            now, now + 60000
          );
          const row = this.sql.exec<{ id: number }>("SELECT last_insert_rowid() AS id").toArray()[0];
          // Broadcast ONLY for genuinely new activation attempts.
          this.broadcast({ type: "wake_hint", hint_id: row.id, attempt_id, created_at: now });
          return json({ ok: true, attempt_id, is_duplicate: false, status: "pending", hint_id: row.id, hint_at: now });
        });
      }

      // Check for a fresh wake hint (natalie only, for the ingress hook).
      // Returns the active lease's attempt_id so the hook can correlate
      // its activation with the hint and avoid duplicate wake-ups.
      if (request.method === "GET" && path === "/v1/wake_hint" && role === "natalie") {
        return this.ctx.storage.transactionSync(() => {
          this.sql.exec("DELETE FROM wake_leases WHERE expires_at<=?", now);
          this.sql.exec("DELETE FROM wake_hints WHERE expires_at<=?", now);
          const lease = this.sql.exec<{
            attempt_id: string; status: string; created_at: number;
          }>("SELECT attempt_id, status, created_at FROM wake_leases WHERE conversation_id='default' LIMIT 1").toArray()[0];
          const row = this.sql.exec<{ id: number; created_at: number }>(
            "SELECT id, created_at FROM wake_hints ORDER BY id DESC LIMIT 1"
          ).toArray()[0];
          if (!lease && !row) return json({ fresh: false, hint_id: null, hint_at: null, attempt_id: null, status: null });
          return json({
            fresh: true,
            hint_id: row ? row.id : null,
            hint_at: row ? row.created_at : (lease ? lease.created_at : null),
            attempt_id: lease ? lease.attempt_id : null,
            status: lease ? lease.status : null,
          });
        });
      }

      // Conversation history (natalie only, for the ingress agent).
      // Returns the last N completed turns in chronological order so replies
      // can reference earlier context (e.g. "what about tomorrow?" after a
      // weather question). Only turns where both the user message and
      // Natalie's reply exist (status='completed') are included.
      // Feature flag: CONVERSATION_HISTORY_ENABLED="false" disables it.
      if (request.method === "GET" && path === "/v1/history" && role === "natalie") {
        if (this.env.CONVERSATION_HISTORY_ENABLED === "false") {
          return json({ enabled: false, turns: [], turn_count: 0 });
        }
        const limitParam = parseInt(url.searchParams.get("limit") ?? "10", 10);
        const limit = Math.min(20, Math.max(1, Number.isSafeInteger(limitParam) ? limitParam : 10));
        return this.ctx.storage.transactionSync(() => {
          const rows = this.sql.exec<{
            msg_seq: number; user_body: string; reply_body: string;
          }>(`
            SELECT m.seq AS msg_seq, m.body AS user_body, r.body AS reply_body
            FROM messages m
            JOIN replies r ON r.message_id = m.id
            WHERE m.status = 'completed'
            ORDER BY m.seq DESC
            LIMIT ?`, limit).toArray();
          // Token budget: ~2000 tokens ≈ 8000 chars. rows[] is newest-first;
          // collect newest-first so the budget trim drops the OLDEST turns,
          // then emit in chronological order (oldest turn first).
          const MAX_CHARS = 8000;
          const pairs: Array<{ user_body: string; reply_body: string }> = [];
          let totalChars = 0;
          for (const row of rows) {
            const pairChars = row.user_body.length + row.reply_body.length;
            if (totalChars + pairChars > MAX_CHARS && pairs.length > 0) break;
            pairs.push({ user_body: row.user_body, reply_body: row.reply_body });
            totalChars += pairChars;
          }
          const turns: Array<{ role: string; body: string }> = [];
          for (let i = pairs.length - 1; i >= 0; i--) {
            turns.push({ role: "user", body: pairs[i].user_body });
            turns.push({ role: "assistant", body: pairs[i].reply_body });
          }
          return json({ enabled: true, turns, turn_count: rows.length, chars: totalChars });
        });
      }

      // Upload TTS audio for a reply (natalie only, requires lease)
      const audioPut = /^\/v1\/replies\/([^/]+)\/audio$/.exec(path);
      if (request.method === "PUT" && audioPut && role === "natalie") {
        const id = audioPut[1];
        if (!uuid(id)) return error(400, "invalid ID");
        const { audio_base64, lease_token } = await readBody(request, 2500000); // 2.5MB for base64 audio
        if (typeof audio_base64 !== "string" || audio_base64.length === 0 || audio_base64.length > 2000000) return error(400, "audio_base64 required (max 2MB)");
        if (!uuid(lease_token)) return error(400, "lease_token required");
        return this.ctx.storage.transactionSync(() => {
          const msg = this.sql.exec<Message>("SELECT * FROM messages WHERE id=?", id).toArray()[0];
          if (!msg) return error(404, "not found");
          if (msg.lease_token !== lease_token) return error(409, "wrong lease");
          this.sql.exec("INSERT OR REPLACE INTO reply_audio(message_id,audio_base64,created_at) VALUES(?,?,?)", id, audio_base64, now);
          // Broadcast audio availability
          this.broadcast({ type: "audio", message_id: id });
          return json({ message_id: id, ok: true });
        });
      }

      // Upload one TTS audio segment for a reply (natalie only, requires lease).
      // Sentence-by-sentence TTS (M2.7): each sentence's MP3 is uploaded as
      // seq 0..N in sentence order. is_final=true on the last segment.
      // Event name per P3 spec: "audio_segment_ready".
      const segPut = /^\/v1\/replies\/([^/]+)\/audio\/segments\/([0-9]+)$/.exec(path);
      if (request.method === "PUT" && segPut && role === "natalie") {
        const id = segPut[1];
        const seq = parseInt(segPut[2], 10);
        if (!uuid(id)) return error(400, "invalid ID");
        if (!Number.isSafeInteger(seq) || seq < 0 || seq > 100) return error(400, "invalid seq");
        const { audio_base64, is_final, sentence_text, lease_token } = await readBody(request, 2500000); // 2.5MB for base64 audio
        if (typeof audio_base64 !== "string" || audio_base64.length === 0 || audio_base64.length > 2000000) return error(400, "audio_base64 required (max 2MB)");
        if (!uuid(lease_token)) return error(400, "lease_token required");
        return this.ctx.storage.transactionSync(() => {
          const msg = this.sql.exec<Message>("SELECT * FROM messages WHERE id=?", id).toArray()[0];
          if (!msg) return error(404, "not found");
          if (msg.lease_token !== lease_token) return error(409, "wrong lease");
          // Allowlist: lease must be active OR message already replied (later
          // sentences may take longer than the original lease). The lease_token
          // match above is the auth; we don't check lease_until strictly.
          this.sql.exec(
            `INSERT OR REPLACE INTO reply_audio_segments
             (message_id, seq, audio_base64, sentence_text, is_final, created_at)
             VALUES (?, ?, ?, ?, ?, ?)`,
            id, seq, audio_base64,
            typeof sentence_text === "string" ? sentence_text.substring(0, 500) : null,
            is_final === true ? 1 : 0,
            now
          );
          const count = this.sql.exec<{ n: number }>(
            "SELECT COUNT(*) AS n FROM reply_audio_segments WHERE message_id=?", id
          ).toArray()[0].n;
          // Broadcast per-segment event for instant phone notification.
          // At-least-once delivery assumed; the phone dedupes by sequence.
          this.broadcast({
            type: "audio_segment_ready",
            message_id: id,
            sequence: seq,
            is_final: is_final === true,
            segment_count: count,
          });
          return json({ message_id: id, seq, is_final: is_final === true, segments_so_far: count }, 201);
        });
      }

      // Download TTS audio segments for a reply (phone only), for gap-fill
      // after reconnect or out-of-order WS delivery.
      const segGet = /^\/v1\/replies\/([^/]+)\/audio\/segments$/.exec(path);
      if (request.method === "GET" && segGet && role === "phone") {
        const id = segGet[1];
        if (!uuid(id)) return error(400, "invalid ID");
        const sinceSeq = Math.max(0, parseInt(url.searchParams.get("since_seq") ?? "0", 10) || 0);
        const rows = this.sql.exec<{
          seq: number; audio_base64: string; is_final: number;
        }>(
          "SELECT seq, audio_base64, is_final FROM reply_audio_segments WHERE message_id=? AND seq>=? ORDER BY seq LIMIT 20",
          id, sinceSeq
        ).toArray();
        if (rows.length === 0) {
          // Hint for backward compat: single-audio exists but no segments
          const single = this.sql.exec("SELECT 1 FROM reply_audio WHERE message_id=?", id).toArray()[0];
          if (single) {
            return json({ message_id: id, segmented: false, has_single_audio: true, segments: [], is_final: false });
          }
          return error(404, "no audio segments for this message");
        }
        const isFinal = rows.some(r => r.is_final === 1);
        return json({
          message_id: id,
          segmented: true,
          segments: rows.map(r => ({ seq: r.seq, audio_base64: r.audio_base64 })),
          is_final: isFinal,
        });
      }

      // Download TTS audio for a reply (phone only).
      // Unified endpoint: prefers sentence segments when present, otherwise
      // falls back to the legacy single-MP3 path. Old phone clients that
      // don't know about segments only ever see the legacy shape, because
      // segmented replies are only produced after the new phone build ships.
      const audioGet = /^\/v1\/replies\/([^/]+)\/audio$/.exec(path);
      if (request.method === "GET" && audioGet && role === "phone") {
        const id = audioGet[1];
        if (!uuid(id)) return error(400, "invalid ID");
        const segRows = this.sql.exec<{
          seq: number; audio_base64: string; is_final: number;
        }>(
          "SELECT seq, audio_base64, is_final FROM reply_audio_segments WHERE message_id=? ORDER BY seq LIMIT 20",
          id
        ).toArray();
        if (segRows.length > 0) {
          const isFinal = segRows.some(r => r.is_final === 1);
          return json({
            message_id: id,
            segmented: true,
            segments: segRows.map(r => ({ seq: r.seq, audio_base64: r.audio_base64 })),
            is_final: isFinal,
          });
        }
        // Fallback: single audio (existing behavior, plus segmented flag)
        const row = this.sql.exec<{ audio_base64: string }>("SELECT audio_base64 FROM reply_audio WHERE message_id=?", id).toArray()[0];
        if (!row) return error(404, "no audio for this message");
        return json({ message_id: id, segmented: false, audio_base64: row.audio_base64 });
      }

      // Device registration (phone only, requires phone token which is already verified)
      if (request.method === "POST" && path === "/v1/devices/register" && role === "phone") {
        const { device_id, label } = await readBody(request);
        if (!uuid(device_id)) return error(400, "device_id must be UUID");
        return this.ctx.storage.transactionSync(() => {
          const existing = this.sql.exec("SELECT * FROM devices WHERE device_id=?", device_id).toArray()[0];
          if (existing) {
            this.sql.exec("UPDATE devices SET last_seen=?, revoked=0 WHERE device_id=?", now, device_id);
            return json({ device_id, registered: true, duplicate: true });
          }
          this.sql.exec("INSERT INTO devices(device_id,paired_at,last_seen,label) VALUES(?,?,?,?)", device_id, now, now, typeof label === "string" ? label.substring(0, 100) : null);
          return json({ device_id, registered: true, duplicate: false }, 201);
        });
      }
      return error(404, "not found");
    } catch (e) {
      if (e instanceof SyntaxError) return error(400, "invalid JSON");
      if (e instanceof Error && /body too large|JSON object required/.test(e.message)) return error(400, e.message);
      console.error("relay error", e);
      return error(500, "internal error");
    }
  }
}
function parseCursor(s: string | null): number | null {
  if (s === null || s === "") return 0;
  if (!/^(0|[1-9][0-9]*)$/.test(s)) return null;
  const n = Number(s);
  return Number.isSafeInteger(n) ? n : null;
}
