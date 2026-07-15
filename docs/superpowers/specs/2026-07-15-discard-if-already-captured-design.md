# Discard Extension Content Already Covered by Screenshot Capture — Design

## Problem

The Chrome extension's `content-script.js`/`background.js` POSTs plain-text page content to `/addContent` for every `http(s)://` page the user visits, including LinkedIn, Facebook, and Quora pages. `scripts/screenshot-capture.py` separately (and, since the session-consolidation feature, much more completely — one merged file per browsing session instead of raw page-load text) OCR-captures those same three sites. For pages the screenshot pipeline already covers, the extension's plain-text submission is redundant clutter in `mcp-resources/` and duplicate/lower-quality search hits, e.g. `15-19-04-19-https___www.linkedin.com_feed_.txt` (extension) alongside `15-19-08-24-screenshot_linkedin_20260715_190420.txt` (screenshot, far more complete).

## Goal

When the Chrome extension submits content for a URL that the screenshot pipeline already captures, discard it silently at ingestion — don't write it to `mcp-resources/`, don't index it in Lucene, don't embed it. Screenshot submissions themselves are unaffected.

## Non-goals

- No backfill/cleanup of already-existing extension-submitted files for these sites (e.g. the LinkedIn feed example above stays as-is). Forward-looking only.
- No Chrome extension changes — the discard happens entirely in the Java backend.
- No change to `ExclusionRules` (it governs search-result filtering for a different, narrower set of always-noisy root pages — `facebook.com`/`facebook.com/`, `quora.com`/`quora.com/` exactly — and stays as-is).
- No change to `scripts/screenshot-capture.py` itself.

## Coverage matching

A URL counts as "covered by screenshot capture" — and its extension-submitted twin should be discarded — using the *same* per-site rules `screenshot-capture.py`'s subpage gating already enforces, not a blanket per-domain match:

- **facebook.com**: any subpage. The Python script has no subpage gating for facebook (`SUBPAGE_GATED_SITES = {"quora", "linkedin"}` excludes it) — every facebook.com page is captured.
- **linkedin.com**: only the bare root (no path after `.com/`) or `/feed/` (the script's `LINKEDIN_FEED_PATTERN`). Any other LinkedIn subpage (articles, profiles, individual posts, notifications) is skipped by the script's subpage gating, so the extension's submission for those must **not** be discarded — it's the only copy of that content.
- **quora.com**: only the bare root or `/topic/...` (the script's `QUORA_TOPIC_PATTERN`). Individual question/answer pages are skipped by the script, so those extension submissions must **not** be discarded either.

This mirrors `screenshot-capture.py`'s `has_subpath()` + `is_subpage_exempt()` + `SUBPAGE_GATED_SITES` logic. There is no way to share code between the Python script and the Java backend, so this is a deliberate, documented duplication — a comment must note it needs to stay in sync with the Python script if that gating logic ever changes.

## New class: `ScreenshotCoverage`

`src/main/java/com/breynisson/router/digitalme/ScreenshotCoverage.java`, alongside `ExclusionRules.java` (same package, same static-utility style):

```java
public final class ScreenshotCoverage {

    private ScreenshotCoverage() {
    }

    // Mirrors scripts/screenshot-capture.py's LINKEDIN_FEED_PATTERN. Keep in sync if that changes.
    private static final Pattern LINKEDIN_FEED_PATTERN = Pattern.compile("linkedin\\.com/feed/?(?:[?#]|$)");
    // Mirrors scripts/screenshot-capture.py's QUORA_TOPIC_PATTERN. Keep in sync if that changes.
    private static final Pattern QUORA_TOPIC_PATTERN = Pattern.compile("quora\\.com/topic/");

    public static boolean isCovered(String url) {
        if (url.contains("facebook.com")) {
            return true;
        }
        if (url.contains("linkedin.com")) {
            return !hasSubpath(url, "linkedin.com/") || LINKEDIN_FEED_PATTERN.matcher(url).find();
        }
        if (url.contains("quora.com")) {
            return !hasSubpath(url, "quora.com/") || QUORA_TOPIC_PATTERN.matcher(url).find();
        }
        return false;
    }

    // Mirrors scripts/screenshot-capture.py's has_subpath(). Keep in sync if that changes.
    private static boolean hasSubpath(String url, String domainSlash) {
        int idx = url.indexOf(domainSlash);
        if (idx == -1) {
            return false;
        }
        return url.length() > idx + domainSlash.length();
    }
}
```

## Wiring into `DefaultDigitalMeStorage.addContent()`

Add the check as the very first thing inside the existing `if (addContentRequest.getSource().startsWith("http"))` branch (`src/main/java/com/breynisson/router/digitalme/DefaultDigitalMeStorage.java`), before the YouTube/Jsoup branch — so it skips Jsoup parsing, the file write, Lucene indexing, and embedding entirely:

```java
if (addContentRequest.getSource().startsWith("http")) {
    if (ScreenshotCoverage.isCovered(addContentRequest.getSource())) {
        log.info("Discarding extension content already covered by screenshot capture: {}", addContentRequest.getSource());
        contentResponse.setSuccess(true);
        return contentResponse;
    }
    if (addContentRequest.getSource().startsWith("https://www.youtube.com")) {
        ...
```

The response is still `success = true` (silent no-op) since `background.js`'s fetch is fire-and-forget and shouldn't need special handling for this case. The early `return` happens inside the method's existing `try` block, so the `finally { lock.unlock(); }` still runs correctly.

Screenshot submissions are unaffected: their `source` is a browser window title (e.g. `"Feed | LinkedIn and 21 more pages - ... - Microsoft Edge"`), which never starts with `"http"`, so they never enter this branch at all.

## Testing

A new `ScreenshotCoverageTest` (JUnit 5, matching `docs/testing.md` conventions) covering:
- `https://www.facebook.com/anything` → covered
- `https://www.facebook.com/` → covered
- `https://www.linkedin.com/` → covered (root, no subpath)
- `https://www.linkedin.com/feed/` → covered
- `https://www.linkedin.com/feed/?trk=nav_home` → covered
- `https://www.linkedin.com/in/someone/` → **not** covered (profile subpage)
- `https://www.linkedin.com/pulse/some-article` → **not** covered (article subpage)
- `https://www.quora.com/` → covered (root)
- `https://www.quora.com/topic/Artificial-Intelligence` → covered
- `https://www.quora.com/Some-Question-Title` → **not** covered (question subpage)
- `https://www.example.com/` → not covered (untracked domain)

`DefaultDigitalMeStorage.addContent()`'s wiring is exercised by adding tests to the existing `DefaultDigitalMeStorageTest` (`src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java`, which already covers plain-text indexing, HTML stripping, and update-in-place using its `request(source, name, content)` helper and `storage.search(...)`/`TextEntryDao.findByName(...)` assertions). Add a case verifying a covered URL (e.g. `https://www.facebook.com/somepost`) returns `success = true` but produces zero search results and no `TextEntryDao` entry, and a case verifying an uncovered LinkedIn subpage (e.g. `https://www.linkedin.com/pulse/some-article`) still gets indexed normally, unchanged from today's behavior.

## Docs

Update `docs/architecture.md`'s `/addContent` description (currently: "If `source` starts with `http`, content is stripped to plain text via Jsoup before indexing") to note the new discard-if-covered check, and add a short note to the "Screenshot OCR capture" section cross-referencing it, per CLAUDE.md's requirement to update docs when committing a feature branch.
