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
# Telethon requires non-empty api credentials at construction time; when they
# are missing (local testing / misconfigured deploy) fall back to placeholders
# so the web UI can still run and report the problem.
client = TelegramClient(session, API_ID or 1, API_HASH or "not-set")

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


def _is_bot_link(url: str) -> bool:
    """Only treat links that look like the delivery-bot deep link as payloads.
    Anything else (header links, channel links, support groups) is noise."""
    if BOT_USERNAME.lower() in url.lower():
        return True
    if "/start" in url and "t.me/" in url:
        return True
    if "start=" in url and "t.me/" in url:
        return True
    return False


def _parse_file_list(message) -> list[dict]:
    """Parse the '📁 [size] ❗ name' lines + attach payloads from links.

    Payloads come from URL/button entities that point at the delivery bot
    (deep links like t.me/Movie_world2_bot?start=...).
    """
    text = message.text or message.message or ""
    urls = [u for u in _extract_urls(message) if _is_bot_link(u)]
    try:
        if message.buttons:
            for row in message.buttons:
                for b in row:
                    u = getattr(b, "url", None)
                    if u and _is_bot_link(u):
                        urls.append(u)
    except Exception:
        pass

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
    if not items:
        return items

    unique_urls = list(dict.fromkeys(urls))
    if len(unique_urls) == len(items):
        for it, u in zip(items, unique_urls):
            it["payload"] = u
    elif unique_urls:
        # best effort: attach the first bot deep link to the first entry
        items[0]["payload"] = unique_urls[0]
    # fall back to the file name as payload - the plugin uses title if
    # payload is empty anyway
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
<div class="card"><h2>Settings</h2><div id="cfg">…</div><p style="font-size:13px;color:#aaa">Group: <code id="cfgGroup">?</code> · Bot: <code id="cfgBot">?</code></p></div>
<div class="card"><h2>Session persistence (optional)</h2>
<p style="font-size:13px;color:#aaa">Free hosts wipe their disk on redeploys, which logs you out. Copy the session
string below and set it as the <code>TG_SESSION_STRING</code> env var on your host, then redeploy —
you stay logged in forever.</p>
<button onclick="doSession()">Get session string</button>
<div id="sess" style="margin-top:8px"></div></div>
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
async function doSession(){const r=await j('/api/session');
document.getElementById('sess').innerHTML='<pre style="font-size:11px;white-space:pre-wrap;word-break:break-all">'+(r.session?r.session:JSON.stringify(r))+'</pre>'}
async function cfg(){const c=await j('/api/config');document.getElementById('cfg').innerHTML=
'API configured: <span class="'+(c.apiConfigured?'good':'bad')+'">'+(c.apiConfigured?'yes':'NO — set API_ID / API_HASH')+'</span> · persistent session: '+(c.sessionPersistent?'yes':'no');
document.getElementById('cfgGroup').textContent='@'+c.group;document.getElementById('cfgBot').textContent='@'+c.bot}
st();cfg()
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


@app.get("/api/config")
async def api_config():
    """Public, safe config info (no secrets) for the web UI / debugging."""
    return {
        "group": GROUP_USERNAME,
        "bot": BOT_USERNAME,
        "apiConfigured": bool(API_ID and API_HASH),
        "sessionPersistent": bool(SESSION_STRING),
    }


@app.get("/api/session")
async def api_session(x_bridge_token: str | None = Header(default=None)):
    """Return the current Telegram session as a string so it can be stored in
    the TG_SESSION_STRING env var - this makes the login survive restarts."""
    _check_token(x_bridge_token)
    try:
        if not (client.is_connected() and await client.is_user_authorized()):
            raise HTTPException(400, "not logged in")
        s = client.session.save()
        if not s:
            raise HTTPException(400, "session could not be serialized")
        return {"session": s, "hint": "Set TG_SESSION_STRING to this value in your host env to keep the login across restarts."}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(500, f"could not export session: {e}")


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


MAX_RANGE = 8 * 1024 * 1024  # never buffer more than 8MB per request


def _parse_range_header(rng: str, size: int):
    """Parse a 'bytes=start-end' header -> (start, end) clamped to [0, size).
    Returns None when the range is unsatisfiable."""
    m = re.match(r"bytes=(\d*)-(\d*)", rng or "")
    if not m:
        return None
    start_s, end_s = m.groups()
    start = int(start_s) if start_s else 0
    end = int(end_s) if end_s else size - 1
    end = min(end, size - 1) if size else end
    if start >= size or end < start:
        return None
    # cap each range so huge/open-ended ranges can't OOM the server
    end = min(end, start + MAX_RANGE - 1)
    return start, end


@app.api_route("/api/stream/{file_id}", methods=["GET", "HEAD"])
async def api_stream(file_id: str, request: Request):
    # Note: intentionally NOT behind BRIDGE_TOKEN - the app player fetches this
    # URL without custom headers, and the fileId itself is unguessable.
    entry = _index.get(file_id)
    if not entry:
        raise HTTPException(404, "unknown fileId")
    try:
        # get_messages with a single int id returns a Message (or None), not a list
        msg = await client.get_messages(entry["chat"], ids=entry["msg_id"])
        if not msg:
            raise HTTPException(404, "file message not found")
        if not msg.media:
            raise HTTPException(404, "message has no media")
        media = msg.media
        size = int(getattr(msg.file, "size", None) or entry.get("size") or 0)
        mime = getattr(msg.file, "mime_type", None) or "video/mp4"

        headers = {
            "Accept-Ranges": "bytes",
            "Content-Type": mime,
            "Content-Disposition": 'inline; filename="video.mp4"',
            "Content-Length": str(size),
        }
        if request.method == "HEAD":
            return Response(status_code=200, headers=headers)

        rng = request.headers.get("range")
        if not rng:
            # No Range header: stream the whole file in capped chunks.
            async def whole():
                offset = 0
                while offset < size:
                    chunk = await client.download_file(
                        media, offset=offset, limit=min(MAX_RANGE, size - offset)
                    )
                    if not chunk:
                        break
                    yield chunk
                    offset += len(chunk)

            from fastapi.responses import StreamingResponse
            return StreamingResponse(whole(), status_code=200, headers=headers)

        parsed = _parse_range_header(rng, size)
        if parsed is None:
            raise HTTPException(416, "range out of bounds")
        start, end = parsed
        length = end - start + 1

        data = await client.download_file(media, offset=start, limit=length)
        headers.update({
            "Content-Range": f"bytes {start}-{end}/{size}",
            "Content-Length": str(len(data)),
        })
        return Response(content=data, status_code=206, headers=headers)
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
