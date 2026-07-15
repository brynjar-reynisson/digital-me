# Consolidate Same-URL Screenshot Captures — Design

## Problem

`scripts/screenshot-capture.py` writes one `mcp-resources` file per distinct screenshot capture (roughly every 3 seconds while a tracked site is in the foreground and content has changed). While actively reading/scrolling a single page, this produces many small, heavily-overlapping files — each capture repeats most of the previous capture's text plus a bit of new content that scrolled into view. This clutters `mcp-resources/`, produces redundant embeddings/search hits for what is really one browsing session on one page, and doesn't read as a coherent document.

## Goal

Consolidate the OCR text from consecutive captures of the same page into a single `mcp-resources` file per browsing "session," sent to digital-me once the session ends, instead of one file per capture.

## Non-goals

- No change to capture cadence, hash-based screenshot dedup, crop/line-filtering pipeline, or the OCR engine itself.
- No backend changes (`ResourceReceiver`, `EmbeddingIndex`, `LuceneIndex`, `TextEntryDao`, `/addContent`) — consolidation happens entirely client-side in `screenshot-capture.py`, which still sends one `/addContent` POST per session (not per capture).
- No cross-session merging (e.g. revisiting the same URL an hour later starts a new session; it does not append to the earlier one).
- No batch/backfill pass over already-written historical files — this only changes behavior going forward.

## Session key

A session is identified by:
- The **exact URL** (via `get_address_bar_url()`), when the active browser is UIA-capable (chrome/edge — matches `UIA_CAPABLE_BROWSERS`) and the URL can be read.
- Otherwise, the **pagename** (site — linkedin/facebook/quora), as today's `SITE_KEYWORDS` already resolve. This is the fallback for firefox/opera/brave, where the address bar can't be read via UIA.

A session ends when a capture resolves to a different session key than the currently active session, or when the active session goes idle (see below).

## State changes

`screenshot-capture-state.json` gains an `active_session` object. `last_sent_text` is renamed `last_processed_text` — it now guards against reprocessing an unchanged OCR result per capture (as it always has), decoupled from "sent to digital-me," since nothing is sent until a flush.

```json
{
  "last_hash": "...",
  "last_processed_text": "...",
  "active_session": {
    "key": "https://www.linkedin.com/feed/",
    "pagename": "linkedin",
    "window_title": "...",
    "started_at": "2026-07-15T16:44:32",
    "last_capture_at": "2026-07-15T16:45:10",
    "lines": ["...", "..."]
  }
}
```

`active_session` is `null`/absent when there is no open session. Missing/old-shaped state files (no `active_session` key) are treated as "no active session" — no migration needed.

## Per-run flow (`main()`)

1. **Idle-timeout flush check runs first**, before today's `if pagename is None: return`. If `active_session` is set and `now - last_capture_at` exceeds `IDLE_TIMEOUT_SECONDS` (120s), flush and clear it. This runs regardless of what's currently in the foreground, so a session isn't stranded forever if the user never returns to that tab (e.g. closes the browser).
2. If no tracked site is in the foreground: return (unchanged).
3. Compute this capture's `session_key` (URL-or-pagename, as above).
4. If there's an active session whose `key` differs from this capture's `session_key`: flush it, then start a new session for this capture.
5. If there's no active session: start one (`started_at` = now, empty `lines`).
6. Existing hash-dedup and OCR (`run_ocr`/`run_ocr_filtered`) run unchanged. Compare the OCR result to `last_processed_text`; skip merging (but still update `last_capture_at`) if unchanged.
7. **Merge**: split the OCR text into lines, append any line not already present in `active_session.lines` (exact-string dedup), preserving order. This is append-only within a session — lines never get removed even if they'd scroll out of view in later captures — so the session file accumulates the full set of distinct text seen while scrolling.
8. Update `active_session.last_capture_at`, save state. No POST happens on a normal per-capture run.

## Flush

`flush_session(session)`:
- Skips no-op if `lines` is empty.
- Joins `lines` with `"\n"`.
- Calls the existing `send_to_digital_me(source, entry_name, merged_text)`, where `entry_name` uses `session["started_at"]` for the timestamp (so the resulting file sorts to when the session began, consistent with today's naming) and `source`/`window_title` come from the session's start.
- Clears `active_session` in state after a successful send.

## Testing

- `derive_session_key(...)` and the line-merge function get duplicated as pure functions into `scripts/test_screenshot_logic.py`, per this project's existing convention (functions there are duplicated rather than imported, since the source file's hyphenated name can't be imported directly).
- The idle-timeout predicate (a simple timestamp comparison) is tested the same way.
- `flush_session`/`send_to_digital_me` stay manually verified via live captures, matching the existing convention for HTTP/subprocess-integration code (see `docs/testing.md`'s note on `DeepseekSummarizeClientTest`).

## Docs

Update `docs/architecture.md`'s "Screenshot OCR capture" section to describe session consolidation (session key derivation, append-dedup merge, idle-timeout flush), per CLAUDE.md's requirement to update docs when committing a feature branch.
