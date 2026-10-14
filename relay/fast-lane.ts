/**
 * Fast-lane router prototype for the Natalie voice assistant.
 *
 * PROTOTYPE ONLY — disabled by default, not integrated into production.
 * Purpose: benchmark whether deterministic handling of simple questions
 * can replace full Muse inference (~20s) for straightforward requests.
 *
 * Critical rule: when in doubt, route to "full". A wrong fast answer is
 * worse than a slow right answer.
 */

export type Lane = "fast" | "full";

export interface RouteResult {
  lane: Lane;
  /** Which fast-lane handler matched (fast lane only). */
  handler?: string;
  /** 0-1 confidence in the routing decision. */
  confidence: number;
  /** Human-readable reason for the routing decision (for logs). */
  reason: string;
}

/** Context passed to the router (conversation history, etc.). */
export interface RouterContext {
  /** Recent conversation turns, oldest first. */
  history?: Array<{ role: "user" | "assistant"; text: string }>;
  /** IANA timezone for time/date answers. */
  timezone?: string;
}

/** Feature flag — prototype is DISABLED by default. */
export const FAST_LANE_ENABLED = false;

/* ------------------------------------------------------------------ */
/* Safe arithmetic expression evaluator (no eval()).                   */
/* ------------------------------------------------------------------ */

/**
 * Tokenize a simple arithmetic expression.
 * Supports: + - * / % ^ ( ) and decimal numbers.
 * Does NOT support: function calls, variables, or any JS syntax.
 */
function tokenize(expr: string): Array<{ type: string; value: string }> {
  const tokens: Array<{ type: string; value: string }> = [];
  let i = 0;
  while (i < expr.length) {
    const ch = expr[i];
    if (/\s/.test(ch)) { i++; continue; }
    if (/[0-9.]/.test(ch)) {
      let num = "";
      let dots = 0;
      while (i < expr.length && /[0-9.]/.test(expr[i])) {
        if (expr[i] === ".") { dots++; if (dots > 1) throw new Error("bad number"); }
        num += expr[i]; i++;
      }
      if (num === "." || num === "") throw new Error("bad number");
      tokens.push({ type: "num", value: num });
      continue;
    }
    if ("+-*/%^()".includes(ch)) {
      tokens.push({ type: "op", value: ch });
      i++;
      continue;
    }
    throw new Error(`unexpected char: ${ch}`);
  }
  return tokens;
}

/**
 * Recursive-descent parser for safe arithmetic.
 * Grammar: expr := term (('+'|'-') term)* ; term := factor (('*'|'/'|'%') factor)*
 *          factor := ('+'|'-') factor | power ; power := primary ('^' power)?
 *          primary := number | '(' expr ')'
 */
function parseExpr(tokens: Array<{ type: string; value: string }>): number {
  let pos = 0;
  const peek = () => tokens[pos];
  const next = () => tokens[pos++];

  function primary(): number {
    const t = next();
    if (!t) throw new Error("unexpected end");
    if (t.type === "num") return parseFloat(t.value);
    if (t.type === "op" && t.value === "(") {
      const v = expr();
      const close = next();
      if (!close || close.type !== "op" || close.value !== ")") throw new Error("missing )");
      return v;
    }
    throw new Error("expected number or (");
  }
  function power(): number {
    let base = primary();
    const t = peek();
    if (t && t.type === "op" && t.value === "^") {
      next();
      const exp = power(); // right-associative
      const r = Math.pow(base, exp);
      if (!isFinite(r)) throw new Error("overflow");
      return r;
    }
    return base;
  }
  function factor(): number {
    const t = peek();
    if (t && t.type === "op" && (t.value === "+" || t.value === "-")) {
      next();
      const v = factor();
      return t.value === "-" ? -v : v;
    }
    return power();
  }
  function term(): number {
    let v = factor();
    for (;;) {
      const t = peek();
      if (t && t.type === "op" && (t.value === "*" || t.value === "/" || t.value === "%")) {
        next();
        const rhs = factor();
        if (t.value === "*") v = v * rhs;
        else if (t.value === "/") { if (rhs === 0) throw new Error("division by zero"); v = v / rhs; }
        else { if (rhs === 0) throw new Error("division by zero"); v = v % rhs; }
        if (!isFinite(v)) throw new Error("overflow");
      } else break;
    }
    return v;
  }
  function expr(): number {
    let v = term();
    for (;;) {
      const t = peek();
      if (t && t.type === "op" && (t.value === "+" || t.value === "-")) {
        next();
        const rhs = term();
        v = t.value === "+" ? v + rhs : v - rhs;
        if (!isFinite(v)) throw new Error("overflow");
      } else break;
    }
    return v;
  }
  const result = expr();
  if (pos < tokens.length) throw new Error("trailing tokens");
  return result;
}

/** Safely evaluate an arithmetic expression. Throws on anything unsafe. */
export function safeEval(expr: string): number {
  const cleaned = expr.trim();
  if (cleaned.length === 0 || cleaned.length > 100) throw new Error("bad length");
  // Allowlist: digits, operators, parens, decimal point, whitespace only.
  if (!/^[0-9+\-*/%^().\s]+$/.test(cleaned)) throw new Error("disallowed characters");
  const tokens = tokenize(cleaned);
  if (tokens.length === 0) throw new Error("empty");
  return parseExpr(tokens);
}

/* ------------------------------------------------------------------ */
/* Fast-lane handlers.                                                 */
/* ------------------------------------------------------------------ */

function formatTime(tz: string, now: Date): string {
  const fmt = new Intl.DateTimeFormat("en-CA", {
    timeZone: tz, hour: "numeric", minute: "2-digit", hour12: true,
  });
  return fmt.format(now);
}

function formatDate(tz: string, now: Date): string {
  const fmt = new Intl.DateTimeFormat("en-CA", {
    timeZone: tz, weekday: "long", year: "numeric", month: "long", day: "numeric",
  });
  return fmt.format(now);
}

/** Words for small integers (for speakable arithmetic answers). */
const SMALL_NUMS = [
  "zero","one","two","three","four","five","six","seven","eight","nine","ten",
  "eleven","twelve","thirteen","fourteen","fifteen","sixteen","seventeen",
  "eighteen","nineteen","twenty",
];

function speakableNumber(n: number): string {
  if (Number.isInteger(n) && n >= 0 && n <= 20) return SMALL_NUMS[n];
  if (Number.isInteger(n)) return n.toLocaleString("en-CA");
  return String(Math.round(n * 100) / 100);
}

/* ------------------------------------------------------------------ */
/* Pattern matching helpers.                                           */
/* ------------------------------------------------------------------ */

/** Normalize text for matching: lowercase, collapse whitespace, strip punctuation at ends. */
function norm(text: string): string {
  return text.toLowerCase().replace(/[?!.,;:]+$/g, "").replace(/\s+/g, " ").trim();
}

/** Words that force full lane (context-dependent, ambiguous, or sensitive). */
const FORCE_FULL_PATTERNS: RegExp[] = [
  /\b(tomorrow|yesterday|next week|last week|tonight)\b/,       // needs temporal context
  /\b(there|that|it|this|those|these)\b/,                        // pronoun → needs context
  /\b(montreal|toronto|laval|quebec)\b/i,                        // location qualifier
  /\b(remind|remember|note|email|message|send|call|text)\b/,     // actions
  /\b(should|would|could|do you think|opinion|advice)\b/,        // judgment/opinion
  /\b(my|mine|our)\b/,                                           // personal reference
  /\b(going to|will it|is it going to)\b/,                      // prediction
  /\b(jacket|umbrella|bring|wear)\b/,                            // advice-seeking
  /\b(about|what about|how about|and)\b/,                        // follow-up connector
];

/** Returns true if the message contains any force-full signal. */
function hasForceFullSignal(text: string): boolean {
  const t = norm(text);
  return FORCE_FULL_PATTERNS.some((re) => re.test(t));
}

/* --- Time/date patterns --- */
const TIME_PATTERNS = [
  /^(what('s| is) the time|what time is it|tell me the time|current time|do you have the time)$/,
  /^(what time is it( right now)?)$/,
];
const DATE_PATTERNS = [
  /^(what('s| is) (today's date|the date|today)|what day is it|what day is today|tell me (today's date|the date|today))$/,
  /^(what('s| is) the date today)$/,
];

/* --- Greeting patterns (standalone only) --- */
const GREETING_PATTERNS = [
  /^(hi|hello|hey|good morning|good afternoon|good evening|good night|howdy)$/,
];

/* --- Arithmetic patterns --- */
/** Extract an arithmetic expression from natural language. Returns null if not parseable. */
function extractArithmetic(text: string): string | null {
  const t = norm(text);

  // "what is X" / "calculate X" / "how much is X" where X looks like arithmetic
  const m = t.match(/^(?:what(?:'s| is)|calculate|compute|how much is|tell me)\s+(.+)$/);
  if (!m) return null;
  let expr = m[1];

  // Convert words to operators.
  expr = expr
    .replace(/\bplus\b/g, "+")
    .replace(/\bminus\b/g, "-")
    .replace(/\btimes\b/g, "*")
    .replace(/\bmultiplied by\b/g, "*")
    .replace(/\bdivided by\b/g, "/")
    .replace(/\bover\b/g, "/")
    .replace(/\bmod\b|\bmodulo\b/g, "%")
    .replace(/\bto the power of\b|\bpower\b/g, "^")
    .replace(/\bsquared\b/g, "^2")
    .replace(/\bpercent of\b/g, "% of"); // handled specially below

  // Handle "X percent of Y" → (X/100)*Y
  const pct = expr.match(/^([\d.]+)\s*%\s*of\s+(.+)$/);
  if (pct) {
    expr = `(${pct[1]}/100)*(${pct[2]})`;
  }

  // After conversion, the whole thing must be pure arithmetic.
  if (!/^[0-9+\-*/%^().\s]+$/.test(expr)) return null;
  if (!/[0-9]/.test(expr)) return null;
  // Must contain at least one operator to be arithmetic (not just a number).
  if (!/[+\-*/%^]/.test(expr.replace(/^\s*-/, ""))) return null;

  // Sanity: try to evaluate. If it throws, not valid arithmetic.
  try {
    safeEval(expr);
  } catch {
    return null;
  }
  return expr;
}

/* ------------------------------------------------------------------ */
/* Router.                                                             */
/* ------------------------------------------------------------------ */

export function route(text: string, ctx: RouterContext = {}): RouteResult {
  const t = norm(text);
  const tz = ctx.timezone ?? "America/Toronto";

  // Empty or too long → full (don't try to be clever).
  if (t.length === 0 || t.length > 500) {
    return { lane: "full", confidence: 1.0, reason: "empty or too long" };
  }

  // Check specific fast patterns FIRST. They are tightly anchored (^$)
  // so they only match unambiguous requests. This must come before the
  // force-full signals, because idioms like "what time is it" contain
  // the word "it" which would otherwise trigger the pronoun rule.

  // Time.
  if (TIME_PATTERNS.some((re) => re.test(t))) {
    return { lane: "fast", handler: "time", confidence: 0.98, reason: "time pattern" };
  }

  // Date.
  if (DATE_PATTERNS.some((re) => re.test(t))) {
    return { lane: "fast", handler: "date", confidence: 0.98, reason: "date pattern" };
  }

  // Greeting (standalone only).
  if (GREETING_PATTERNS.some((re) => re.test(t))) {
    return { lane: "fast", handler: "greeting", confidence: 0.95, reason: "standalone greeting" };
  }

  // Arithmetic.
  const expr = extractArithmetic(t);
  if (expr !== null) {
    return { lane: "fast", handler: "arithmetic", confidence: 0.97, reason: `arithmetic: ${expr}` };
  }

  // Force-full signals catch everything ambiguous that didn't match
  // a specific fast pattern.
  if (hasForceFullSignal(t)) {
    return { lane: "full", confidence: 0.95, reason: "context/action/opinion signal detected" };
  }

  // Default: full lane.
  return { lane: "full", confidence: 0.9, reason: "no fast pattern matched" };
}

/* ------------------------------------------------------------------ */
/* Fast-lane execution (deterministic, no LLM).                         */
/* ------------------------------------------------------------------ */

export interface FastResult {
  /** Short, speakable reply text. */
  reply: string;
  /** Handler that produced it. */
  handler: string;
  /** Execution time in ms. */
  elapsedMs: number;
}

const GREETING_REPLIES = [
  "Hey Kareem! What can I do for you?",
  "Hi Kareem! What's on your mind?",
  "Hello! How can I help?",
];

/**
 * Execute a fast-lane request. Returns a short speakable reply.
 * Throws if the handler fails — caller must fall back to full lane.
 */
export function executeFast(
  text: string,
  routeResult: RouteResult,
  ctx: RouterContext = {}
): FastResult {
  const start = Date.now();
  const tz = ctx.timezone ?? "America/Toronto";
  const now = new Date();
  const t = norm(text);
  let reply: string;

  switch (routeResult.handler) {
    case "time":
      reply = `It's ${formatTime(tz, now)}, Kareem.`;
      break;
    case "date":
      reply = `Today is ${formatDate(tz, now)}.`;
      break;
    case "greeting": {
      // Deterministic pick based on hour (no randomness in tests).
      const hour = parseInt(
        new Intl.DateTimeFormat("en-CA", { timeZone: tz, hour: "numeric", hour12: false }).format(now),
        10
      );
      reply = GREETING_REPLIES[hour % GREETING_REPLIES.length];
      break;
    }
    case "arithmetic": {
      const expr = extractArithmetic(t);
      if (!expr) throw new Error("arithmetic extraction failed at execution");
      const value = safeEval(expr);
      if (!isFinite(value)) throw new Error("non-finite result");
      reply = `${speakableNumber(value)}.`;
      break;
    }
    default:
      throw new Error(`unknown fast handler: ${routeResult.handler}`);
  }

  return { reply, handler: routeResult.handler!, elapsedMs: Date.now() - start };
}
