"""Index Wumo comic strips into digital-me.

For each day, fetches https://wumo.com/wumo/YYYY/MM/DD, downloads the main strip
image, asks DeepSeek (via the opencode CLI) to transcribe the text and describe
the scenery (never to explain the joke), and POSTs the result to /addContent
with the page URL as the source.

Resumable: finished dates are recorded in <data-dir>/comics/wumo/state.json.

Usage:
    python -I scripts/comic-index.py --start 2012-12-06 --days 365
"""
import argparse
import datetime
import json
import re
import shutil
import subprocess
import sys
import threading
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

BASE_URL = "https://wumo.com/wumo/{y:04d}/{m:02d}/{d:02d}"
SITE_ROOT = "https://wumo.com"
USER_AGENT = "Mozilla/5.0"
DEFAULT_DATA_DIR = r"C:\Users\Lenovo\DigitalMe"
DEFAULT_MODEL = "deepseek/deepseek-flash"
OG_IMAGE_RE = re.compile(r'og:image"\s+content="([^"]+)"')
RESULT_RE = re.compile(r"TEXT:(.*?)SCENE:(.*)", re.DOTALL)

# Single line, and free of characters cmd.exe treats specially (& | < > ^ " %):
# opencode.cmd runs through cmd.exe, which truncates arguments at a newline.
PROMPT = (
    "Look at this cartoon strip. First write a line starting with TEXT: followed by all "
    "the text in the strip transcribed exactly in reading order, with separate speech "
    "bubbles separated by ' / '. Then write a line starting with SCENE: followed by 2-3 "
    "sentences describing the setting, the characters, and the objects visible. "
    "Do not explain the joke and do not interpret the humor."
)

state_lock = threading.Lock()


def http_get(url, timeout=30):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def find_image_url(html):
    match = OG_IMAGE_RE.search(html)
    if not match:
        return None
    url = match.group(1)
    return SITE_ROOT + url if url.startswith("/") else url


def describe(image_path, opencode, model, timeout):
    """Run opencode with the image attached; return (text, scene) or None."""
    # -f is an array option, so the prompt must come before it.
    proc = subprocess.run(
        [opencode, "run", PROMPT, "--model", model, "--format", "json", "-f", str(image_path)],
        stdin=subprocess.DEVNULL, capture_output=True, text=True,
        encoding="utf-8", timeout=timeout,
    )
    answer = None
    for line in proc.stdout.splitlines():
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if event.get("type") == "text":
            answer = event["part"]["text"]
    if not answer:
        print(f"  no answer (exit {proc.returncode}): {proc.stdout[-300:]!r} {proc.stderr[-300:]!r}", flush=True)
        return None
    match = RESULT_RE.search(answer)
    if not match:
        print(f"  unparseable answer: {answer[:300]!r}", flush=True)
        return None
    return " ".join(match.group(1).split()), " ".join(match.group(2).split())


def post_content(server, source, name, content):
    body = json.dumps({"source": source, "name": name, "content": content}).encode("utf-8")
    req = urllib.request.Request(
        server + "/addContent", data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.status == 200


def process_day(day, args, image_dir):
    """Returns 'done', 'missing' (no strip that day) or 'failed'."""
    page_url = BASE_URL.format(y=day.year, m=day.month, d=day.day)
    try:
        image_url = find_image_url(http_get(page_url).decode("utf-8", errors="replace"))
    except urllib.error.HTTPError as e:
        return "missing" if e.code == 404 else "failed"
    if not image_url:
        return "missing"
    image_path = image_dir / (day.isoformat() + Path(image_url).suffix)
    if not image_path.exists():  # failed days are retried; don't re-download
        image_path.write_bytes(http_get(image_url))
    result = describe(image_path, args.opencode, args.model, args.timeout)
    if result is None:
        return "failed"
    text, scene = result
    content = (f"Wumo cartoon {day.isoformat()}\n\n"
               f"Text in the cartoon: {text}\n\n"
               f"Scene: {scene}")
    if not post_content(args.server, page_url, f"Wumo {day.isoformat()}", content):
        return "failed"
    return "done"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--start", required=True, help="first date, YYYY-MM-DD")
    parser.add_argument("--days", type=int, required=True)
    parser.add_argument("--data-dir", default=DEFAULT_DATA_DIR)
    parser.add_argument("--server", default="http://localhost:8080")
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--workers", type=int, default=3)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--opencode", default=shutil.which("opencode.cmd") or "opencode.cmd")
    args = parser.parse_args()

    out_dir = Path(args.data_dir) / "comics" / "wumo"
    out_dir.mkdir(parents=True, exist_ok=True)
    state_path = out_dir / "state.json"
    state = json.loads(state_path.read_text()) if state_path.exists() else {}

    start = datetime.date.fromisoformat(args.start)
    days = [start + datetime.timedelta(n) for n in range(args.days)]
    pending = [d for d in days if state.get(d.isoformat()) not in ("done", "missing")]
    print(f"{len(days) - len(pending)} already finished, {len(pending)} to do", flush=True)

    def work(day):
        try:
            status = process_day(day, args, out_dir)
        except Exception as e:  # keep the run going; failed days are retried next run
            print(f"{day} error: {e}", flush=True)
            status = "failed"
        with state_lock:
            if status != "failed":
                state[day.isoformat()] = status
                state_path.write_text(json.dumps(state, indent=1))
        print(f"{day} {status}", flush=True)
        return status

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = list(pool.map(work, pending))
    print({s: results.count(s) for s in set(results)}, flush=True)
    return 1 if "failed" in results else 0


if __name__ == "__main__":
    sys.exit(main())
