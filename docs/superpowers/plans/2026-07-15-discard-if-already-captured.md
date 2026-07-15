# Discard Extension Content Already Covered by Screenshot Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop storing Chrome-extension-submitted page content for LinkedIn/Facebook/Quora URLs that `scripts/screenshot-capture.py` already covers more completely, while still storing extension content for pages the screenshot pipeline skips (e.g. individual LinkedIn articles/profiles, individual Quora questions).

**Architecture:** A new static utility class, `ScreenshotCoverage`, mirrors `screenshot-capture.py`'s per-site subpage-gating rules (facebook: any subpage; linkedin/quora: only root or their specific exempt subpath pattern) to decide if a URL is "covered." `DefaultDigitalMeStorage.addContent()` calls it as the very first check inside its existing `source.startsWith("http")` branch and, if covered, returns success without writing/indexing/embedding anything. Screenshot submissions are unaffected — their `source` is a window title, never a URL, so they never enter that branch.

**Tech Stack:** Java 19, JUnit 5 (existing project stack — no new dependencies).

## Global Constraints

- Forward-looking only — no backfill/cleanup of already-existing extension-submitted files for these sites.
- No Chrome extension changes — the discard is entirely backend-side.
- No change to `ExclusionRules` (different purpose: search-result filtering for a narrower, always-noisy root-page set) or to `scripts/screenshot-capture.py` itself.
- Coverage matching mirrors `screenshot-capture.py`'s actual capture rules, not a blanket per-domain match:
  - `facebook.com`: any subpage is covered (the script has no subpage gating for facebook).
  - `linkedin.com`: only the bare root or `/feed/` (the script's `LINKEDIN_FEED_PATTERN` — `linkedin\.com/feed/?(?:[?#]|$)`) is covered. Any other LinkedIn subpage (articles, profiles, posts) is **not** covered — those extension submissions must still be stored, since the script skips capturing them entirely.
  - `quora.com`: only the bare root or `/topic/...` (the script's `QUORA_TOPIC_PATTERN` — `quora\.com/topic/`) is covered. Individual question/answer pages are **not** covered — those extension submissions must still be stored.
- Domain matching uses simple substring containment (`url.contains("linkedin.com")`, etc.), matching this codebase's existing convention in `ExclusionRules` (`sourceUrl.contains(".google.")`) rather than introducing proper URI/host parsing.
- A discarded submission still returns `success = true` (silent no-op) — the Chrome extension's `background.js` POST is fire-and-forget and shouldn't need special handling.
- Tests run via: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=<TestClass>` (per `docs/tooling.md` — `mvn` is not on PATH).

---

### Task 1: `ScreenshotCoverage` — coverage-matching logic

**Files:**
- Create: `src/main/java/com/breynisson/router/digitalme/ScreenshotCoverage.java`
- Test: `src/test/java/com/breynisson/router/digitalme/ScreenshotCoverageTest.java`

**Interfaces:**
- Produces: `ScreenshotCoverage.isCovered(String url) -> boolean`, used by Task 2 as the discard check in `DefaultDigitalMeStorage.addContent()`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/breynisson/router/digitalme/ScreenshotCoverageTest.java`:

```java
package com.breynisson.router.digitalme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenshotCoverageTest {

    @Test
    void facebookAnySubpageIsCovered() {
        assertTrue(ScreenshotCoverage.isCovered("https://www.facebook.com/anything"));
    }

    @Test
    void facebookRootIsCovered() {
        assertTrue(ScreenshotCoverage.isCovered("https://www.facebook.com/"));
    }

    @Test
    void linkedinRootIsCovered() {
        assertTrue(ScreenshotCoverage.isCovered("https://www.linkedin.com/"));
    }

    @Test
    void linkedinFeedIsCovered() {
        assertTrue(ScreenshotCoverage.isCovered("https://www.linkedin.com/feed/"));
    }

    @Test
    void linkedinFeedWithQueryIsCovered() {
        assertTrue(ScreenshotCoverage.isCovered("https://www.linkedin.com/feed/?trk=nav_home"));
    }

    @Test
    void linkedinProfileSubpageIsNotCovered() {
        assertFalse(ScreenshotCoverage.isCovered("https://www.linkedin.com/in/someone/"));
    }

    @Test
    void linkedinArticleSubpageIsNotCovered() {
        assertFalse(ScreenshotCoverage.isCovered("https://www.linkedin.com/pulse/some-article"));
    }

    @Test
    void quoraRootIsCovered() {
        assertTrue(ScreenshotCoverage.isCovered("https://www.quora.com/"));
    }

    @Test
    void quoraTopicIsCovered() {
        assertTrue(ScreenshotCoverage.isCovered("https://www.quora.com/topic/Artificial-Intelligence"));
    }

    @Test
    void quoraQuestionSubpageIsNotCovered() {
        assertFalse(ScreenshotCoverage.isCovered("https://www.quora.com/Some-Question-Title"));
    }

    @Test
    void untrackedDomainIsNotCovered() {
        assertFalse(ScreenshotCoverage.isCovered("https://www.example.com/"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=ScreenshotCoverageTest`
Expected: compile failure — `ScreenshotCoverage` does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/breynisson/router/digitalme/ScreenshotCoverage.java`:

```java
package com.breynisson.router.digitalme;

import java.util.regex.Pattern;

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

- [ ] **Step 4: Run test to verify it passes**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=ScreenshotCoverageTest`
Expected: `Tests run: 11, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add src/main/java/com/breynisson/router/digitalme/ScreenshotCoverage.java src/test/java/com/breynisson/router/digitalme/ScreenshotCoverageTest.java
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: add ScreenshotCoverage to identify URLs already captured by screenshot OCR"
```

---

### Task 2: Wire the discard check into `DefaultDigitalMeStorage.addContent()`

**Files:**
- Modify: `src/main/java/com/breynisson/router/digitalme/DefaultDigitalMeStorage.java:47`
- Modify: `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java`

**Interfaces:**
- Consumes: `ScreenshotCoverage.isCovered(String url) -> boolean` (Task 1).

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java` (alongside the existing `@Test` methods, using the same `request(...)`/`cleanupDb(...)` helpers already in the file):

```java
    @Test
    void addContentDiscardsCoveredScreenshotUrl() {
        // Guard against a stray row from a prior failed run of this test (e.g. before this
        // task's fix existed, this URL would have actually been indexed) -- per this file's
        // own no-auto-rollback DB convention (docs/testing.md), delete before asserting.
        cleanupDb("https://www.facebook.com/somepost");

        AddContentRequest req = request("https://www.facebook.com/somepost", "Facebook Post", "some facebook post text");

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertTrue(storage.search("facebook").results().isEmpty());
        assertTrue(TextEntryDao.findByName("https://www.facebook.com/somepost").isEmpty());
    }

    @Test
    void addContentStoresUncoveredLinkedinSubpage() {
        AddContentRequest req = request("https://www.linkedin.com/pulse/some-article", "Some Article", "article body text");

        AddContentResponse response = storage.addContent(req);

        assertTrue(response.isSuccess());
        assertEquals(1, storage.search("article").results().size());
        assertFalse(TextEntryDao.findByName("https://www.linkedin.com/pulse/some-article").isEmpty());

        cleanupDb("https://www.linkedin.com/pulse/some-article");
    }
```

- [ ] **Step 2: Run tests to verify `addContentDiscardsCoveredScreenshotUrl` fails**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=DefaultDigitalMeStorageTest`
Expected: `addContentDiscardsCoveredScreenshotUrl` FAILS (today's code indexes the facebook URL normally, so `search("facebook")` returns a result and `TextEntryDao.findByName(...)` is non-empty) — `addContentStoresUncoveredLinkedinSubpage` passes already (no behavior change needed for that case), confirming the test correctly targets only the new discard behavior. The `cleanupDb(...)` call at the start of the discard test means this failing run doesn't leave stray DB state behind for Step 4 — it deletes whatever row this same run just inserted the *next* time the test runs, not retroactively, so if you re-run the suite before applying Step 3's fix you'll see the same failure, not a stale pass.

- [ ] **Step 3: Wire the check into `addContent()`**

In `src/main/java/com/breynisson/router/digitalme/DefaultDigitalMeStorage.java`, the current method body (lines 41-64) reads:

```java
    @Override
    public AddContentResponse addContent(AddContentRequest addContentRequest) {
        lock.lock();
        AddContentResponse contentResponse = new AddContentResponse();
        try {
            log.info("addContent: {}", addContentRequest.getSource());
            String content = addContentRequest.getContent();
            if (addContentRequest.getSource().startsWith("http")) {
                if (addContentRequest.getSource().startsWith("https://www.youtube.com")) {
                    content = new YouTubeCaptionExtractor().extractFromYouTubeUrl(addContentRequest.getSource());
                } else {
                    content = Jsoup.parse(content).text();
                    content = content.replace("\\n", " ");
                    content = content.replace("\\t", " ");
                    content = content.replace("\\r", " ");
                    content = content.replaceAll("\\s+", " ").strip();
                }
                addContentRequest.setContent(content);
            }
            Path written = resourceReceiver.addContent(addContentRequest);
            CompletableFuture.runAsync(() -> embeddingIndex.indexFile(written));
            LuceneIndex.createOrUpdateIndex(content, addContentRequest.getSource(), addContentRequest.getName());
            TextEntryDao.insertOrUpdate(addContentRequest.getSource());
            contentResponse.setSuccess(true);
        } catch (Exception e) {
            log.error("Error in addContent for {}", addContentRequest.getSource(), e);
            contentResponse.setSuccess(false);
            contentResponse.setErrorMessage(e.getMessage());
        } finally {
            lock.unlock();
        }
        return contentResponse;
    }
```

Change the `if (addContentRequest.getSource().startsWith("http"))` block to add the discard check first:

```java
            if (addContentRequest.getSource().startsWith("http")) {
                if (ScreenshotCoverage.isCovered(addContentRequest.getSource())) {
                    log.info("Discarding extension content already covered by screenshot capture: {}", addContentRequest.getSource());
                    contentResponse.setSuccess(true);
                    return contentResponse;
                }
                if (addContentRequest.getSource().startsWith("https://www.youtube.com")) {
                    content = new YouTubeCaptionExtractor().extractFromYouTubeUrl(addContentRequest.getSource());
                } else {
                    content = Jsoup.parse(content).text();
                    content = content.replace("\\n", " ");
                    content = content.replace("\\t", " ");
                    content = content.replace("\\r", " ");
                    content = content.replaceAll("\\s+", " ").strip();
                }
                addContentRequest.setContent(content);
            }
```

The rest of the method is unchanged. The early `return contentResponse;` is inside the existing `try` block, so `finally { lock.unlock(); }` still runs.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test -Dtest=DefaultDigitalMeStorageTest`
Expected: all tests in the class pass, including both new ones.

- [ ] **Step 5: Run the full test suite**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test`
Expected: all tests pass, no regressions in other test classes (e.g. `IndexPageTest`, which also exercises `addContent` indirectly).

- [ ] **Step 6: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add src/main/java/com/breynisson/router/digitalme/DefaultDigitalMeStorage.java src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "feat: discard extension content for URLs already covered by screenshot capture"
```

---

### Task 3: Document the discard behavior

**Files:**
- Modify: `docs/architecture.md:14`
- Modify: `docs/architecture.md:175` (Screenshot OCR capture section)

**Interfaces:**
- None (documentation only).

- [ ] **Step 1: Update the `/addContent` description**

In `docs/architecture.md`, line 14 currently reads:

```markdown
`/addContent` uses a `ReentrantLock` for thread safety. If `source` starts with `http`, content is stripped to plain text via Jsoup before indexing.
```

Change to:

```markdown
`/addContent` uses a `ReentrantLock` for thread safety. If `source` starts with `http`, content is stripped to plain text via Jsoup before indexing — unless `ScreenshotCoverage.isCovered()` determines the URL is a LinkedIn/Facebook/Quora page already captured more completely by the screenshot OCR pipeline, in which case the submission is silently discarded (still returns success, nothing is written or indexed).
```

- [ ] **Step 2: Cross-reference from the Screenshot OCR capture section**

In `docs/architecture.md`, the "Screenshot OCR capture (`scripts/`)" section's "Dedup" bullet (line 175) currently reads:

```markdown
- **Dedup:** `screenshot-capture-state.json` tracks the last screenshot's hash and OCR'd text; unchanged captures are skipped rather than re-sent.
```

Add a new bullet directly after it (before the existing "Session consolidation" bullet):

```markdown
- **Extension overlap:** the Chrome extension's plain-text page captures for LinkedIn/Facebook/Quora URLs already covered by this pipeline are discarded server-side by `ScreenshotCoverage.isCovered()` (see the `/addContent` description above) — it mirrors this script's own subpage-gating rules (facebook: any page; linkedin/quora: only root or their specific exempt subpath) so pages this script skips (e.g. individual LinkedIn articles, Quora answers) still get stored from the extension.
```

- [ ] **Step 3: Commit**

```bash
git -C /c/Users/Lenovo/IdeaProjects/digital-me add docs/architecture.md
git -C /c/Users/Lenovo/IdeaProjects/digital-me commit -m "docs: document discarding extension content already covered by screenshots"
```

---

### Task 4: Final verification and branch wrap-up

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite one more time**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" test`
Expected: all tests pass.

- [ ] **Step 2: Run Checkstyle**

Run: `cmd //c "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2023.3.5\plugins\maven\lib\maven3\bin\mvn.cmd" checkstyle:check`
Expected: no violations (matches `docs/tooling.md`'s rules — unused imports, equals-avoid-null, etc.).

- [ ] **Step 3: Review the full branch diff**

Run: `git -C /c/Users/Lenovo/IdeaProjects/digital-me diff main...feature/discard_if_already_captured --stat`
Expected: shows changes to `src/main/java/com/breynisson/router/digitalme/ScreenshotCoverage.java` (new), `src/main/java/com/breynisson/router/digitalme/DefaultDigitalMeStorage.java`, `src/test/java/com/breynisson/router/digitalme/ScreenshotCoverageTest.java` (new), `src/test/java/com/breynisson/router/digitalme/DefaultDigitalMeStorageTest.java`, `docs/architecture.md`, plus the design spec and this plan under `docs/superpowers/`.

- [ ] **Step 4: Manual sanity check (optional but recommended)**

With the app running, POST a facebook subpage URL to `/addContent` directly (e.g. via `curl`) and confirm via `/search?keywords=...` that it does not appear, while a POST for an uncovered LinkedIn subpage URL does appear. This confirms the behavior end-to-end through the real HTTP endpoint, not just the unit tests.
