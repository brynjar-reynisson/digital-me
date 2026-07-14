# Screenshot Sub-Page Skip Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Skip screenshot capture on Quora and LinkedIn when the browser's current URL points at a sub-page (anything after the last `.com/`), instead of the root feed.

**Architecture:** `scripts/screenshot-capture.py` already gates capture on browser window title alone. This adds a second gate, evaluated only for Chrome/Edge windows showing Quora or LinkedIn: read the address bar via Windows UI Automation, and if the URL has content after the last `.com/`, return before taking the screenshot. Any failure to read the URL falls back to the existing behavior (capture).

**Tech Stack:** Python 3.13, `uiautomation` (new dependency), existing `pywin32`/`mss`/`winsdk`/`Pillow`/`requests` stack.

## Global Constraints

- Only Chrome and Edge get the URL-based sub-page check (both Chromium-based, same address bar accessible name). Firefox/Opera/Brave keep title-only gating, unchanged.
- Only Quora and LinkedIn are gated by sub-page detection. Facebook is unaffected.
- If the URL can't be read (UIA failure of any kind), fall back to capturing the screenshot — never fail closed.
- `has_subpath(url)`: `url.rfind(".com/")`; if not found, `False`; otherwise `True` iff there's any character after that `.com/`.
- No non-English UI locale support (matches existing English-only OCR limitation).

Full rationale: `docs/superpowers/specs/2026-07-14-screenshot-subpage-skip-design.md`

---

### Task 1: Extend `detect_site()` to also report the matched browser

**Files:**
- Modify: `scripts/screenshot-capture.py:26-33` (`detect_site`), `scripts/screenshot-capture.py:101-105` (`main`, call site)
- Test: `scripts/test_screenshot_logic.py:10-53` (inlined `detect_site` copy + existing tests)

**Interfaces:**
- Produces: `detect_site(window_title: str) -> tuple[str | None, str | None, str]` — `(pagename, browser, window_title)`. `browser` is the matched entry from `BROWSER_KEYWORDS` (e.g. `"chrome"`), or `None` when `pagename` is `None`.

- [ ] **Step 1: Update the existing tests in `scripts/test_screenshot_logic.py` to expect the new 3-tuple, without changing the inlined `detect_site` yet**

Replace the test functions (lines 22–53) with:

```python
def test_detect_quora():
    pagename, browser, title = detect_site("Quora - A place to share knowledge - Google Chrome")
    assert pagename == "quora", f"expected quora, got {pagename}"
    assert browser == "chrome", f"expected chrome, got {browser}"
    assert "Quora" in title

def test_detect_linkedin():
    pagename, browser, _ = detect_site("Feed | LinkedIn - Google Chrome")
    assert pagename == "linkedin"
    assert browser == "chrome"

def test_detect_facebook():
    pagename, browser, _ = detect_site("Facebook - Google Chrome")
    assert pagename == "facebook"
    assert browser == "chrome"

def test_detect_no_match():
    pagename, browser, _ = detect_site("GitHub - Microsoft Edge")
    assert pagename is None
    assert browser is None

def test_detect_case_insensitive():
    pagename, browser, _ = detect_site("QUORA - SOME TITLE - GOOGLE CHROME")
    assert pagename == "quora"
    assert browser == "chrome"

def test_detect_ignores_notepad():
    pagename, browser, _ = detect_site("screenshot_quora_20260620_014350.txt - Notepad")
    assert pagename is None
    assert browser is None

def test_detect_ignores_explorer():
    pagename, browser, _ = detect_site("LinkedIn - File Explorer")
    assert pagename is None
    assert browser is None

def test_detect_microsoft_edge():
    pagename, browser, _ = detect_site("Feed | LinkedIn - Microsoft Edge")
    assert pagename == "linkedin"
    assert browser == "edge"
```

**Do not** edit the inlined `detect_site` function body (lines 10–17) in this step.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -q`
Expected: FAIL — `ValueError: not enough values to unpack (expected 3, got 2)` on the first updated test.

- [ ] **Step 3: Update the inlined `detect_site` in `scripts/test_screenshot_logic.py` to return the 3-tuple**

Replace lines 10–17:

```python
def detect_site(window_title: str) -> tuple:
    lower = window_title.lower()
    matched_browser = next((b for b in BROWSER_KEYWORDS if b in lower), None)
    if matched_browser is None:
        return None, None, window_title
    for keyword, pagename in SITE_KEYWORDS.items():
        if keyword in lower:
            return pagename, matched_browser, window_title
    return None, None, window_title
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -q`
Expected: PASS (12 passed)

- [ ] **Step 5: Commit**

```bash
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" add scripts/test_screenshot_logic.py
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" commit -m "test: detect_site reports matched browser alongside pagename"
```

- [ ] **Step 6: Mirror the same change into the production file, `scripts/screenshot-capture.py`**

Replace lines 26–33 (`detect_site`):

```python
def detect_site(window_title: str) -> tuple[str | None, str | None, str]:
    lower = window_title.lower()
    matched_browser = next((b for b in BROWSER_KEYWORDS if b in lower), None)
    if matched_browser is None:
        return None, None, window_title
    for keyword, pagename in SITE_KEYWORDS.items():
        if keyword in lower:
            return pagename, matched_browser, window_title
    return None, None, window_title
```

Update the call site at line 102–105 (start of `main`):

```python
def main() -> None:
    hwnd, title = get_active_window()
    pagename, browser, window_title = detect_site(title)
    if pagename is None:
        return
```

- [ ] **Step 7: Smoke-test that the production file still imports cleanly**

Run: `cd scripts && python -c "import screenshot_capture" 2>&1 || python -c "import importlib.util; spec = importlib.util.spec_from_file_location('m', 'screenshot-capture.py'); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); print('import ok')"`
Expected: `import ok` (the filename has a hyphen, so use the `importlib` form)

- [ ] **Step 8: Commit**

```bash
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" add scripts/screenshot-capture.py
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" commit -m "feat: detect_site reports matched browser alongside pagename"
```

---

### Task 2: Add `has_subpath()` pure function

**Files:**
- Modify: `scripts/screenshot-capture.py` (add function near `hash_bytes`, ~line 52)
- Test: `scripts/test_screenshot_logic.py` (add inlined copy + tests)

**Interfaces:**
- Consumes: nothing (pure function, string in)
- Produces: `has_subpath(url: str) -> bool`, used by Task 4's `main()` gating logic.

- [ ] **Step 1: Add failing tests to `scripts/test_screenshot_logic.py`**

Add near the other pure-function tests (after `test_hash_distinct`, before `test_state_roundtrip`):

```python
def test_has_subpath_root_with_slash():
    assert has_subpath("https://www.quora.com/") is False

def test_has_subpath_subpage():
    assert has_subpath("https://www.quora.com/Some-Question-Title") is True

def test_has_subpath_no_dot_com_slash():
    assert has_subpath("https://www.quora.com") is False

def test_has_subpath_query_string_root():
    assert has_subpath("https://www.linkedin.com/?ref=x") is True

def test_has_subpath_linkedin_profile():
    assert has_subpath("https://www.linkedin.com/in/someone/") is True
```

Add the function definition itself alongside the other inlined pure functions (after `hash_bytes`, line 20):

```python
def has_subpath(url: str) -> bool:
    idx = url.rfind(".com/")
    if idx == -1:
        return False
    return len(url[idx + len(".com/"):]) > 0
```

Also add the five new test calls to the `if __name__ == "__main__":` block at the bottom.

- [ ] **Step 2: Run tests to verify they pass**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -q`
Expected: PASS (17 passed)

Since the function and its tests were added together, there's no red state to observe here — the function is a direct transcription of the spec's truth table (see Task list Step 1 above), and Task 1 already demonstrated the red/green cycle for this file's pattern.

- [ ] **Step 3: Commit**

```bash
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" add scripts/test_screenshot_logic.py
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" commit -m "test: add has_subpath pure function with coverage"
```

- [ ] **Step 4: Add the same `has_subpath()` function to `scripts/screenshot-capture.py`**

Insert after `hash_bytes` (after line 53):

```python
def has_subpath(url: str) -> bool:
    idx = url.rfind(".com/")
    if idx == -1:
        return False
    return len(url[idx + len(".com/"):]) > 0
```

- [ ] **Step 5: Smoke-test import**

Run: `cd scripts && python -c "import importlib.util; spec = importlib.util.spec_from_file_location('m', 'screenshot-capture.py'); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); print('import ok')"`
Expected: `import ok`

- [ ] **Step 6: Commit**

```bash
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" add scripts/screenshot-capture.py
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" commit -m "feat: add has_subpath helper for URL sub-page detection"
```

---

### Task 3: Add `get_address_bar_url()` via UI Automation

**Files:**
- Modify: `scripts/screenshot-capture.py` (add import + function near `get_active_window`, ~line 36; add constant near `SITE_KEYWORDS`/`BROWSER_KEYWORDS`, ~line 22-23)
- Modify: `scripts/requirements.txt`

**Interfaces:**
- Consumes: `hwnd: int` (the foreground window handle from `get_active_window()`, already used elsewhere in `main()`)
- Produces: `get_address_bar_url(hwnd: int) -> str | None`, used by Task 4's `main()` gating logic. Also produces `SUBPAGE_CAPABLE_BROWSERS: set[str]` constant.

Not unit-tested — real UIA interaction against a live browser window needs manual verification (same rationale as `DeepseekSummarizeClient`'s subprocess handling per `docs/testing.md`).

- [ ] **Step 1: Add the dependency**

Append to `scripts/requirements.txt`:

```
uiautomation
```

- [ ] **Step 2: Install it in the local environment**

Run: `pip install uiautomation`
Expected: `Successfully installed uiautomation-...`

- [ ] **Step 3: Add the import and constant to `scripts/screenshot-capture.py`**

Add to the imports (after the `win32gui` import, ~line 10):

```python
import uiautomation as auto
```

Add near `BROWSER_KEYWORDS` (~line 23):

```python
SUBPAGE_CAPABLE_BROWSERS = {"chrome", "edge"}
```

- [ ] **Step 4: Add `get_address_bar_url()`**

Insert after `get_active_window()` (~line 39):

```python
def get_address_bar_url(hwnd: int) -> str | None:
    try:
        window = auto.ControlFromHandle(hwnd)
        edit = window.EditControl(Name="Address and search bar")
        if not edit.Exists(0, 0):
            return None
        return edit.GetValuePattern().Value
    except Exception:
        return None
```

- [ ] **Step 5: Smoke-test import**

Run: `cd scripts && python -c "import importlib.util; spec = importlib.util.spec_from_file_location('m', 'screenshot-capture.py'); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); print('import ok')"`
Expected: `import ok`

- [ ] **Step 6: Manually verify against a live browser**

With a Chrome or Edge window open on any page, run a throwaway check from a Python REPL in `scripts/`:

```python
import win32gui
import importlib.util
spec = importlib.util.spec_from_file_location('m', 'screenshot-capture.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
hwnd, title = m.get_active_window()
print(title)
print(m.get_address_bar_url(hwnd))
```

Expected: prints the window title, then the current URL string (not `None`). Switch focus to the Chrome/Edge window before running, since `get_active_window()` reads the foreground window.

- [ ] **Step 7: Commit**

```bash
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" add scripts/screenshot-capture.py scripts/requirements.txt
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" commit -m "feat: read browser address bar URL via UI Automation"
```

---

### Task 4: Wire sub-page gating into `main()`

**Files:**
- Modify: `scripts/screenshot-capture.py:22-23` (add `SUBPAGE_GATED_SITES` constant), `scripts/screenshot-capture.py` `main()` (~line 101, after Task 1's updated call site)

**Interfaces:**
- Consumes: `detect_site()` → `(pagename, browser, window_title)` (Task 1), `has_subpath(url)` (Task 2), `get_address_bar_url(hwnd)` (Task 3), `SUBPAGE_CAPABLE_BROWSERS` (Task 3)
- Produces: final gated `main()` — no new interface for later tasks (this is the last task)

- [ ] **Step 1: Add `SUBPAGE_GATED_SITES` constant**

Add near `SITE_KEYWORDS` (~line 22):

```python
SUBPAGE_GATED_SITES = {"quora", "linkedin"}
```

- [ ] **Step 2: Add the gating check in `main()`, before the screenshot is taken**

Current `main()` (post-Task-1) starts:

```python
def main() -> None:
    hwnd, title = get_active_window()
    pagename, browser, window_title = detect_site(title)
    if pagename is None:
        return
    bmp_bytes = take_screenshot_bmp(hwnd)
```

Insert the gating check between the `if pagename is None: return` line and `bmp_bytes = take_screenshot_bmp(hwnd)`:

```python
def main() -> None:
    hwnd, title = get_active_window()
    pagename, browser, window_title = detect_site(title)
    if pagename is None:
        return
    if pagename in SUBPAGE_GATED_SITES and browser in SUBPAGE_CAPABLE_BROWSERS:
        url = get_address_bar_url(hwnd)
        if url is not None and has_subpath(url):
            return
    bmp_bytes = take_screenshot_bmp(hwnd)
```

- [ ] **Step 3: Smoke-test import**

Run: `cd scripts && python -c "import importlib.util; spec = importlib.util.spec_from_file_location('m', 'screenshot-capture.py'); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); print('import ok')"`
Expected: `import ok`

- [ ] **Step 4: Run the full pure-function test suite one more time**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -q`
Expected: PASS (17 passed) — confirms Tasks 1–2's changes are still intact.

- [ ] **Step 5: Manual end-to-end verification**

1. Open Chrome (or Edge), navigate to `https://www.quora.com/` (root feed).
2. Bring that window to the foreground.
3. Run: `cd scripts && python screenshot-capture.py` — should behave as before capture-wise (check `screenshot-capture-state.json` gets a new `last_hash`, or run with a temporary `print` if needed).
4. Navigate the same window to any Quora question page (a URL with content after `quora.com/`).
5. Run `python screenshot-capture.py` again — it should return immediately without updating `screenshot-capture-state.json`'s hash (no screenshot taken, no OCR, no POST to `/addContent`).
6. Repeat steps 1–5 for `https://www.linkedin.com/` (root/feed) vs. a profile page (`https://www.linkedin.com/in/...`).
7. Confirm Facebook is unaffected: navigate to any Facebook sub-page and confirm the screenshot still fires (no gating applied), matching pre-change behavior.

- [ ] **Step 6: Commit**

```bash
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" add scripts/screenshot-capture.py
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" commit -m "feat: skip screenshots on Quora/LinkedIn sub-pages"
```

---

### Task 5: Exempt LinkedIn feed and Quora topic pages from sub-page gating

**Context:** Discovered during Task 4's manual verification and confirmed with the human operator.
Full rationale: `docs/superpowers/specs/2026-07-14-screenshot-subpage-skip-design.md`, "Addendum:
exemptions for dynamically-loaded pages". LinkedIn's logged-in root redirects to `/feed/`, which
`has_subpath()` correctly (per its own truth table) treats as a sub-page — but `/feed/` is the
LinkedIn home a user actually wants captured, not a sub-page in the sense this feature targets.
Similarly, Quora topic pages (`quora.com/topic/*`) must always be captured because their content
loads dynamically and the Chrome extension can't see it — the screenshot+OCR path is the only way
this content gets indexed.

**Files:**
- Modify: `scripts/screenshot-capture.py` (add `import re` if not already present, add two compiled
  patterns + `is_subpage_exempt()` near `has_subpath()`, update the `main()` gating condition)
- Test: `scripts/test_screenshot_logic.py` (add inlined copy + tests)

**Interfaces:**
- Consumes: `has_subpath(url)` (Task 2) — `is_subpage_exempt()` is checked alongside it, not a
  modification to it.
- Produces: `is_subpage_exempt(url: str) -> bool`, consumed by `main()`'s gating condition (final
  consumer — no later task).

- [ ] **Step 1: Add failing tests to `scripts/test_screenshot_logic.py`**

Add near the `has_subpath` tests:

```python
def test_is_subpage_exempt_linkedin_feed():
    assert is_subpage_exempt("https://www.linkedin.com/feed/") is True

def test_is_subpage_exempt_linkedin_feed_no_trailing_slash():
    assert is_subpage_exempt("https://www.linkedin.com/feed") is True

def test_is_subpage_exempt_linkedin_feed_with_query():
    assert is_subpage_exempt("https://www.linkedin.com/feed/?trk=nav_home") is True

def test_is_subpage_exempt_linkedin_feedback_not_exempt():
    assert is_subpage_exempt("https://www.linkedin.com/feedback/") is False

def test_is_subpage_exempt_linkedin_profile_not_exempt():
    assert is_subpage_exempt("https://www.linkedin.com/in/someone/") is False

def test_is_subpage_exempt_quora_topic():
    assert is_subpage_exempt("https://www.quora.com/topic/Artificial-Intelligence") is True

def test_is_subpage_exempt_quora_topic_nested():
    assert is_subpage_exempt("https://www.quora.com/topic/Artificial-Intelligence/answer/Someone") is True

def test_is_subpage_exempt_quora_question_not_exempt():
    assert is_subpage_exempt("https://www.quora.com/Some-Question-Title") is False

def test_is_subpage_exempt_neither_site():
    assert is_subpage_exempt("https://www.facebook.com/topic/") is False
```

Add the import and function definitions alongside the other inlined pure functions (near
`has_subpath`, and add `import re` at the top of the file if not already imported):

```python
LINKEDIN_FEED_PATTERN = re.compile(r"linkedin\.com/feed/?(?:[?#]|$)")
QUORA_TOPIC_PATTERN = re.compile(r"quora\.com/topic/")

def is_subpage_exempt(url: str) -> bool:
    return bool(LINKEDIN_FEED_PATTERN.search(url)) or bool(QUORA_TOPIC_PATTERN.search(url))
```

Also add the nine new test calls to the `if __name__ == "__main__":` block at the bottom.

- [ ] **Step 2: Run tests to verify they pass**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -q`
Expected: PASS (26 passed)

As with `has_subpath` in Task 2, the function and its tests were written together as a direct
transcription of the spec's truth table — no red state expected here.

- [ ] **Step 3: Commit**

```bash
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" add scripts/test_screenshot_logic.py
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" commit -m "test: add is_subpage_exempt for LinkedIn feed and Quora topic pages"
```

- [ ] **Step 4: Add the same `is_subpage_exempt()` to `scripts/screenshot-capture.py`**

Add `import re` to the top-level imports if not already present. Insert near `has_subpath`:

```python
LINKEDIN_FEED_PATTERN = re.compile(r"linkedin\.com/feed/?(?:[?#]|$)")
QUORA_TOPIC_PATTERN = re.compile(r"quora\.com/topic/")

def is_subpage_exempt(url: str) -> bool:
    return bool(LINKEDIN_FEED_PATTERN.search(url)) or bool(QUORA_TOPIC_PATTERN.search(url))
```

- [ ] **Step 5: Update the gating condition in `main()`**

Current (post-Task-4):

```python
    if pagename in SUBPAGE_GATED_SITES and browser in SUBPAGE_CAPABLE_BROWSERS:
        url = get_address_bar_url(hwnd)
        if url is not None and has_subpath(url):
            return
```

Change to:

```python
    if pagename in SUBPAGE_GATED_SITES and browser in SUBPAGE_CAPABLE_BROWSERS:
        url = get_address_bar_url(hwnd)
        if url is not None and has_subpath(url) and not is_subpage_exempt(url):
            return
```

- [ ] **Step 6: Smoke-test import**

Run: `cd scripts && python -c "import importlib.util; spec = importlib.util.spec_from_file_location('m', 'screenshot-capture.py'); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); print('import ok')"`
Expected: `import ok`

- [ ] **Step 7: Run the full pure-function test suite**

Run: `cd scripts && python -m pytest test_screenshot_logic.py -q`
Expected: PASS (26 passed)

- [ ] **Step 8: Manual end-to-end verification**

1. In a Chrome or Edge window logged into LinkedIn, navigate to `https://www.linkedin.com/` (which
   redirects to `/feed/`). Confirm the screenshot IS attempted (not gated) — this was previously
   gated before this task, so this is the regression this task fixes.
2. Navigate to a Quora topic page (e.g. `https://www.quora.com/topic/Artificial-Intelligence`).
   Confirm the screenshot IS attempted (not gated).
3. Navigate to a LinkedIn profile (e.g. `https://www.linkedin.com/in/someone/`) and a Quora
   question page. Confirm both are STILL gated (skipped) — the exemption must not be so broad it
   swallows the sub-page skip behavior Task 4 built.
4. Navigate to `https://www.linkedin.com/feedback/` if reachable, or reason through the regex
   directly in a Python shell — confirm it is NOT exempt (distinguishing it from `/feed/`).

- [ ] **Step 9: Commit**

```bash
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" add scripts/screenshot-capture.py
git -C "C:\Users\Lenovo\IdeaProjects\digital-me" commit -m "feat: exempt LinkedIn feed and Quora topic pages from sub-page gating"
```
