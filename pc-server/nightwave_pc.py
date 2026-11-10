#!/usr/bin/env python3
"""Nightwave PC server — play the music that is already on this computer in Nightwave.

Usage:   python nightwave_pc.py "D:\\MUSIC"            (optional: --port 8844)
Needs only Python 3 (no extra installs). Stop it with Ctrl+C.

It shares ONE folder, read-only, with devices on your own Wi-Fi:
  /api/list      the songs in the folder
  /file/<id>     a song (supports seeking)
  /              Nightwave itself, already connected to this PC's music
"""
import argparse, hashlib, json, mimetypes, os, re, socket, sys, threading, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

APP_URL = "https://hackerbones.github.io/sauti/"
AUDIO = {".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac", ".webm"}
JUNK = [r"^\s*\[\s*spotdownloader\.com\s*\]\s*", r"\s*SMS\s*\[\s*Skiza[^\]]*\]\s*to\s*\d+",
        r"\s*[\(\[][^\)\]]*\b(?:official|lyrics?|lyric video|audio|visuali[sz]er|music video|video|explicit)\b[^\)\]]*[\)\]]",
        r"\s*[|_]\s*(?:official\s*)?(?:audio|lyrics?)\s*$"]
tracks, cache, lock = {}, {}, threading.Lock()


def scan(folder):
    found = {}
    for root, _dirs, files in os.walk(folder):
        for name in files:
            stem, ext = os.path.splitext(name)
            if ext.lower() not in AUDIO:
                continue
            path = os.path.join(root, name)
            for pat in JUNK:
                stem = re.sub(pat, "", stem, flags=re.I)
            stem = re.sub(r"\s{2,}", " ", stem).strip(" -_") or name
            m = re.match(r"^(.+?)\s+[-–—]\s+(.+)$", stem)
            artist, title = (m.group(1).strip(), m.group(2).strip()) if m else ("Unknown artist", stem)
            tid = hashlib.sha1(os.path.relpath(path, folder).encode("utf-8", "replace")).hexdigest()[:12]
            found[tid] = {"id": tid, "title": title, "artist": artist, "size": os.path.getsize(path), "path": path}
    return found


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1)); return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):  # keep the window quiet
        pass

    def cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Range")
        self.send_header("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges")
        self.send_header("Access-Control-Allow-Private-Network", "true")

    def send_bytes(self, body, ctype, status=200):
        self.send_response(status); self.cors()
        self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-cache"); self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204); self.cors(); self.send_header("Content-Length", "0"); self.end_headers()

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        path = self.path.split("?")[0]
        try:
            if path == "/api/list":
                with lock:
                    tracks.clear(); tracks.update(scan(self.server.folder))
                    out = sorted(({k: v for k, v in t.items() if k != "path"} for t in tracks.values()), key=lambda t: (t["artist"].lower(), t["title"].lower()))
                return self.send_bytes(json.dumps({"tracks": out}).encode(), "application/json")
            if path.startswith("/file/"):
                return self.send_file(path[6:])
            return self.send_app(path)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def send_file(self, tid):
        t = tracks.get(tid)
        if not t or not os.path.isfile(t["path"]):          # only files found by the scan can be served
            return self.send_bytes(b"Not found", "text/plain", 404)
        size = os.path.getsize(t["path"]); start, end = 0, size - 1
        m = re.match(r"bytes=(\d*)-(\d*)", self.headers.get("Range", ""))
        if m and (m.group(1) or m.group(2)):
            if m.group(1):
                start = int(m.group(1)); end = int(m.group(2)) if m.group(2) else end
            else:
                start = max(0, size - int(m.group(2)))
            if start >= size:
                self.send_response(416); self.cors(); self.send_header("Content-Range", f"bytes */{size}"); self.send_header("Content-Length", "0"); self.end_headers(); return
            end = min(end, size - 1); self.send_response(206); self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        else:
            self.send_response(200)
        self.cors(); self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Type", mimetypes.guess_type(t["path"])[0] or "audio/mpeg")
        self.send_header("Content-Length", str(end - start + 1)); self.end_headers()
        if self.command == "HEAD":
            return
        with open(t["path"], "rb") as f:
            f.seek(start); left = end - start + 1
            while left > 0:
                chunk = f.read(min(256 * 1024, left))
                if not chunk:
                    break
                self.wfile.write(chunk); left -= len(chunk)

    def send_app(self, path):
        """Serve Nightwave itself (fetched from the official site) so a phone can open http://<this-pc>:port/."""
        rel = path.lstrip("/") or "index.html"
        if ".." in rel or not re.match(r"^[\w./-]+$", rel):
            return self.send_bytes(b"Not found", "text/plain", 404)
        if rel not in cache:
            try:
                with urllib.request.urlopen(APP_URL + rel, timeout=15) as r:
                    body, ctype = r.read(), r.headers.get("Content-Type", "application/octet-stream")
            except Exception:
                return self.send_bytes(b"Could not load the app - is this PC online?", "text/plain", 502)
            if rel == "index.html":   # point the app at this PC's music automatically
                body = body.replace(b"<head>", b"<head><script>try{localStorage.getItem('pc')||localStorage.setItem('pc',JSON.stringify(location.origin))}catch(e){}</script>", 1)
            cache[rel] = (body, ctype)
        return self.send_bytes(*cache[rel])


def main():
    ap = argparse.ArgumentParser(description="Share one music folder with Nightwave on your Wi-Fi.")
    ap.add_argument("folder", help="the music folder to share, e.g. D:\\MUSIC")
    ap.add_argument("--port", type=int, default=8844)
    a = ap.parse_args()
    folder = os.path.abspath(a.folder)
    if not os.path.isdir(folder):
        sys.exit(f"Folder not found: {folder}")
    tracks.update(scan(folder))
    srv = ThreadingHTTPServer(("0.0.0.0", a.port), Handler); srv.folder = folder
    url = f"http://{lan_ip()}:{a.port}"
    print(f"\n  Nightwave PC server is running — {len(tracks)} songs in {folder}\n")
    print(f"  On your phone (same Wi-Fi), open:   {url}")
    print(f"  Or in Nightwave: Settings > Music on my PC > {url}")
    print(f"  On this computer:                    http://localhost:{a.port}\n")
    print("  If your phone can't connect, allow Python through Windows Firewall (Private networks).")
    print("  Press Ctrl+C to stop.\n")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("Stopped.")


if __name__ == "__main__":
    main()
