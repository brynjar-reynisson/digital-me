---
name: browser-screenshot-ocr
description: Use when the user asks to screenshot a web page, OCR a browser page into digital-me, or open a site and capture+index its text. Takes a browser name and a URL, and returns the OCR'd text.
argument-hint: [browser] [url]
allowed-tools: PowerShell(Start-Process *) Bash(python scripts/screenshot-capture.py) Read(//c/Users/Lenovo/IdeaProjects/digital-me/scripts/screenshot-capture-state.json)
---

# Browser Screenshot OCR (digital-me)

Opens `$1` in `$0`, then runs the capture script that screenshots the page, OCRs it, and indexes the extracted text into digital-me.

## Steps

1. **Open the page** — launch the browser to the target URL in a new window, using the PowerShell tool directly (matches how this has been done previously in this project):
   ```
   Start-Process $0 -ArgumentList "--new-window","$1"
   ```
2. Wait a few seconds for the page to finish loading before capturing.
3. **Capture + OCR** — run the capture script, from the project root (`C:\Users\Lenovo\IdeaProjects\digital-me`):
   ```bash
   python scripts/screenshot-capture.py
   ```
4. **Return the text** — read `scripts/screenshot-capture-state.json` and report its `last_sent_text` field back to the user as the OCR'd text. If the script skipped the capture (see Notes), this field will still hold whatever text was sent on the *previous* run, not new content — say so rather than presenting it as fresh.

## Notes

- `screenshot-capture.py` takes no CLI arguments — it detects the site from the **currently active/foreground window's title**, so the window opened in step 1 must still be focused when step 3 runs.
- It only recognizes and captures **linkedin, facebook, and quora** pages (`SITE_KEYWORDS` in the script) — for any other site, `detect_site()` returns `None` and the script exits silently without capturing anything.
- It also skips the capture if the screenshot is byte-identical to the last one, or the OCR'd text matches the last text sent — in both cases `screenshot-capture-state.json` is not updated with new text.
- Captured content is POSTed to `http://localhost:8080/addContent`, so the digital-me backend must already be running (see the `build-and-deploy` skill).
- `Start-Process` needs the actual executable name, not the brand name — use `msedge` (not `edge`), `chrome`, or `firefox`. If the browser argument fails with "cannot find the file specified," retry with the executable name.
