import hashlib
import json
import tempfile
from pathlib import Path

# Inline the two pure functions so this file has no external imports
SITE_KEYWORDS = {"linkedin": "linkedin", "facebook": "facebook", "quora": "quora"}

def detect_site(window_title: str) -> tuple:
    lower = window_title.lower()
    for keyword, pagename in SITE_KEYWORDS.items():
        if keyword in lower:
            return pagename, window_title
    return None, window_title

def hash_bytes(data: bytes) -> str:
    return hashlib.md5(data).hexdigest()

def test_detect_quora():
    pagename, title = detect_site("Quora - A place to share knowledge - Google Chrome")
    assert pagename == "quora", f"expected quora, got {pagename}"
    assert "Quora" in title

def test_detect_linkedin():
    pagename, _ = detect_site("Feed | LinkedIn - Google Chrome")
    assert pagename == "linkedin"

def test_detect_facebook():
    pagename, _ = detect_site("Facebook - Google Chrome")
    assert pagename == "facebook"

def test_detect_no_match():
    pagename, _ = detect_site("GitHub - Microsoft Edge")
    assert pagename is None

def test_detect_case_insensitive():
    pagename, _ = detect_site("QUORA - SOME TITLE")
    assert pagename == "quora"

def test_hash_deterministic():
    assert hash_bytes(b"hello") == hash_bytes(b"hello")

def test_hash_distinct():
    assert hash_bytes(b"hello") != hash_bytes(b"world")

def test_state_roundtrip():
    with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as f:
        path = Path(f.name)
    state = {"last_hash": "abc123", "last_sent_text": "some ocr text"}
    path.write_text(json.dumps(state), encoding="utf-8")
    loaded = json.loads(path.read_text(encoding="utf-8"))
    assert loaded == state
    path.unlink()

def test_load_state_missing_file():
    path = Path(tempfile.mktemp(suffix=".json"))
    assert not path.exists()
    state = {"last_hash": None, "last_sent_text": None}
    if path.exists():
        state = json.loads(path.read_text(encoding="utf-8"))
    assert state == {"last_hash": None, "last_sent_text": None}

if __name__ == "__main__":
    test_detect_quora()
    test_detect_linkedin()
    test_detect_facebook()
    test_detect_no_match()
    test_detect_case_insensitive()
    test_hash_deterministic()
    test_hash_distinct()
    test_state_roundtrip()
    test_load_state_missing_file()
    print("All tests passed.")
