# Screenshot Sub-Page Skip — Design Spec
Date: 2026-07-14

## Problem

`scripts/screenshot-capture.py` gates screenshot capture only on the browser window title (e.g. `"Quora - A place to share knowledge - Google Chrome"`). It has no visibility into the actual page URL, so it cannot distinguish the Quora/LinkedIn feed root from a sub-page (e.g. an individual question or profile). Sub-pages don't need to be captured.

## Solution Overview

Extend `screenshot-capture.py` to read the real URL from the browser's address bar via Windows UI Automation (UIA), but only for Chrome and Edge — both Chromium-based, both expose the address bar as an `EditControl` named `"Address and search bar"`. If the URL for a Quora or LinkedIn tab has content after the last `.com/`, skip the screenshot entirely (before capture/OCR, not just before sending). Any failure to read the URL (control not found, exception, unsupported browser) falls back to today's behavior — take the screenshot.

Facebook and non-Chromium browsers (Firefox, Opera, Brave) are unaffected — they keep the current title-only gating with no path check.

---

## Changes to `scripts/screenshot-capture.py`

### `detect_site()` — extended return signature

Changes from `(pagename, window_title)` to `(pagename, browser, window_title)`, where `browser` is the matched entry from `BROWSER_KEYWORDS` (e.g. `"chrome"`, `"edge"`, `"firefox"`) or `None` if `pagename` is `None`. This lets `main()` know whether the URL-based check applies without re-scanning the title.

### `SUBPAGE_GATED_SITES` (new constant)

```python
SUBPAGE_GATED_SITES = {"quora", "linkedin"}
```

### `SUBPAGE_CAPABLE_BROWSERS` (new constant)

```python
SUBPAGE_CAPABLE_BROWSERS = {"chrome", "edge"}
```

### `get_address_bar_url(hwnd)` (new)

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

Uses the `uiautomation` package. Broad exception catch is intentional — any UIA failure (control renamed by a browser update, fullscreen mode hiding the address bar, timing issues) degrades to "URL unknown," not a crash.

### `has_subpath(url)` (new, pure)

```python
def has_subpath(url: str) -> bool:
    idx = url.rfind(".com/")
    if idx == -1:
        return False
    return len(url[idx + len(".com/"):]) > 0
```

- `https://www.quora.com/` → `False` (root, capture)
- `https://www.quora.com/Some-Question-Title` → `True` (sub-page, skip)
- `https://www.quora.com` (no trailing slash) → `False` (no `.com/` substring, safe default: capture)
- Query-string-only roots (e.g. `linkedin.com/?ref=x`) are treated as having a sub-path (skip) — accepted edge case, not worth special-casing.

### `main()` — new gating step

After `detect_site()` returns `(pagename, browser, window_title)`, and before `take_screenshot_bmp()`:

```python
if pagename in SUBPAGE_GATED_SITES and browser in SUBPAGE_CAPABLE_BROWSERS:
    url = get_address_bar_url(hwnd)
    if url is not None and has_subpath(url):
        return
```

Placed before the screenshot is taken (not just before sending), so sub-pages skip the screenshot + OCR cost entirely, not just the network call.

---

## Dependencies

Add to `scripts/requirements.txt`:
```
uiautomation
```

---

## Tests (`scripts/test_screenshot_logic.py`)

- Update the file's inlined copy of `detect_site` to the new 3-tuple return signature; update existing assertions accordingly (e.g. `test_detect_quora` now unpacks `pagename, browser, title` and additionally asserts `browser == "chrome"`).
- Add pure-function tests for `has_subpath`:
  - root with trailing slash → `False`
  - sub-page path → `True`
  - no `.com/` substring → `False`
  - query-string-only root → `True`
- `get_address_bar_url` is not unit-tested — real UIA interaction against a live browser window needs manual verification, consistent with how `DeepseekSummarizeClient`'s subprocess handling is excluded from automated tests (see `docs/testing.md`).

---

## Out of Scope

- Firefox, Opera, Brave path gating (title-only gating unchanged for these).
- Facebook path gating (unaffected by this change).
- Non-English browser UI locales (the address bar's accessible name is only guaranteed to be `"Address and search bar"` in English; consistent with the existing English-only OCR limitation).
- Detecting sub-pages when the browser is in fullscreen/immersive mode with no visible address bar (falls back to capturing, per the "unknown URL → capture" rule).
