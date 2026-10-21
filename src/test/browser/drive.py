"""Drive a page in a real (headless) Firefox through its Marionette protocol -- real pointer and key events, the
page's own console errors. T4-EC stage 4: the merge canvas's acts are checked this way before the user is asked to
try them (slice 2 shipped with every act working in tests and none usable by hand). No dependencies beyond Firefox."""
import json, socket, subprocess, tempfile, time, sys

class Marionette:
    def __init__(self, port=2828):
        self.profile = tempfile.mkdtemp()
        with open(self.profile + "/user.js", "w") as f:
            f.write('user_pref("marionette.port", %d);\n' % port)
        self.proc = subprocess.Popen(["firefox", "--headless", "--marionette", "--no-remote", "--profile", self.profile,
                                      "--window-size=1600,1000"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(60):
            try:
                self.sock = socket.create_connection(("127.0.0.1", port), timeout=30); break
            except OSError:
                time.sleep(0.5)
        self.buf = b""; self.id = 0
        self._read()
        self.cmd("WebDriver:NewSession", {"capabilities": {}})

    def _read(self):
        while b":" not in self.buf: self.buf += self.sock.recv(65536)
        n, rest = self.buf.split(b":", 1); n = int(n)
        while len(rest) < n: rest += self.sock.recv(65536)
        self.buf = rest[n:]
        return json.loads(rest[:n])

    def cmd(self, name, params=None):
        self.id += 1
        msg = json.dumps([0, self.id, name, params or {}]).encode()
        self.sock.sendall(str(len(msg)).encode() + b":" + msg)
        while True:
            r = self._read()
            if r[0] == 1 and r[1] == self.id:
                if r[2]: raise RuntimeError(r[2])
                return r[3]

    def js(self, script, *args):
        return self.cmd("WebDriver:ExecuteScript", {"script": script, "args": list(args)}).get("value")

    def close(self):
        try: self.cmd("WebDriver:DeleteSession")
        except Exception: pass
        self.proc.terminate()

CTRL, SHIFT = "", ""
class Page:
    def __init__(self, url):
        self.m = Marionette()
        self.m.cmd("WebDriver:Navigate", {"url": url}); time.sleep(4)
        self.m.js("window.__errs=[]; window.addEventListener('error', e => window.__errs.push(String(e.message)));"
                  "window.addEventListener('unhandledrejection', e => window.__errs.push('rejection: ' + e.reason));")
    def js(self, s, *a): return self.m.js(s, *a)
    def centre(self, title):
        return self.js("const t=[...document.querySelectorAll('g.node title')].find(t=>t.textContent===arguments[0]);"
                       "const r=t.parentNode.querySelector('circle').getBoundingClientRect(); return [Math.round(r.x+r.width/2), Math.round(r.y+r.height/2)]", title)
    def pointer(self, steps, keys=()):
        acts = [{"type": "pointer", "id": "mouse", "parameters": {"pointerType": "mouse"}, "actions": steps}]
        if keys:
            acts.insert(0, {"type": "key", "id": "kb", "actions": [{"type": "keyDown", "value": k} for k in keys]
                            + [{"type": "pause", "duration": 0}] * (len(steps) - len(keys))})
        self.m.cmd("WebDriver:PerformActions", {"actions": acts}); self.m.cmd("WebDriver:ReleaseActions"); time.sleep(0.6)
    def click_at(self, xy, keys=()):
        self.pointer([{"type": "pointerMove", "x": xy[0], "y": xy[1], "origin": "viewport"},
                      {"type": "pointerDown", "button": 0}, {"type": "pointerUp", "button": 0}], keys)
    def drag(self, a, b, keys=()):
        self.pointer([{"type": "pointerMove", "x": a[0], "y": a[1], "origin": "viewport"}, {"type": "pointerDown", "button": 0},
                      {"type": "pointerMove", "x": b[0], "y": b[1], "origin": "viewport", "duration": 300},
                      {"type": "pointerUp", "button": 0}], keys)
    def selected(self): return self.js("return [...document.querySelectorAll('g.node.selected title')].map(t=>t.textContent)")
    def status(self): return self.js("return document.getElementById('status').textContent")
    def disabled(self, b): return self.js("return document.getElementById(arguments[0]).disabled", b)
    def errors(self): return self.js("return window.__errs")
    def close(self): self.m.close()
