# Consolidate Same-Session Screenshot Captures Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `scripts/screenshot-capture.py` merge consecutive OCR captures of the same page into one buffered "session," sending a single `/addContent` POST per session instead of one per capture.

**Architecture:** All new logic is client-side, pure-function-first: a session is keyed by exact URL (UIA-capable browsers) or site name (fallback), tracked in `screenshot-capture-state.json` as an `active_session` object accumulating deduped lines across captures. `main()` is restructured to route every run through two small pure decision functions — `check_idle_flush` (safety-net flush when a session goes quiet) and `resolve_active_session` (start/continue/flush-on-key-change) — so the only untested code is the thin I/O wrapper (`flush_session`) that actually POSTs.

**Tech Stack:** Python, existing `screenshot-capture.py` toolchain (no new dependencies).

## Global Constraints

- No backend changes — `ResourceReceiver`, `EmbeddingIndex`, `LuceneIndex`, `TextEntryDao`, `/addContent` are untouched. Consolidation is entirely inside `screenshot-capture.py`.
- One `/addContent` POST per session (at flush time), not per capture.
- Session key = exact URL via `get_address_bar_url()` when `browser in UIA_CAPABLE_BROWSERS`, else falls back to `pagename`.
- Idle-timeout safety net: `IDLE_TIMEOUT_SECONDS = 120`. A session flushes if `120` seconds pass with no capture refreshing it — this check runs every invocation, even when the current foreground window isn't a tracked site.
- A capture whose screenshot hash is unchanged from the last one must still keep the active session alive (refresh `last_capture_at`) without running OCR or touching `lines` — otherwise a long static (non-scrolling) reading session would falsely idle-time-out.
- Merge is append-only, exact-string line dedup, order-preserving — no line is ever removed once added to a session.
- `state["last_sent_text"]` is renamed `state["last_processed_text"]` (same purpose: skip reprocessing an unchanged OCR result per capture; decoupled from "sent," since nothing is sent until flush).
- New pure functions must be duplicated (not imported) into `scripts/test_screenshot_logic.py`, matching that file's existing convention (it inlines pure functions rather than importing from the hyphenated `screenshot-capture.py` filename).
- `flush_session` (the actual HTTP POST) is verified manually against live captures, not unit-tested — matching the `DeepseekSummarizeClientTest` precedent in `docs/testing.md` (subprocess/HTTP integrations aren't unit-tested in CI).
- Tests run via `cd scripts && python -m pytest test_screenshot_logic.py -v` (matching the prior Tesseract plan's convention).

---

### Task 1: Session key derivation

**Files:**
- Modify: `scripts/screenshot-capture.py` (insert after `save_state()`, currently ending at line 251, before `preprocess_for_ocr` at line 254)
- Test: `scripts/test_screenshot_logic.py`

**Interfaces:**
- Produces: `derive_session_key(pagename: str, url: str | None) -> str`, used by later tasks (`main()`, Task 6) to compute the session key each run.

- [ ] **Step 1: Write the failing test**

Add to `scripts/test_screenshot_logic.py`:

```python
def derive_session_key(pagename: str, url: str) -> str:
    return url if url else pagename

def test_derive_session_key_uses_url_when_present():
    assert derive_session_key("linkedin", "https://www.linkedin.com/feed/") == "https://www.linkedin.com/feed/"

def test_derive_session_key_falls_back_to_pagename_when_no_url():
    assert derive_session_key("linkedin", None) == "linkedin"
```

(The inline `derive_session_key` here is a placeholder duplicate matching the real implementation added in Step 3 — this file always inlines its own copies of the pure functions under test, per its existing convention.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -k derive_session_key -v`
Expected: both tests currently PASS against the placeholder (there's no "fails first" step here since the placeholder already implements the function correctly — this mirrors how this test file works generally: it doesn't import the real module, so there's no red/green against the production file itself). Confirm instead that the two new tests are collected and pass: `2 passed`.

- [ ] **Step 3: Add the real implementation to `scripts/screenshot-capture.py`**

Insert immediately after `save_state()` (which ends at line 251) and before `def preprocess_for_ocr`:

```python
IDLE_TIMEOUT_SECONDS = 120


def derive_session_key(pagename: str, url: str | None) -> str:
    return url if url else pagename
```

- [ ] **Step 4: Run the full test file to confirm nothing broke**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -v`
Expected: all tests pass (previous 48 plus the 2 new ones = 50).

- [ ] **Step 5: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add scripts/screenshot-capture.py scripts/test_screenshot_logic.py
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: add derive_session_key for screenshot session consolidation"
```

---

### Task 2: Session line merging

**Files:**
- Modify: `scripts/screenshot-capture.py` (append after `derive_session_key`, added in Task 1)
- Test: `scripts/test_screenshot_logic.py`

**Interfaces:**
- Produces: `merge_session_lines(existing_lines: list[str], new_text: str) -> list[str]`, used by `resolve_active_session`/`main()` (Task 4, Task 6) to fold a new capture's OCR text into the session buffer.

- [ ] **Step 1: Write the failing test**

Add to `scripts/test_screenshot_logic.py`:

```python
def merge_session_lines(existing_lines: list, new_text: str) -> list:
    merged = list(existing_lines)
    seen = set(existing_lines)
    for line in new_text.split("\n"):
        if line not in seen:
            merged.append(line)
            seen.add(line)
    return merged

def test_merge_session_lines_appends_new_lines():
    result = merge_session_lines(["a", "b"], "b\nc")
    assert result == ["a", "b", "c"]

def test_merge_session_lines_empty_existing():
    result = merge_session_lines([], "x\ny")
    assert result == ["x", "y"]

def test_merge_session_lines_all_duplicates_no_change():
    result = merge_session_lines(["a", "b"], "a\nb")
    assert result == ["a", "b"]

def test_merge_session_lines_preserves_existing_order():
    result = merge_session_lines(["z", "a"], "a\nnew")
    assert result == ["z", "a", "new"]
```

- [ ] **Step 2: Run test to verify it passes against the inlined copy**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -k merge_session_lines -v`
Expected: `4 passed`

- [ ] **Step 3: Add the real implementation to `scripts/screenshot-capture.py`**

Append directly after `derive_session_key`:

```python
def merge_session_lines(existing_lines: list[str], new_text: str) -> list[str]:
    merged = list(existing_lines)
    seen = set(existing_lines)
    for line in new_text.split("\n"):
        if line not in seen:
            merged.append(line)
            seen.add(line)
    return merged
```

- [ ] **Step 4: Run the full test file to confirm nothing broke**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -v`
Expected: all 54 tests pass.

- [ ] **Step 5: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add scripts/screenshot-capture.py scripts/test_screenshot_logic.py
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: add merge_session_lines for screenshot session consolidation"
```

---

### Task 3: Idle-timeout predicate

**Files:**
- Modify: `scripts/screenshot-capture.py` (append after `merge_session_lines`, added in Task 2)
- Test: `scripts/test_screenshot_logic.py`

**Interfaces:**
- Consumes: nothing new (uses stdlib `datetime`, already imported at the top of both files' scopes — `test_screenshot_logic.py` needs `import datetime` added since it currently doesn't import it).
- Produces: `is_session_idle(last_capture_at: str, now: datetime.datetime, idle_timeout_seconds: int) -> bool`, used by `check_idle_flush` (Task 5).

- [ ] **Step 1: Write the failing test**

Add `import datetime` to the top of `scripts/test_screenshot_logic.py` (alongside the existing `hashlib`/`json`/`re`/`tempfile`/`Path`/`Image` imports), then add:

```python
def is_session_idle(last_capture_at: str, now, idle_timeout_seconds: int) -> bool:
    last = datetime.datetime.fromisoformat(last_capture_at)
    return (now - last).total_seconds() > idle_timeout_seconds

def test_is_session_idle_true_when_over_threshold():
    last = datetime.datetime(2026, 1, 1, 12, 0, 0)
    now = datetime.datetime(2026, 1, 1, 12, 2, 1)
    assert is_session_idle(last.isoformat(), now, 120) is True

def test_is_session_idle_false_when_under_threshold():
    last = datetime.datetime(2026, 1, 1, 12, 0, 0)
    now = datetime.datetime(2026, 1, 1, 12, 1, 0)
    assert is_session_idle(last.isoformat(), now, 120) is False

def test_is_session_idle_exact_threshold_not_idle():
    last = datetime.datetime(2026, 1, 1, 12, 0, 0)
    now = datetime.datetime(2026, 1, 1, 12, 2, 0)
    assert is_session_idle(last.isoformat(), now, 120) is False
```

- [ ] **Step 2: Run test to verify it passes against the inlined copy**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -k is_session_idle -v`
Expected: `3 passed`

- [ ] **Step 3: Add the real implementation to `scripts/screenshot-capture.py`**

Append directly after `merge_session_lines`:

```python
def is_session_idle(last_capture_at: str, now: datetime.datetime, idle_timeout_seconds: int) -> bool:
    last = datetime.datetime.fromisoformat(last_capture_at)
    return (now - last).total_seconds() > idle_timeout_seconds
```

- [ ] **Step 4: Run the full test file to confirm nothing broke**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -v`
Expected: all 57 tests pass.

- [ ] **Step 5: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add scripts/screenshot-capture.py scripts/test_screenshot_logic.py
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: add is_session_idle for screenshot session consolidation"
```

---

### Task 4: Session construction and resolution

**Files:**
- Modify: `scripts/screenshot-capture.py` (append after `is_session_idle`, added in Task 3)
- Test: `scripts/test_screenshot_logic.py`

**Interfaces:**
- Consumes: `derive_session_key` (Task 1, for constructing test session keys only — not called internally by these functions).
- Produces:
  - `start_session(session_key: str, pagename: str, window_title: str, now: datetime.datetime) -> dict` — returns a fresh session dict `{"key", "pagename", "window_title", "started_at", "last_capture_at", "lines"}`.
  - `resolve_active_session(state: dict, pagename: str, window_title: str, session_key: str, now: datetime.datetime) -> tuple[dict, dict | None]` — returns `(session_dict, session_to_flush)`. `session_to_flush` is the previous `active_session` if its `key` doesn't match `session_key` (caller must flush it), else `None`. Used by `main()` (Task 6).

- [ ] **Step 1: Write the failing test**

Add to `scripts/test_screenshot_logic.py`:

```python
def start_session(session_key: str, pagename: str, window_title: str, now) -> dict:
    timestamp = now.isoformat()
    return {
        "key": session_key,
        "pagename": pagename,
        "window_title": window_title,
        "started_at": timestamp,
        "last_capture_at": timestamp,
        "lines": [],
    }

def resolve_active_session(state: dict, pagename: str, window_title: str, session_key: str, now):
    active_session = state.get("active_session")
    session_to_flush = None
    if active_session is not None and active_session["key"] != session_key:
        session_to_flush = active_session
        active_session = None
    if active_session is None:
        active_session = start_session(session_key, pagename, window_title, now)
    return active_session, session_to_flush

def test_start_session_shape():
    now = datetime.datetime(2026, 7, 15, 16, 44, 32)
    session = start_session("https://www.linkedin.com/feed/", "linkedin", "Feed | LinkedIn", now)
    assert session == {
        "key": "https://www.linkedin.com/feed/",
        "pagename": "linkedin",
        "window_title": "Feed | LinkedIn",
        "started_at": now.isoformat(),
        "last_capture_at": now.isoformat(),
        "lines": [],
    }

def test_resolve_active_session_starts_new_when_none():
    now = datetime.datetime(2026, 7, 15, 16, 44, 32)
    session, to_flush = resolve_active_session({}, "linkedin", "Feed | LinkedIn", "linkedin-key", now)
    assert session["key"] == "linkedin-key"
    assert session["lines"] == []
    assert to_flush is None

def test_resolve_active_session_continues_when_key_matches():
    existing = {"key": "linkedin-key", "pagename": "linkedin", "window_title": "t",
                "started_at": "2026-07-15T16:00:00", "last_capture_at": "2026-07-15T16:00:00", "lines": ["a"]}
    now = datetime.datetime(2026, 7, 15, 16, 1, 0)
    session, to_flush = resolve_active_session({"active_session": existing}, "linkedin", "t", "linkedin-key", now)
    assert session is existing
    assert to_flush is None

def test_resolve_active_session_flushes_old_when_key_changes():
    existing = {"key": "old-key", "pagename": "linkedin", "window_title": "t",
                "started_at": "2026-07-15T16:00:00", "last_capture_at": "2026-07-15T16:00:00", "lines": ["a"]}
    now = datetime.datetime(2026, 7, 15, 16, 1, 0)
    session, to_flush = resolve_active_session({"active_session": existing}, "linkedin", "t", "new-key", now)
    assert to_flush is existing
    assert session["key"] == "new-key"
    assert session["lines"] == []
```

- [ ] **Step 2: Run test to verify it passes against the inlined copy**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -k "start_session or resolve_active_session" -v`
Expected: `4 passed`

- [ ] **Step 3: Add the real implementation to `scripts/screenshot-capture.py`**

Append directly after `is_session_idle`:

```python
def start_session(session_key: str, pagename: str, window_title: str, now: datetime.datetime) -> dict:
    timestamp = now.isoformat()
    return {
        "key": session_key,
        "pagename": pagename,
        "window_title": window_title,
        "started_at": timestamp,
        "last_capture_at": timestamp,
        "lines": [],
    }


def resolve_active_session(
    state: dict, pagename: str, window_title: str, session_key: str, now: datetime.datetime
) -> tuple[dict, dict | None]:
    active_session = state.get("active_session")
    session_to_flush = None
    if active_session is not None and active_session["key"] != session_key:
        session_to_flush = active_session
        active_session = None
    if active_session is None:
        active_session = start_session(session_key, pagename, window_title, now)
    return active_session, session_to_flush
```

- [ ] **Step 4: Run the full test file to confirm nothing broke**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -v`
Expected: all 61 tests pass.

- [ ] **Step 5: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add scripts/screenshot-capture.py scripts/test_screenshot_logic.py
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: add start_session/resolve_active_session for screenshot session consolidation"
```

---

### Task 5: Idle-timeout flush check

**Files:**
- Modify: `scripts/screenshot-capture.py` (append after `resolve_active_session`, added in Task 4)
- Test: `scripts/test_screenshot_logic.py`

**Interfaces:**
- Consumes: `is_session_idle` (Task 3).
- Produces: `check_idle_flush(state: dict, now: datetime.datetime, idle_timeout_seconds: int) -> tuple[dict, dict | None]` — returns `(possibly-updated state, session-to-flush-or-None)`. Used by `main()` (Task 6) as the very first thing each run does, before checking whether a tracked site is in the foreground.

- [ ] **Step 1: Write the failing test**

Add to `scripts/test_screenshot_logic.py`:

```python
def check_idle_flush(state: dict, now, idle_timeout_seconds: int):
    active_session = state.get("active_session")
    if active_session is None or not is_session_idle(active_session["last_capture_at"], now, idle_timeout_seconds):
        return state, None
    new_state = dict(state)
    new_state["active_session"] = None
    return new_state, active_session

def test_check_idle_flush_no_active_session():
    now = datetime.datetime(2026, 7, 15, 16, 44, 32)
    state, to_flush = check_idle_flush({}, now, 120)
    assert to_flush is None
    assert state == {}

def test_check_idle_flush_not_idle_yet():
    session = {"key": "k", "last_capture_at": "2026-07-15T16:44:00", "lines": ["a"]}
    now = datetime.datetime(2026, 7, 15, 16, 45, 0)
    state, to_flush = check_idle_flush({"active_session": session}, now, 120)
    assert to_flush is None
    assert state["active_session"] is session

def test_check_idle_flush_idle_flushes_and_clears():
    session = {"key": "k", "last_capture_at": "2026-07-15T16:44:00", "lines": ["a"]}
    now = datetime.datetime(2026, 7, 15, 16, 47, 0)
    state, to_flush = check_idle_flush({"active_session": session}, now, 120)
    assert to_flush is session
    assert state["active_session"] is None
```

- [ ] **Step 2: Run test to verify it passes against the inlined copy**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -k check_idle_flush -v`
Expected: `3 passed`

- [ ] **Step 3: Add the real implementation to `scripts/screenshot-capture.py`**

Append directly after `resolve_active_session`:

```python
def check_idle_flush(
    state: dict, now: datetime.datetime, idle_timeout_seconds: int
) -> tuple[dict, dict | None]:
    active_session = state.get("active_session")
    if active_session is None or not is_session_idle(active_session["last_capture_at"], now, idle_timeout_seconds):
        return state, None
    new_state = dict(state)
    new_state["active_session"] = None
    return new_state, active_session
```

- [ ] **Step 4: Run the full test file to confirm nothing broke**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -v`
Expected: all 64 tests pass.

- [ ] **Step 5: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add scripts/screenshot-capture.py scripts/test_screenshot_logic.py
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: add check_idle_flush for screenshot session consolidation"
```

---

### Task 6: Wire session consolidation into `main()`

**Files:**
- Modify: `scripts/screenshot-capture.py`:
  - Add `flush_session` after `send_to_digital_me` (currently lines 281-288, before `def main` at line 290)
  - Replace the body of `main()` (currently lines 290-327)

**Interfaces:**
- Consumes: `derive_session_key`, `merge_session_lines`, `is_session_idle`, `start_session`, `resolve_active_session`, `check_idle_flush`, `IDLE_TIMEOUT_SECONDS` (Tasks 1-5), `send_to_digital_me` (existing).
- Produces: the wired end-to-end behavior. No new pure functions — this task is integration, verified by running the full test suite plus a manual live check (per this project's convention for I/O-touching code, see `docs/testing.md`'s note on `DeepseekSummarizeClientTest`).

This task has no "write failing test" step: it wires already-tested pure functions into `main()`, and `main()` itself (like today) is not unit-tested — it directly drives window capture, OCR, and network I/O.

- [ ] **Step 1: Add `flush_session`**

Insert directly after `send_to_digital_me` (which ends at line 288) and before `def main() -> None:`:

```python
def flush_session(session: dict) -> None:
    if not session["lines"]:
        return
    merged_text = "\n".join(session["lines"])
    started = datetime.datetime.fromisoformat(session["started_at"])
    timestamp = started.strftime("%Y%m%d_%H%M%S")
    entry_name = f"screenshot_{session['pagename']}_{timestamp}"
    send_to_digital_me(session["window_title"], entry_name, merged_text)
```

- [ ] **Step 2: Replace `main()`**

Replace the entire current body (lines 290-327):

```python
def main() -> None:
    hwnd, title = get_active_window()
    pagename, browser, window_title = detect_site(title)
    if pagename is None:
        return
    if pagename in SUBPAGE_GATED_SITES and browser in UIA_CAPABLE_BROWSERS:
        url = get_address_bar_url(hwnd)
        if url is not None and has_subpath(url) and not is_subpage_exempt(url):
            return
    crop_box = None
    needs_line_filtering = False
    if pagename in CROP_CONTENT_SITES and browser in UIA_CAPABLE_BROWSERS:
        crop_box, needs_line_filtering = get_main_content_rect(hwnd)
    bmp_bytes = take_screenshot_bmp(hwnd, crop_box)
    current_hash = hash_bytes(bmp_bytes)

    state = load_state()
    if current_hash == state.get("last_hash"):
        return

    if needs_line_filtering:
        ocr_text = run_ocr_filtered(bmp_bytes).strip().replace("\r\n", "\n")
    else:
        ocr_text = run_ocr(bmp_bytes).strip().replace("\r\n", "\n")

    if ocr_text == state.get("last_sent_text"):
        state["last_hash"] = current_hash
        save_state(state)
        return

    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    entry_name = f"screenshot_{pagename}_{timestamp}"
    send_to_digital_me(window_title, entry_name, ocr_text)

    state["last_hash"] = current_hash
    state["last_sent_text"] = ocr_text
    save_state(state)
```

with:

```python
def main() -> None:
    now = datetime.datetime.now()
    state = load_state()

    # Runs regardless of what's currently in the foreground, so a session isn't
    # stranded forever if the user never returns to that tab (e.g. closes the browser).
    state, stale_session = check_idle_flush(state, now, IDLE_TIMEOUT_SECONDS)
    if stale_session is not None:
        flush_session(stale_session)
        save_state(state)

    hwnd, title = get_active_window()
    pagename, browser, window_title = detect_site(title)
    if pagename is None:
        return

    url = get_address_bar_url(hwnd) if browser in UIA_CAPABLE_BROWSERS else None
    if pagename in SUBPAGE_GATED_SITES and url is not None and has_subpath(url) and not is_subpage_exempt(url):
        return

    crop_box = None
    needs_line_filtering = False
    if pagename in CROP_CONTENT_SITES and browser in UIA_CAPABLE_BROWSERS:
        crop_box, needs_line_filtering = get_main_content_rect(hwnd)
    bmp_bytes = take_screenshot_bmp(hwnd, crop_box)
    current_hash = hash_bytes(bmp_bytes)
    session_key = derive_session_key(pagename, url)

    if current_hash == state.get("last_hash"):
        # Nothing changed on screen, but the user may just be reading without
        # scrolling -- keep the session alive rather than letting it idle out.
        active_session, session_to_flush = resolve_active_session(state, pagename, window_title, session_key, now)
        active_session["last_capture_at"] = now.isoformat()
        state = dict(state)
        state["active_session"] = active_session
        if session_to_flush is not None:
            flush_session(session_to_flush)
        save_state(state)
        return

    if needs_line_filtering:
        ocr_text = run_ocr_filtered(bmp_bytes).strip().replace("\r\n", "\n")
    else:
        ocr_text = run_ocr(bmp_bytes).strip().replace("\r\n", "\n")

    active_session, session_to_flush = resolve_active_session(state, pagename, window_title, session_key, now)
    if ocr_text != state.get("last_processed_text"):
        active_session["lines"] = merge_session_lines(active_session["lines"], ocr_text)
    active_session["last_capture_at"] = now.isoformat()

    state = dict(state)
    state["active_session"] = active_session
    state["last_hash"] = current_hash
    state["last_processed_text"] = ocr_text
    if session_to_flush is not None:
        flush_session(session_to_flush)
    save_state(state)
```

Note the URL is now fetched once (for any UIA-capable browser on a tracked site), reused for both the existing subpage-gating check and session-key derivation — previously it was only fetched for `SUBPAGE_GATED_SITES` (quora/linkedin), never for facebook.

- [ ] **Step 3: Rename the state key in `load_state()`'s default**

`load_state()` currently returns `{"last_hash": None, "last_sent_text": None}` when the state file doesn't exist. Update to:

```python
def load_state() -> dict:
    if STATE_FILE.exists():
        try:
            return json.loads(STATE_FILE.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {"last_hash": None, "last_processed_text": None}
```

- [ ] **Step 4: Run the full test file to confirm nothing broke**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -v`
Expected: all 64 tests pass (this task adds no new pure-function tests, only wiring).

Also update `test_state_roundtrip` and `test_load_state_missing_file` in `scripts/test_screenshot_logic.py`, which currently reference the old `last_sent_text` key name, to use `last_processed_text` instead, so they reflect the real state shape.

- [ ] **Step 5: Manual verification**

With the app and screenshot watcher running (`build-and-deploy` skill, or `restart-digital-me.ps1`), open a tracked site (e.g. LinkedIn feed) and scroll slowly for at least 30 seconds, then switch away to an untracked window (or a different tracked URL) to trigger a flush. Confirm:
1. Only one new file appears under `mcp-resources/<year-month>/` for that session (not one per capture).
2. That file's content contains lines from multiple points in the scroll (not just the last screenshot).
3. `screenshot-capture-state.json` shows `active_session` populated while scrolling, then cleared (or replaced with a new session) after switching away.

- [ ] **Step 6: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add scripts/screenshot-capture.py scripts/test_screenshot_logic.py
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: consolidate same-session screenshot captures into a single POST"
```

---

### Task 7: Document session consolidation in `docs/architecture.md`

**Files:**
- Modify: `docs/architecture.md`

**Interfaces:**
- None (documentation only).

- [ ] **Step 1: Update the "Screenshot OCR capture" section**

In `docs/architecture.md`, find the existing "## Screenshot OCR capture (`scripts/`)" section (added in the Tesseract branch) and add a new bullet after the "Dedup" bullet:

```markdown
- **Session consolidation:** consecutive captures of the same page are merged into one buffered session rather than sent individually. A session is keyed by the exact URL (`get_address_bar_url()`, chrome/edge only) or, when the URL can't be read, the site name. Each capture's OCR lines are appended to the session's line buffer with exact-string dedup (`merge_session_lines()`), so scrolled-past content accumulates in one place instead of being spread across many overlapping files. A session flushes (single `/addContent` POST via `flush_session()`) when a capture resolves to a different session key, or — as a safety net, checked every run via `check_idle_flush()` — when `IDLE_TIMEOUT_SECONDS` (120s) pass with no capture refreshing it, so a session isn't stranded if the browser is closed mid-session.
```

- [ ] **Step 2: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add docs/architecture.md
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "docs: document screenshot session consolidation"
```

---

### Task 8: Final verification and branch wrap-up

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite one more time**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -v`
Expected: all tests pass.

- [ ] **Step 2: Review the full branch diff**

Run: `git -C /c/Users/Lenovo/IdeaProjects/digital-me diff main...feature/consolidate-screenshots --stat`
Expected: shows changes to `scripts/screenshot-capture.py`, `scripts/test_screenshot_logic.py`, `docs/architecture.md`, plus the design spec and this plan under `docs/superpowers/`.

- [ ] **Step 3: Confirm the manual verification from Task 6 Step 5 is still fresh**

If the app has been restarted or code has changed since that manual check, repeat it once more before considering the branch done.
