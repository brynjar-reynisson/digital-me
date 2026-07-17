# Capture Google Docs via Screenshot OCR — Design

## Problem

The original ask ("read_gdoc") assumed the file-change watcher could add `.gdoc` files to its watched extensions, parse the JSON body Google Drive writes for cloud-only documents, extract the Drive file ID, and fetch the doc's text via the Google API.

That assumption was tested directly against a real file (`G:\My Drive\summarizing - fast and free online service.gdoc`) and does not hold on this machine's setup. `G:\My Drive` is Google Drive for Desktop's *virtual* drive (streaming mode) — not a real disk. Reading a `.gdoc` file's bytes fails identically across Python (`open(..., 'rb').read()`), .NET (`[System.IO.File]::ReadAllText`), and `cmd /c type`, all raising `ERROR_INVALID_FUNCTION` ("Incorrect function" / "Invalid request code"). `fsutil reparsepoint query` confirms it isn't even a reparse point — the Drive filesystem driver simply refuses raw reads of `.gdoc`/`.gsheet`/`.gslides` placeholder files at the driver level, despite reporting a plausible file size via `stat`. There is no local API that can recover the Drive file ID from the file.

Real Google Drive/Docs API access (OAuth installed-app flow, or a service account) was considered and rejected: OAuth needs a one-time Cloud project/client setup plus ongoing refresh-token handling, and a service account only works for files explicitly shared with its email — both too much ongoing friction for personal use.

## Goal

Get the text of Google Docs the user is actively viewing into digital-me, using an approach that needs no Google API credentials and no readable local file.

## Approach

Reuse the existing screenshot-OCR pipeline (`scripts/screenshot-capture.py`), which already solves this exact problem for LinkedIn, Facebook, and Quora: it detects the active browser window, screenshots it, OCRs the text, and POSTs the result to `/addContent`. Google Docs becomes a fourth recognized site. No new subsystem.

## Non-goals

- No FileChangeWatcher changes — the `.gdoc`-file-watching idea is dropped entirely, not deferred.
- No Google Sheets or Slides support (`.gsheet`/`.gslides` have the same unreadable-placeholder problem, but this branch is scoped to Docs only).
- No Google API/OAuth integration of any kind.
- No backfill of documents visited before this feature ships — forward-looking only, same as the existing screenshot pipeline.

## Changes

### `scripts/screenshot-capture.py`

- `SITE_KEYWORDS`: add `"google docs": "google-docs"`. Chrome/Edge window titles for an open document are `"<Doc Title> - Google Docs - Google Chrome"`; the docs.google.com homepage/file-picker title is just `"Google Docs - Google Chrome"` — both match on the `"google docs"` substring, so `detect_site()` alone can't distinguish them. That distinction is handled below via the URL.
- `CROP_CONTENT_SITES`: add `"google-docs"`. Reuses the existing UIA landmark-based cropping (`get_main_content_rect()`) to cut Docs' top toolbar/menu bar and left outline sidebar out of the OCR'd region. This is untested against Google Docs' accessibility tree specifically, but every fallback in that path already fails open — no landmark found → full document rect; document rect not found → `None` → full window screenshot — so enabling it carries no risk beyond "cropping might not happen."
- New pattern: `GOOGLE_DOCS_DOCUMENT_PATTERN = re.compile(r"docs\.google\.com/document/d/")`.
- New pure helper (tested directly, same style as `has_subpath()`/`is_subpage_exempt()`):
  ```python
  def is_non_document_google_docs_page(pagename: str, url: str | None) -> bool:
      return pagename == "google-docs" and url is not None and not GOOGLE_DOCS_DOCUMENT_PATTERN.search(url)
  ```
- In `main()`, alongside the existing `SUBPAGE_GATED_SITES` skip check:
  ```python
  if is_non_document_google_docs_page(pagename, url):
      return
  ```
  Behavior: when the URL is readable (Chrome/Edge — `UIA_CAPABLE_BROWSERS`) and it's the homepage/file-picker rather than an open document, skip the capture. When the URL can't be read at all (non-Chromium browser, or a UIA lookup failure), fall back to capturing on the title match alone — the same graceful-degradation shape `get_address_bar_url()`'s callers already use elsewhere in this file.
- No changes needed to session consolidation (`derive_session_key`, `merge_session_lines`), idle-flush, hash-based dedup-skip, or the `/addContent` POST shape (`flush_session`) — all already generic across sites. A Google Docs session is keyed by its `/document/d/<id>/...` URL like any other site.

### `src/main/java/com/breynisson/router/digitalme/ScreenshotCoverage.java`

Add `docs.google.com` as unconditionally covered (same treatment as `facebook.com` — no subpath gating):

```java
public static boolean isCovered(String url) {
    if (url.contains("facebook.com")) {
        return true;
    }
    if (url.contains("docs.google.com")) {
        return true;
    }
    ...
```

Rationale: the Chrome extension's `content-script.js` fires on every `http(s)` page load, including `docs.google.com`, and POSTs `document.body.innerHTML` to `/addContent`. Google Docs renders its content via canvas, not real DOM text, so that submission is expected to be near-empty/junk. Discarding it server-side (as already happens for LinkedIn/Facebook/Quora) prevents that junk from sitting alongside the real OCR'd capture under a different `source` value. Unlike LinkedIn/Quora, there's no known "safe" Docs subpath worth preserving from the extension, so — like Facebook — the whole domain is covered unconditionally rather than gated by URL shape.

## Data flow

1. Poll loop (every 3s, per `screenshot-capture.ps1`) reads the active window title.
2. `detect_site()` matches `"google docs"` → pagename `"google-docs"`.
3. If Chrome/Edge, `get_address_bar_url()` reads the URL. `is_non_document_google_docs_page()` skips the capture if it's not an open document.
4. `get_main_content_rect()` attempts to crop to the document body (falls back to full window on any failure).
5. Screenshot → OCR → merged into the per-URL session (same consolidation logic as other sites) → flushed to `/addContent` on tab switch or 120s idle.
6. Backend (`DefaultDigitalMeStorage.addContent()`): source is the browser window title (not a URL, matching the existing pattern for all screenshot-sourced content), so it never enters the `startsWith("http")` branch and `ScreenshotCoverage` doesn't apply to it — it's indexed directly like any other non-URL source.
7. Separately, the Chrome extension's own `docs.google.com` submission (source = the actual URL) hits the `startsWith("http")` branch, matches `ScreenshotCoverage.isCovered()`, and is discarded.

## Error handling

No new failure modes. Every branch this feature touches already has an existing fallback in the surrounding code: landmark-not-found → full doc rect, doc-rect-not-found → full window, URL-unreadable → title-only capture (no document/homepage filtering). If Tesseract or the UIA calls throw, that's pre-existing behavior unrelated to this change.

## Testing

- `scripts/test_screenshot_logic.py` (inlined pure-function style, no imports of the real script): add `detect_site()` cases for `"My Document - Google Docs - Google Chrome"` → `("google-docs", "chrome", ...)`, and `is_non_document_google_docs_page()` cases — open document URL → `False`; homepage URL (`https://docs.google.com/document/u/0/`) → `True`; homepage title with `url=None` → `False` (falls back to capturing).
- `ScreenshotCoverageTest.java`: add a case for `https://docs.google.com/document/d/abc123/edit` → covered, and `https://docs.google.com/` → covered (unconditional, matching the Facebook precedent — no subpath gating for this domain).

## Docs

Update `docs/architecture.md`: the "Screenshot OCR capture" section gets a note that Google Docs is a fourth recognized site (crop-content, URL-gated to actual documents); the `ScreenshotCoverage` bullet in the `/addContent` description gets `docs.google.com` added alongside `facebook.com`/`quora.com`. Per CLAUDE.md, this happens as part of the feature branch commit, not deferred.
