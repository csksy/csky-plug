"""
Eera Telegram Bridge
====================

A tiny companion server that powers the "Telegram Eera" CloudStream plugin.

It logs into a real Telegram account (MTProto userbot) and exposes a simple
HTTP API so the plugin can:
  * search the @eera_Search_Zone group  (GET  /api/search)
  * request a file from @Movie_world2_bot (GET /api/select) and instantly
    forward it to Saved Messages to beat the 50-second self-destruct timer
  * stream the file progressively         (GET  /api/stream/{fileId})

Why a bridge? The official Telegram Bot API cannot read the group, cannot
message other bots, and cannot search - only a real account (via MTProto /
Telethon) can automate this flow. Keeping it on a small server instead of
inside the plugin avoids bundling ~50MB of native Telegram libraries into a
CloudStream plugin and is far less likely to get the account flagged.

Run it:
    pip install -r requirements.txt
    set API_ID / API_HASH (from my.telegram.org)
    uvicorn main:app --host 0.0.0.0 --port 8000
    open http://localhost:8000/ to log in once with your Telegram account

Optional env:
    TG_SESSION_STRING  - persistent session string (survives restarts)
    BRIDGE_TOKEN       - if set, all /api routes require header X-Bridge-Token
    GROUP_USERNAME     - search group (default eera_Search_Zone)
    BOT_USERNAME       - delivery bot (default Movie_world2_bot)
    DATA_FILE          - where the fileId index is stored (default bridge_data.json)
"""

import asyncio
import json
import logging
import os
import re
import time
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

import uvicorn
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import HTMLResponse, Response
from pydantic import BaseModel

from telethon import TelegramClient
from telethon.errors import SessionPasswordNeededError
from telethon.sessions import StringSession
from telethon.tl.functions.channels import JoinChannelRequest
from telethon.tl.types import (
    MessageEntityTextUrl,
    MessageEntityUrl,
    MessageMediaDocument,
    MessageMediaPhoto,
)

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("eera-bridge")

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

API_ID = int(os.environ.get("API_ID", "0") or 0)
API_HASH = os.environ.get("API_HASH", "").strip()
SESSION_NAME = os.environ.get("TG_SESSION", "eera_bridge")
SESSION_STRING = os.environ.get("TG_SESSION_STRING", "").strip()
GROUP_USERNAME = os.environ.get("GROUP_USERNAME", "eera_Search_Zone").lstrip("@")
BOT_USERNAME = os.environ.get("BOT_USERNAME", "Movie_world2_bot").lstrip("@")
BRIDGE_TOKEN = os.environ.get("BRIDGE_TOKEN", "").strip()
DATA_FILE = os.environ.get("DATA_FILE", "bridge_data.json")

FILE_RE = re.compile(r"📁\s*\[([^\]]+)\]\s*❗?\s*(.+)")

app = FastAPI(title="Eera Telegram Bridge", version="1.0")

# ---------------------------------------------------------------------------
# Telegram client + state
# ---------------------------------------------------------------------------

session = StringSession(SESSION_STRING) if SESSION_STRING else SESSION_NAME
client = TelegramClient(session, API_ID, API_HASH)

_login_lock = asyncio.Lock()
_op_lock = asyncio.Lock()

_pending_phone: str | None = None
_group_entity = None
_bot_entity = None

_index: dict = {}
_index_lock = asyncio.Lock()


def _load_index():
    global _index
    try:
        p = Path(DATA_FILE)
        if p.exists():
            _index = json.loads(p.read_text(encoding="utf-8"))
    except Exception as e:
        log.warning("could not load index: %s", e)


def _save_index():
    try:
        Path(DATA_FILE).write_text(json.dumps(_index), encoding="utf-8")
    except Exception as e:
        log.warning("could not save index: %s", e)


def _check_token(x_token: str | None):
    if BRIDGE_TOKEN and x_token != BRIDGE_TOKEN:
        raise HTTPException(401, "missing or wrong X-Bridge-Token")


def _fmt_size(b):
    if not b:
        return None
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if b < 1024:
            return f"{b:.1f} {unit}" if unit != "B" else f"{b} B"
        b /= 1024
    return f"{b:.1f} PB"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _extract_urls(message) -> list[str]:
    """Pull every hidden/visible link out of a message (clickable titles)."""
    urls = []
    if not message.entities:
        return urls
    text = message.text or message.message or ""
    for ent in message.entities:
        if isinstance(ent, (MessageEntityUrl, MessageEntityTextUrl)):
            u = getattr(ent, "url", None) or text[ent.offset: ent.offset + ent.length]
            if u and u.startswith("http"):
                urls.append(u)
    return urls


def _parse_file_list(message) -> list[dict]:
    """Parse the '📁 [size] ❗ name' lines + attach payloads from links."""
    text = message.text or message.message or ""
    urls = _extract_urls(message)
    items = []
    for line in text.splitlines():
        m = FILE_RE.search(line)
        if not m:
            continue
        items.append({
            "title": m.group(2).strip(),
            "size": m.group(1).strip(),
            "payload": None,
        })
    if items and urls:
        # if there are as many links as files, pair them; else attach the first
        if len(urls) == len(items):
            for it, u in zip(items, urls):
                it["payload"] = u
        else:
            items[0]["payload"] = urls[0]
    # also scan inline buttons for URLs
    try:
        if message.buttons:
            for row in message.buttons:
                for b in row:
                    if getattr(b, "url", None):
                        if items:
                            items[0]["payload"] = items[0]["payload"] or b.url
    except Exception:
        pass
    return items


async def _ensure_chats():
    global _group_entity, _bot_entity
    if _group_entity is None:
        try:
            _group_entity = await client.get_entity(GROUP_USERNAME)
        except Exception as e:
            raise HTTPException(503, f"cannot find group @{GROUP_USERNAME}: {e}")
        try:
            await client(JoinChannelRequest(_group_entity))
            log.info("joined %s", GROUP_USERNAME)
        except Exception as e:
            log.info("join not needed/possible: %s", e)
    if _bot_entity is None:
        try:
            _bot_entity = await client.get_entity(BOT_USERNAME)
        except Exception as e:
            raise HTTPException(503, f"cannot find bot @{BOT_USERNAME}: {e}")


async def _wait_for_bot_reply(chat, since_id, timeout=45, want_media=False):
    """Poll for the newest bot message after `since_id`."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        msgs = await client.get_messages(chat, limit=12)
        for m in msgs:
            if m.id <= since_id:
                continue
            sender = await m.get_sender()
            is_bot = getattr(sender, "bot", False)
            if is_bot:
                if want_media:
                    if m.media and not getattr(m.media, "sticker", False):
                        return m
                else:
                    if m.text and ("📁" in m.text or "RESULTS FOR" in m.text.upper()):
                        return m
        await asyncio.sleep(1.5)
    return None


# ---------------------------------------------------------------------------
# Web UI (login once in a browser)
# ---------------------------------------------------------------------------

HTML = """<!doctype html><html><head><meta charset="utf-8"><title>Eera Bridge</title>
<style>body{font-family:system-ui;max-width:520px;margin:40px auto;padding:0 16px;color:#eee;background:#111}
h1{font-size:22px}input{width:100%;padding:10px;margin:6px 0 14px;border-radius:8px;border:1px solid #333;background:#1c1c1c;color:#eee}
button{width:100%;padding:12px;border:0;border-radius:8px;background:#2f9bff;color:#fff;font-size:15px;cursor:pointer}
.card{background:#191919;padding:20px;border-radius:12px;margin-bottom:16px}
.good{color:#3ddc84}.bad{color:#ff6b6b}code{background:#000;padding:2px 6px;border-radius:4px}</style></head><body>
<h1>🎬 Eera Telegram Bridge</h1>
<div class="card"><h2>Status</h2><div id="status">…</div></div>
<div class="card"><h2>Step 1 — phone</h2><input id="phone" placeholder="+911234567890">
<button onclick="doLogin()">Send code</button></div>
<div class="card"><h2>Step 2 — code</h2><input id="code" placeholder="12345">
<input id="pass" placeholder="2FA password (only if enabled)">
<button onclick="doCode()">Sign in</button></div>
<div class="card"><h2>Test</h2><input id="q" placeholder="Search query, e.g. Inception">
<button onclick="doSearch()">Test search</button><div id="out"></div></div>
<script>
async function j(u,o){const r=await fetch(u,o);const t=await r.text();try{return JSON.parse(t)}catch(e){return t}}
async function st(){const s=await j('/health');document.getElementById('status').innerHTML=
'<span class="'+(s.loggedIn?'good':'bad')+'">'+(s.loggedIn?'Logged in as '+s.userName:s.message||'Not logged in')+'</span>'}
async function doLogin(){const phone=document.getElementById('phone').value;
const r=await j('/api/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({phone})});
alert(r.message||r.detail||JSON.stringify(r));st()}
async function doCode(){const r=await j('/api/login/code',{method:'POST',headers:{'Content-Type':'application/json'},
body:JSON.stringify({code:document.getElementById('code').value,password:document.getElementById('pass').value})});
alert(r.message||r.detail||JSON.stringify(r));st()}
async function doSearch(){const q=document.getElementById('q').value;
const r=await j('/api/search?q='+encodeURIComponent(q));
document.getElementById('out').innerHTML='<pre style="text-align:left;font-size:12px;white-space:pre-wrap">'+JSON.stringify(r,null,2)+'</pre>'}
st()
</script></body></html>"""


@app.get("/", response_class=HTMLResponse)
async def index():
    return HTML


# ---------------------------------------------------------------------------
# API: auth
# ---------------------------------------------------------------------------

class PhoneIn(BaseModel):
    phone: str


class CodeIn(BaseModel):
    code: str
    password: str | None = None


@app.get("/health")
async def health(x_bridge_token: str | None = Header(default=None)):
    me = None
    try:
        if client.is_connected() and await client.is_user_authorized():
            me = await client.get_me()
    except Exception:
        pass
    return {
        "ok": True,
        "loggedIn": bool(me),
        "userName": me.first_name if me else None,
        "message": "logged in" if me else "not logged in",
    }


@app.post("/api/login")
async def api_login(body: PhoneIn, x_bridge_token: str | None = Header(default=None)):
    _check_token(x_bridge_token)
    global _pending_phone
    async with _login_lock:
        if not API_ID or not API_HASH:
            raise HTTPException(500, "API_ID / API_HASH not configured on the server")
        try:
            if not client.is_connected():
                await client.connect()
            await client.send_code_request(body.phone.strip())
            _pending_phone = body.phone.strip()
            return {"status": "code_sent", "message": "Check Telegram for your login code"}
        except Exception as e:
            raise HTTPException(400, f"could not send code: {e}")


@app.post("/api/login/code")
async def api_login_code(body: CodeIn, x_bridge_token: str | None = Header(default=None)):
    _check_token(x_bridge_token)
    global _pending_phone
    async with _login_lock:
        try:
            if not client.is_connected():
                await client.connect()
            phone = _pending_phone
            if phone is None:
                raise HTTPException(400, "call /api/login first")
            try:
                await client.sign_in(phone=phone, code=body.code.strip())
            except SessionPasswordNeededError:
                if not body.password:
                    return {"status": "need_password", "message": "2FA password required"}
                await client.sign_in(password=body.password)
            me = await client.get_me()
            _pending_phone = None
            return {"status": "ready", "message": f"Logged in as {me.first_name}", "user": me.first_name}
        except HTTPException:
            raise
        except Exception as e:
            raise HTTPException(400, f"login failed: {e}")


@app.post("/api/logout")
async def api_logout(x_bridge_token: str | None = Header(default=None)):
    _check_token(x_bridge_token)
    await client.log_out()
    return {"ok": True}


# ---------------------------------------------------------------------------
# API: search
# ---------------------------------------------------------------------------

@app.get("/api/search")
async def api_search(q: str, x_bridge_token: str | None = Header(default=None)):
    _check_token(x_bridge_token)
    q = q.strip()
    if not q:
        raise HTTPException(400, "missing q")
    async with _op_lock:
        try:
            await _ensure_chats()
            sent = await client.send_message(_group_entity, q)
            reply = await _wait_for_bot_reply(_group_entity, since_id=sent.id)
            if reply is None:
                return {"results": [], "message": "no reply from the group bot"}
            items = _parse_file_list(reply)
            if not items:
                # bot replied with something else - surface it for debugging
                return {
                    "results": [],
                    "message": (reply.text or reply.message or "")[:500],
                }
            return {"results": items}
        except HTTPException:
            raise
        except Exception as e:
            log.exception("search failed")
            raise HTTPException(500, f"search failed: {e}")


# ---------------------------------------------------------------------------
# API: select + stream
# ---------------------------------------------------------------------------

class SelectOut(BaseModel):
    fileId: str
    fileName: str | None = None
    size: int | None = None


@app.get("/api/select")
async def api_select(payload: str, x_bridge_token: str | None = Header(default=None)):
    _check_token(x_bridge_token)
    payload = unquote(payload)
    async with _op_lock:
        try:
            await _ensure_chats()

            command = payload
            if payload.startswith("http"):
                u = urlparse(payload)
                qs = parse_qs(u.query)
                start = (qs.get("start") or [None])[0]
                if start:
                    command = f"/start {start}"
                else:
                    raise HTTPException(400, f"deep link has no start payload: {payload}")

            sent = await client.send_message(_bot_entity, command)
            msg = await _wait_for_bot_reply(_bot_entity, since_id=sent.id, want_media=True)
            if msg is None:
                # the bot may have answered with text only - tell the user
                last = await client.get_messages(_bot_entity, limit=5)
                hint = ""
                for m in last:
                    if m.id > sent.id and m.text:
                        hint = m.text[:300]
                        break
                raise HTTPException(404, "bot did not send a file. " + hint)

            # CRITICAL: beat the 50s self-destruct - keep a permanent copy
            saved = await client.forward_messages("me", msg)

            file_id = f"m{saved.id}"
            _index[file_id] = {
                "chat": "me",
                "msg_id": saved.id,
                "name": getattr(msg.file, "name", None),
                "size": getattr(msg.file, "size", None),
            }
            _save_index()

            return SelectOut(
                fileId=file_id,
                fileName=_index[file_id]["name"],
                size=_index[file_id]["size"],
            )
        except HTTPException:
            raise
        except Exception as e:
            log.exception("select failed")
            raise HTTPException(500, f"select failed: {e}")


@app.api_route("/api/stream/{file_id}", methods=["GET", "HEAD"])
async def api_stream(file_id: str, request: Request, x_bridge_token: str | None = Header(default=None)):
    _check_token(x_bridge_token)
    entry = _index.get(file_id)
    if not entry:
        raise HTTPException(404, "unknown fileId")
    try:
        msgs = await client.get_messages(entry["chat"], ids=entry["msg_id"])
        if not msgs:
            raise HTTPException(404, "file message not found")
        msg = msgs[0]
        if not msg.media:
            raise HTTPException(404, "message has no media")
        media = msg.media
        size = getattr(msg.file, "size", None) or entry.get("size") or 0
        mime = getattr(msg.file, "mime_type", None) or "video/mp4"

        rng = request.headers.get("range")
        headers = {
            "Accept-Ranges": "bytes",
            "Content-Type": mime,
            "Content-Disposition": 'inline; filename="video.mp4"',
        }
        if request.method == "HEAD":
            headers["Content-Length"] = str(size)
            return Response(status_code=200, headers=headers)

        if rng:
            m = re.match(r"bytes=(\d*)-(\d*)", rng)
            if not m:
                raise HTTPException(416, "bad range")
            start_s, end_s = m.groups()
            start = int(start_s) if start_s else 0
            end = int(end_s) if end_s else size - 1
            end = min(end, size - 1) if size else end
            length = end - start + 1
            if start >= size:
                raise HTTPException(416, "range out of bounds")
            data = await client.download_file(media, offset=start, limit=length)
            headers.update({
                "Content-Range": f"bytes {start}-{end}/{size}",
                "Content-Length": str(len(data)),
            })
            return Response(content=data, status_code=206, headers=headers)

        headers["Content-Length"] = str(size)
        return Response(content=await client.download_file(media), status_code=200, headers=headers)
    except HTTPException:
        raise
    except Exception as e:
        log.exception("stream failed")
        raise HTTPException(500, f"stream failed: {e}")


# ---------------------------------------------------------------------------
# Startup
# ---------------------------------------------------------------------------

@app.on_event("startup")
async def startup():
    _load_index()
    if not API_ID or not API_HASH:
        log.warning("API_ID / API_HASH are not set - set them from my.telegram.org")
        return
    try:
        if not client.is_connected():
            await client.connect()
        if await client.is_user_authorized():
            me = await client.get_me()
            log.info("bridge ready - logged in as %s", me.first_name)
    except Exception as e:
        log.warning("could not auto-connect: %s", e)


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8000"))
    uvicorn.run(app, host="0.0.0.0", port=port)
