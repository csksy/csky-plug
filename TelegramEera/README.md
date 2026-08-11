# Telegram Eera

A CloudStream provider that searches and streams **Movies / TV / Anime** from
Telegram — the `@eera_Search_Zone` search group and the `@Movie_world2_bot`
delivery bot — through a small companion server called the **Eera bridge**
(see [`/bridge`](../bridge)).

## How it works

1. **Search** — your query is sent to the group; the group bot replies with a
   file list (`📁 [size] ❗ filename …`). The plugin shows those files as
   results.
2. **Pick** — each file carries a hidden deep-link payload extracted from the
   clickable title.
3. **Deliver** — the bridge sends that payload to `@Movie_world2_bot`, which
   replies with the actual video file. The bridge **instantly forwards it to
   Saved Messages** to beat the bot's 50-second self-destruct timer.
4. **Stream** — the bridge streams the file with HTTP Range support, so the
   CloudStream player plays it progressively (no full download first).

## Setup

1. Deploy the bridge once (see [`/bridge`](../bridge) — free hosts work).
2. Set `DEFAULT_BRIDGE` in `TelegramEeraProvider.kt` to your bridge URL and
   rebuild the plugin (or seed it via `TelegramEeraSettings`).
3. Log the bridge into a Telegram account (browser at `https://YOUR-BRIDGE/`).

> Note: the current CloudStream3 pre-release no longer ships the old
> `PreferenceKey` extension settings API, so the bridge URL is configured in
> code. Everything else (login, filters, quality) lives on the bridge.

## Settings (code-level)

| Key | Where | Default |
|---|---|---|
| `bridge_url` | `TelegramEeraSettings` | `https://eera-bridge.onrender.com` |
| `request_timeout` | `TelegramEeraSettings` | `90` seconds |
