# Eera Telegram Bridge

Small server that powers the **Telegram Eera** CloudStream plugin. It logs into
a real Telegram account and gives the plugin a clean HTTP API to search the
`@eera_Search_Zone` group, request files from `@Movie_world2_bot` (beating the
50-second self-destruct by forwarding to Saved Messages instantly) and stream
the file to the player.

```
┌──────────────┐   HTTP/JSON   ┌─────────────────┐   MTProto (Telethon)   ┌────────────────────┐
│ CloudStream  │ ────────────► │  Eera Bridge     │ ─────────────────────► │ @eera_Search_Zone  │
│ plugin       │               │  (this server)   │                        │ @Movie_world2_bot  │
└──────────────┘               └─────────────────┘                        └────────────────────┘
```

## Why a bridge instead of doing it inside the plugin?

The official Telegram Bot API **cannot** read the group, **cannot** message
other bots and **cannot** search — only a real account (MTProto) can automate
this flow. Running that account on a tiny server keeps the CloudStream plugin
small, simple and safe, and the account looks like a normal human user instead
of a scraper.

## 1. Get Telegram API credentials

1. Go to https://my.telegram.org → Log in → **API development tools**.
2. Create an app → copy **api_id** and **api_hash**.

## 2. Deploy

Any free host works (Railway, Render free, PythonAnywhere, Fly.io…). Railway /
Render are easiest because they run `main.py` directly.

- Set env vars: `API_ID`, `API_HASH`, and optionally `PORT`.
- Start command: `uvicorn main:app --host 0.0.0.0 --port $PORT`
  (or `python main.py`).

Locally for testing:

```bash
pip install -r requirements.txt
export API_ID=12345 API_HASH=abcdef
uvicorn main:app --host 0.0.0.0 --port 8000
```

## 3. Log in once

Open `https://YOUR-BRIDGE-URL/` in a browser:

1. **Status** — shows whether you're logged in.
2. **Step 1** — enter your Telegram phone number (use a **dedicated account**,
   not your main one — this is an automated session).
3. **Step 2** — enter the login code from Telegram, plus your 2FA password if
   you have one.

> The session is stored on the server. On free hosts the disk can reset on
> redeploy; to keep the session, generate a **session string** (see
> `.env.example`) and set `TG_SESSION_STRING`.

## 4. Point the plugin at your bridge

In `TelegramEera/src/main/kotlin/com/laddu100/telegrameera/TelegramEeraProvider.kt`
set `DEFAULT_BRIDGE` to your deployed URL (e.g. `https://eera-bridge.onrender.com`)
and rebuild the plugin — or set it via `TelegramEeraSettings.setBridgeUrl(...)`.

## API

| Route | Purpose |
|---|---|
| `GET /health` | status + login state |
| `POST /api/login` `{phone}` | send login code |
| `POST /api/login/code` `{code, password?}` | finish login |
| `POST /api/logout` | log out |
| `GET /api/search?q=...` | search the group, returns `{results:[{title,size,payload}]}` |
| `GET /api/select?payload=...` | request the file from the bot, forwards to Saved Messages, returns `{fileId,...}` |
| `GET /api/stream/{fileId}` | progressive HTTP stream (Range requests supported) |

### Security

Set `BRIDGE_TOKEN` to a random string; then all `/api` routes require the
header `X-Bridge-Token: <token>`. The stream URL itself should stay private —
anyone with it can watch the file.

### Known limitations / notes

- The account can be flagged by Telegram if abused — keep usage human-like.
- The bot may change its reply format; check `/api/search` output and tweak
  `_parse_file_list` / `_wait_for_bot_reply` accordingly.
- First search includes a group join — allow a few extra seconds.
