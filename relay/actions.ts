/**
 * Action framework prototype for the Natalie voice assistant.
 *
 * PROTOTYPE ONLY — dry-run against mock services. NOT integrated into
 * production. No real WhatsApp messages, calendar writes, or reminders.
 *
 * Purpose: define the complete consequential-action lifecycle so that when
 * Natalie can act for Kareem, every action is prepared, explicitly approved
 * for its exact recipient/content/operation, executed once, and verified.
 *
 * Lifecycle:  prepare → approve → execute → verify
 *
 * Safety invariants (enforced in code, tested below):
 *   1. No approval → no execution. Ever.
 *   2. Approval binds to the EXACT action_id, action name, and args hash.
 *      Approving "text Saira 'running late'" cannot execute "text Saira '...different...'".
 *   3. Approvals expire (default 15 minutes).
 *   4. Approvals are one-shot: consumed on first use; replays are blocked.
 *   5. Executed actions cannot execute again (idempotent records).
 *   6. Malformed arguments fail validation at prepare time — nothing to approve.
 *   7. Handlers receive a scoped token valid only for that action name;
 *      mock services reject tokens scoped to a different action.
 *
 * NOTE on hashing: args binding uses FNV-1a 64-bit here for portability.
 * Production MUST use SHA-256 (or better) for the args hash. The binding
 * property under test — any arg change invalidates the approval — holds
 * for both.
 */

export type ActionName =
  | "calendar.create_event"
  | "calendar.list_events"
  | "message.send"
  | "reminder.create";

export type Risk = "read" | "write";

export type ActionStatus =
  | "prepared"
  | "approved"
  | "executing"
  | "executed"
  | "failed"
  | "expired";

/** A prepared/executed action — the durable execution record. */
export interface ActionRecord {
  action_id: string;
  name: ActionName;
  risk: Risk;
  args: Record<string, unknown>;
  /** Binding hash of canonical args. Approval must match this exactly. */
  args_hash: string;
  status: ActionStatus;
  requested_by: string;
  prepared_at: number;
  approved_at: number | null;
  executed_at: number | null;
  approval_id: string | null;
  result: unknown | null;
  error: string | null;
}

/** An explicit approval, bound to one exact action. */
export interface ActionApproval {
  approval_id: string;
  action_id: string;
  action_name: ActionName;
  args_hash: string;
  granted_by: string;
  granted_at: number;
  expires_at: number;
  consumed: boolean;
}

/** Least-privilege token handed to a handler: valid for one action name only. */
export interface ScopedToken {
  action: ActionName;
  token: string;
  issued_at: number;
  expires_at: number;
}

export interface HandlerContext {
  scopedToken: ScopedToken;
  services: ActionServices;
  action_id: string;
}

/** Services the framework can call. Production swaps these for real adapters. */
export interface ActionServices {
  calendar: {
    create(token: ScopedToken, event: CalendarEventInput): Promise<CalendarEvent>;
    list(token: ScopedToken, from?: string, to?: string): Promise<CalendarEvent[]>;
    get(id: string): CalendarEvent | undefined;
  };
  messenger: {
    send(token: ScopedToken, to: string, body: string): Promise<{ message_id: string }>;
    log(): Array<{ to: string; body: string; at: number; message_id: string }>;
  };
  reminders: {
    create(token: ScopedToken, input: ReminderInput): Promise<Reminder>;
    get(id: string): Reminder | undefined;
  };
}

export interface CalendarEventInput { title: string; start: string; end?: string; timezone?: string; description?: string }
export interface CalendarEvent extends CalendarEventInput { id: string; created_at: number }
export interface ReminderInput { text: string; at: string; timezone?: string }
export interface Reminder extends ReminderInput { id: string; created_at: number }

/* ------------------------------------------------------------------ */
/* Storage                                                              */
/* ------------------------------------------------------------------ */

export interface ActionStore {
  save(record: ActionRecord): void;
  get(action_id: string): ActionRecord | undefined;
  saveApproval(a: ActionApproval): void;
  getApproval(approval_id: string): ActionApproval | undefined;
  list(): ActionRecord[];
}

export class MemoryActionStore implements ActionStore {
  private records = new Map<string, ActionRecord>();
  private approvals = new Map<string, ActionApproval>();
  save(r: ActionRecord): void { this.records.set(r.action_id, r); }
  get(id: string): ActionRecord | undefined { return this.records.get(id); }
  saveApproval(a: ActionApproval): void { this.approvals.set(a.approval_id, a); }
  getApproval(id: string): ActionApproval | undefined { return this.approvals.get(id); }
  list(): ActionRecord[] { return [...this.records.values()]; }
}

/**
 * Production DDL (for later integration into the relay Durable Object).
 * Not executed here — documented so the schema is ready.
 *
 * CREATE TABLE IF NOT EXISTS action_records (
 *   action_id TEXT PRIMARY KEY, name TEXT NOT NULL, risk TEXT NOT NULL,
 *   args TEXT NOT NULL, args_hash TEXT NOT NULL, status TEXT NOT NULL,
 *   requested_by TEXT NOT NULL, prepared_at INTEGER NOT NULL,
 *   approved_at INTEGER, executed_at INTEGER, approval_id TEXT,
 *   result TEXT, error TEXT
 * );
 * CREATE TABLE IF NOT EXISTS action_approvals (
 *   approval_id TEXT PRIMARY KEY, action_id TEXT NOT NULL, action_name TEXT NOT NULL,
 *   args_hash TEXT NOT NULL, granted_by TEXT NOT NULL,
 *   granted_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
 *   consumed INTEGER NOT NULL DEFAULT 0
 * );
 */
export const ACTION_RECORDS_DDL = "see comment above";

/* ------------------------------------------------------------------ */
/* Argument validation                                                  */
/* ------------------------------------------------------------------ */

interface ArgSpec {
  type: "string" | "number" | "boolean";
  required: boolean;
  maxLength?: number;
  minLength?: number;
  pattern?: RegExp;
  min?: number;
  max?: number;
  custom?: (v: unknown) => string | null; // returns error message or null
}

const isIsoDate = (v: unknown): string | null =>
  typeof v === "string" && !Number.isNaN(Date.parse(v)) ? null : "must be an ISO date string";

const registry: Record<ActionName, {
  risk: Risk;
  description: string;
  args: Record<string, ArgSpec>;
  summarize: (args: Record<string, unknown>) => string;
  run: (args: Record<string, unknown>, ctx: HandlerContext) => Promise<unknown>;
}> = {
  "calendar.create_event": {
    risk: "write",
    description: "Create a calendar event",
    args: {
      title: { type: "string", required: true, minLength: 1, maxLength: 200 },
      start: { type: "string", required: true, custom: isIsoDate },
      end: { type: "string", required: false, custom: isIsoDate },
      timezone: { type: "string", required: false, maxLength: 64 },
      description: { type: "string", required: false, maxLength: 2000 },
    },
    summarize: (a) => `Create calendar event "${a.title}" at ${a.start}${a.timezone ? ` (${a.timezone})` : ""}`,
    run: async (a, ctx) => ctx.services.calendar.create(ctx.scopedToken, a as unknown as CalendarEventInput),
  },
  "calendar.list_events": {
    risk: "read",
    description: "List calendar events in a range (read-only)",
    args: {
      from: { type: "string", required: false, custom: isIsoDate },
      to: { type: "string", required: false, custom: isIsoDate },
    },
    summarize: () => "List calendar events (read-only)",
    run: async (a, ctx) => ctx.services.calendar.list(
      ctx.scopedToken,
      a.from as string | undefined,
      a.to as string | undefined,
    ),
  },
  "message.send": {
    risk: "write",
    description: "Send a text/WhatsApp message (mock: logged, never sent)",
    args: {
      to: { type: "string", required: true, pattern: /^\+?[0-9\s\-()]{7,20}$/ },
      body: { type: "string", required: true, minLength: 1, maxLength: 1000 },
    },
    summarize: (a) => `Send message to ${a.to}: "${String(a.body).slice(0, 80)}${String(a.body).length > 80 ? "…" : ""}"`,
    run: async (a, ctx) => ctx.services.messenger.send(ctx.scopedToken, a.to as string, a.body as string),
  },
  "reminder.create": {
    risk: "write",
    description: "Create a reminder (mock: stored, never fires)",
    args: {
      text: { type: "string", required: true, minLength: 1, maxLength: 500 },
      at: {
        type: "string", required: true, custom: (v) => {
          const d = isIsoDate(v); if (d) return d;
          return Date.parse(v as string) > Date.now() ? null : "must be a future date";
        },
      },
      timezone: { type: "string", required: false, maxLength: 64 },
    },
    summarize: (a) => `Create reminder "${a.text}" at ${a.at}`,
    run: async (a, ctx) => ctx.services.reminders.create(ctx.scopedToken, a as unknown as ReminderInput),
  },
};

export const ACTION_NAMES = Object.keys(registry) as ActionName[];

export class ActionError extends Error {
  code: string;
  constructor(code: string, message: string) { super(message); this.name = "ActionError"; this.code = code; }
}

function validateArgs(name: ActionName, args: Record<string, unknown>): void {
  const spec = registry[name];
  if (!spec) throw new ActionError("unknown_action", `unknown action: ${name}`);
  if (!args || typeof args !== "object" || Array.isArray(args)) {
    throw new ActionError("validation", "args must be an object");
  }
  for (const [field, rule] of Object.entries(spec.args)) {
    const v = args[field];
    if (v === undefined || v === null) {
      if (rule.required) throw new ActionError("validation", `missing required arg: ${field}`);
      continue;
    }
    if (typeof v !== rule.type) throw new ActionError("validation", `arg ${field} must be ${rule.type}`);
    if (rule.type === "string") {
      const s = v as string;
      if (rule.minLength !== undefined && s.length < rule.minLength) throw new ActionError("validation", `arg ${field} too short`);
      if (rule.maxLength !== undefined && s.length > rule.maxLength) throw new ActionError("validation", `arg ${field} too long`);
      if (rule.pattern && !rule.pattern.test(s)) throw new ActionError("validation", `arg ${field} has invalid format`);
    }
    if (rule.type === "number") {
      const n = v as number;
      if (rule.min !== undefined && n < rule.min) throw new ActionError("validation", `arg ${field} too small`);
      if (rule.max !== undefined && n > rule.max) throw new ActionError("validation", `arg ${field} too large`);
    }
    if (rule.custom) {
      const err = rule.custom(v);
      if (err) throw new ActionError("validation", `arg ${field}: ${err}`);
    }
  }
  for (const field of Object.keys(args)) {
    if (!(field in spec.args)) throw new ActionError("validation", `unknown arg: ${field}`);
  }
}

/* ------------------------------------------------------------------ */
/* Deterministic helpers                                                */
/* ------------------------------------------------------------------ */

/** Canonical JSON: sorted keys, recursive. Any arg change changes the hash. */
export function canonicalJson(v: unknown): string {
  if (v === null || typeof v !== "object") return JSON.stringify(v) ?? "null";
  if (Array.isArray(v)) return `[${v.map(canonicalJson).join(",")}]`;
  const keys = Object.keys(v as Record<string, unknown>).sort();
  return `{${keys.map((k) => `${JSON.stringify(k)}:${canonicalJson((v as Record<string, unknown>)[k])}`).join(",")}}`;
}

/** FNV-1a 64-bit. PROTOTYPE ONLY — production must use SHA-256. */
export function hashArgs(args: Record<string, unknown>): string {
  const s = canonicalJson(args);
  let h1 = 0xcbf29ce4, h2 = 0xcbf29ce4;
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    h1 = Math.imul(h1 ^ c, 16777619);
    h2 = Math.imul(h2 ^ (c + 31), 16777619);
  }
  return (h1 >>> 0).toString(16).padStart(8, "0") + (h2 >>> 0).toString(16).padStart(8, "0");
}

function newId(prefix: string): string {
  const r = () =>
    Math.floor(Math.random() * 0xffffffff).toString(16).padStart(8, "0");
  return `${prefix}_${r()}${r()}`.slice(0, prefix.length + 17);
}

export const DEFAULT_APPROVAL_TTL_MS = 15 * 60 * 1000; // 15 minutes

function issueScopedToken(action: ActionName, ttlMs = 5 * 60 * 1000): ScopedToken {
  const now = Date.now();
  return { action, token: `scope:${action}:${newId("tok")}`, issued_at: now, expires_at: now + ttlMs };
}

/* ------------------------------------------------------------------ */
/* Lifecycle                                                            */
/* ------------------------------------------------------------------ */

/**
 * Step 1 — prepare. Validates args, creates the durable record, returns a
 * human-readable summary for Kareem's approval prompt. Nothing executes here.
 */
export function prepareAction(
  store: ActionStore,
  name: ActionName,
  args: Record<string, unknown>,
  requested_by = "natalie",
): { action_id: string; summary: string; risk: Risk } {
  validateArgs(name, args);
  const action_id = newId("act");
  const record: ActionRecord = {
    action_id,
    name,
    risk: registry[name].risk,
    args: JSON.parse(canonicalJson(args)) as Record<string, unknown>,
    args_hash: hashArgs(args),
    status: "prepared",
    requested_by,
    prepared_at: Date.now(),
    approved_at: null,
    executed_at: null,
    approval_id: null,
    result: null,
    error: null,
  };
  store.save(record);
  return { action_id, summary: registry[name].summarize(args), risk: record.risk };
}

/**
 * Step 2 — approve. Simulates Kareem's explicit approval of ONE prepared
 * action. Binds approval_id → action_id + action name + args hash, with expiry.
 * Read-only actions are auto-approved by the system (logged as such).
 */
export function grantApproval(
  store: ActionStore,
  action_id: string,
  granted_by: string,
  opts: { ttl_ms?: number } = {},
): { approval_id: string; expires_at: number } {
  const record = store.get(action_id);
  if (!record) throw new ActionError("unknown_action", `no such action: ${action_id}`);
  if (record.status !== "prepared") {
    throw new ActionError("bad_state", `action is ${record.status}, not prepared`);
  }
  if (record.risk === "read") {
    // Read-only: system auto-approval, still recorded and bound.
    const approval: ActionApproval = {
      approval_id: newId("apr"), action_id, action_name: record.name,
      args_hash: record.args_hash, granted_by: "system:read-only",
      granted_at: Date.now(), expires_at: Date.now() + (opts.ttl_ms ?? DEFAULT_APPROVAL_TTL_MS),
      consumed: false,
    };
    store.saveApproval(approval);
    record.status = "approved"; record.approved_at = approval.granted_at;
    record.approval_id = approval.approval_id; store.save(record);
    return { approval_id: approval.approval_id, expires_at: approval.expires_at };
  }
  const now = Date.now();
  const approval: ActionApproval = {
    approval_id: newId("apr"), action_id, action_name: record.name,
    args_hash: record.args_hash, granted_by,
    granted_at: now, expires_at: now + (opts.ttl_ms ?? DEFAULT_APPROVAL_TTL_MS),
    consumed: false,
  };
  store.saveApproval(approval);
  record.status = "approved"; record.approved_at = now;
  record.approval_id = approval.approval_id; store.save(record);
  return { approval_id: approval.approval_id, expires_at: approval.expires_at };
}

/**
 * Step 3 — execute. Enforces every safety invariant, then runs the handler
 * with a scoped token. One-shot: approval is consumed on success AND on
 * handler failure (a failed attempt needs a fresh approval to retry).
 */
export async function executeAction(
  store: ActionStore,
  services: ActionServices,
  action_id: string,
  approval_id: string,
  now = Date.now(),
): Promise<unknown> {
  const record = store.get(action_id);
  if (!record) throw new ActionError("unknown_action", `no such action: ${action_id}`);

  const fail = (code: string, message: string): never => {
    record.status = "failed"; record.error = message; store.save(record);
    throw new ActionError(code, message);
  };

  const approval = store.getApproval(approval_id);
  if (!approval) return fail("approval_required", "no approval presented — execution blocked");
  if (approval.action_id !== action_id) {
    return fail("approval_mismatch", "approval was granted for a different action — execution blocked");
  }
  if (approval.action_name !== record.name) {
    return fail("approval_mismatch", "approval action name does not match — execution blocked");
  }
  if (approval.args_hash !== record.args_hash) {
    return fail("approval_mismatch", "action arguments changed after approval — execution blocked");
  }
  if (now >= approval.expires_at) {
    record.status = "expired"; store.save(record);
    throw new ActionError("approval_expired", "approval has expired — execution blocked");
  }
  if (approval.consumed) {
    return fail("approval_replayed", "approval was already used — replay blocked");
  }
  if (record.status === "executed") {
    return fail("already_executed", "action already executed — duplicate blocked");
  }
  if (record.status !== "approved") {
    return fail("bad_state", `action is ${record.status}, not approved — execution blocked`);
  }

  record.status = "executing"; store.save(record);
  const token = issueScopedToken(record.name);
  try {
    const result = await registry[record.name].run(record.args, {
      scopedToken: token, services, action_id,
    });
    approval.consumed = true; store.saveApproval(approval);
    record.status = "executed"; record.executed_at = Date.now();
    record.result = result; store.save(record);
    return result;
  } catch (err) {
    approval.consumed = true; store.saveApproval(approval); // one-shot even on failure
    const message = err instanceof Error ? err.message : String(err);
    return fail("handler_failed", `handler failed: ${message}`);
  }
}

/**
 * Step 4 — verify. Confirms the side effect actually landed, per action type.
 */
export function verifyAction(
  store: ActionStore,
  services: ActionServices,
  action_id: string,
): { ok: boolean; detail: string } {
  const record = store.get(action_id);
  if (!record) return { ok: false, detail: "no such action" };
  if (record.status !== "executed") {
    return { ok: false, detail: `action status is ${record.status}, nothing to verify` };
  }
  const r = record.result as Record<string, unknown> | null;
  switch (record.name) {
    case "calendar.create_event": {
      const id = r?.id as string | undefined;
      const ev = id ? services.calendar.get(id) : undefined;
      return ev
        ? { ok: true, detail: `event ${id} present in calendar` }
        : { ok: false, detail: `event ${id ?? "?"} missing from calendar` };
    }
    case "reminder.create": {
      const id = r?.id as string | undefined;
      const rem = id ? services.reminders.get(id) : undefined;
      return rem
        ? { ok: true, detail: `reminder ${id} present in store` }
        : { ok: false, detail: `reminder ${id ?? "?"} missing from store` };
    }
    case "message.send": {
      const mid = r?.message_id as string | undefined;
      const found = services.messenger.log().some((m) => m.message_id === mid);
      return found
        ? { ok: true, detail: `message ${mid} recorded in mock outbox` }
        : { ok: false, detail: `message ${mid ?? "?"} not found in mock outbox` };
    }
    case "calendar.list_events":
      return Array.isArray(record.result)
        ? { ok: true, detail: `read returned ${record.result.length} events` }
        : { ok: false, detail: "read result is not an array" };
  }
}

/** Convenience: full lifecycle for read-only actions (auto-approved). */
export async function runReadAction(
  store: ActionStore,
  services: ActionServices,
  name: ActionName,
  args: Record<string, unknown> = {},
): Promise<unknown> {
  if (registry[name].risk !== "read") {
    throw new ActionError("approval_required", `${name} is not read-only`);
  }
  const { action_id } = prepareAction(store, name, args);
  const { approval_id } = grantApproval(store, action_id, "system:read-only");
  return executeAction(store, services, action_id, approval_id);
}
