# csky-plug Worklog

Tracked plugin updates for this repository.

---

## 2026-08-12 — KMMovies v3 hotfix (Cloudflare bypass actually wired in)

**Status:** ✅ live (v3, CI build passed)

### What was wrong (logcat: ErrorLoadingException on every load)
The provider called `kmGet(...)`, which Kotlin resolves to **CloudStream's
`MainAPI.kmGet` member** (plain OkHttp request) — NOT the plugin's top-level
Cloudflare-bypass `kmGet`. The entire bypass in `KMMoviesCFBypass.kt` was dead
code: no `cf_clearance` cookies, no WebView CAPTCHA dialog. The site 403-challenges
non-browser clients, so in-app requests got a challenge page → no `h1.hero-title`
→ `load()` returned null → `ErrorLoadingException`. (Logcat showed zero `KM_CF`
logs, proving the bypass never ran.)

### Fix
- Renamed the top-level bypass function `kmGet` → `kmCFGet` (unique name, no
  member shadowing) and added a `referer` param + header.
- Routed **every** provider request through `kmCFGet`: main page, search, load,
  episodes pages, skydrop `api.php`, and w3 REST — with proper browser headers.
- CF cookies are now domain-scoped: only sent to the host they were solved for.
- Bumped version to 3.

### User impact
First use after installing v3 may show the **"Cloudflare Bypass"** dialog once
(solve the CAPTCHA); cookies are saved and reused for 15 hours. A manual
"Bypass Cloudflare" button also exists in the plugin settings.

---

## 2026-08-12 — KMMovies v2 rewrite (kmmovies.online)

**Status:** ✅ live (v2, `KMMovies.cs3` on `builds` branch, CI build passed)

### What was wrong
The previous KMMovies plugin parsed `div.entry-content` headings — an outdated
site structure. The current site uses `#download-links` with `a.dl-btn` /
`.season-block` markup, and its download buttons point at link-protected
`w3.magiclinks.lol` pages. The old plugin fed those protected URLs straight to
CloudStream's `loadExtractor`, which cannot resolve them → **no seasons /
episodes, "no link found", nothing played.**

### Study sources
- **TheMoviesFlix** (this repo) — used as the reference for CloudStream plugin
  conventions, JSON data passing between `load()` / `loadLinks()`, quality
  mapping and direct-link emission. (Cinemalux intentionally NOT used — broken /
  wrong patterns.)
- Live-site probing of kmmovies.online (homepage, search, category, movie
  detail, TV detail, `episodes.magiclinks.lol`, `w1.skydrop.sbs`, w3 REST API).

### Site data flow (verified live)
```
Home / Search / Category   -> article.movie-card
Detail (#download-links)   -> a.dl-btn  (span.dl-res = quality, span.dl-size = size)
  Movies: href = https://w3.magiclinks.lol/{id}-2/   (link-protected WP post)
  TV:     .season-block per season; Episode-Wise tab links:
          https://episodes.magiclinks.lol/series/{slug}-{quality}/
          (Combined / Zip tabs = whole-season packs -> intentionally skipped)
Episodes page              -> .ep-row (span.ep-name "Episode N" + a.dl-btn ->
          https://w1.skydrop.sbs/download.php?id={token})
Movie w3 page              -> real links live in the WP REST API:
          GET /wp-json/wp/v2/posts?slug={id}-2  -> content.rendered contains
          "Google Photos Link: https://w1.skydrop.sbs/download.php?id=..."
          and "Google Drive Link: https://drive.google.com/file/d/{id}/view"
skydrop                    -> GET https://w1.skydrop.sbs/api.php?id={token}
          -> {"success":true,"link":"https://video-downloads.googleusercontent.com/..."}
          direct MKV stream — multi-audio surfaces natively via ExoPlayer's
          audio-track selector in CloudStream.
```

### What the rewrite does
- **Cards:** parses `article.movie-card` (title, poster, Movie vs Series via
  `.meta-row` / `.badge-episodes` / title pattern).
- **Movies:** resolves protected w3 links via the WP REST API → skydrop →
  direct `video-downloads.googleusercontent.com` stream, with Google Drive as a
  secondary source through the built-in extractor. Source names carry quality +
  size + audio, e.g. `KMMovies 1080p (1.9GB) • Hindi, English`.
- **TV:** builds proper season/episode structure from `.season-block` —
  per-episode links only (whole-season Combined/Zip packs skipped). Each
  episode exposes every quality variant (480p → 4K incl. 10-bit).
- **Robustness:** skydrop `api.php` answers `{"busy":true}` while shared —
  resolver retries with backoff; handles WordPress en-dash (`–`) corruption of
  `--` inside encrypted tokens and transient garbage responses.
- **Fallback:** seasons without episode-wise links fall back to whole-season
  pack links.

### Validation
- Local build: `./gradlew :KMMovies:make` → `KMMovies.cs3` ✅
- End-to-end live simulation (Python mirroring provider logic): movie resolves
  to real video URLs; The Boys shows 5 seasons × 8 episodes; first episode
  resolves to a direct stream ✅
- GitHub Actions CI: passed; v2 published to `builds` branch + `plugins.json` ✅

### Files changed
- `KMMovies/src/main/kotlin/com/kmmovies/KMMoviesProvider.kt` (rewritten)
- `KMMovies/build.gradle.kts` (version 2, description)
- `README.md` (plugin table)

---

## Plugin status table (current)

| Plugin | Version | Status |
|---|---|---|
| KMMovies | 3 | ✅ working (v3 CF-bypass hotfix 2026-08-12; v2 rewrite below) |
| TheMoviesFlix | 18 | reference implementation |

---

## 2026-08-26 — Kdesa v1 (new plugin: kdesa.stream movies/TV/anime, 9 sources)

**Status:** ✅ built (v1, `:Kdesa:make` passed, Kdesa.cs3 produced)

### What this is
New plugin for **kdesa.stream** — a TMDB-fronted "kstream" site. The catalog
(movies + TV + anime) is mirrored from TMDB (same bearer token the site ships in
its `config.js`), and every watch page fans out to all of the site's enabled
streaming sources.

### Sources implemented (all 9 enabled on the site)
- **TQQ (Anime)** — all 5 AniKoto mirrors (`anikototv.to`, `.cz`, `.me`, `.net`,
  `.se`): search → watch slug → ajax episode list → ajax server list (sub/hsub/dub)
  → ajax server → MegaPlay embed → `getSources` m3u8 + subtitle tracks. Season
  marker + specials filtering mirrors the site's own matcher.
- **Anidap** — AniList GraphQL → `anidap.lol/api/anime/{id}` →
  `chad.anidap.lol/rest/api/sources` (sub/dub × yuki/beep/uwu). Applies the
  per-response `headers.Referer` (megaplay.buzz etc — the CDN 403s without it).
- **FSOnline** — dooplay flow behind Cloudflare (`/film/{slug}/`,
  `/episoade/{slug}-sezonul-S-episodul-E/`, `admin-ajax.php` `lazy_player`),
  Filemoon + Doodstream embeds.
- **CornClick** — `cornclick.com/player/...` JSON API (hls via their proxy,
  opensubtitles .gz tracks are skipped — the player cannot render gzip).
- **Cuevana3** — TMDB `es-ES` title slug → `/ver-pelicula/` or `/episodio/...`
  → Next.js pageProps videos (latino/spanish/english/japanese) → `player.php`
  → streamwish / filemoon / vidhide / voe embeds, language-labelled links.
- **7Movies** — `7movies.in/api/playback-token` →
  `embed.animecurx.tech/api/source/...?provider=vaplayer`, decodes the
  `proxyUrl?url=` query param back to the raw m3u8.
- **1Embed** — `1embed.cc/api/token` → `_st=` param (required now — the
  movie-web code predates it) → servers vidsrc/goated/emp/night. Uses the
  proxy `streamUrl` (raw_m3u8 is IP-locked to their server) + vtt subs +
  audio-track labels.
- **Nova** — `novahd.cc/api/sources` (hls/mp4 + language + quality + subs)
  behind Cloudflare with the WebView bypass dialog, `ready=false` retry ×3.
- **VixSrc (Italian)** — `/api/movie|tv/...?lang=it` → embed (10-second token!)
  → `window.masterPlaylist` params appended to the playlist URL (the site's own
  implementation misses this and gets 403 — ours works). Master m3u8 is passed
  straight to the player so the Italian/English audio + subtitle renditions
  stay selectable natively.

`CineHDPlus` was deliberately excluded: its search endpoint returns the same
generic listing for every query (verified live), so it would always resolve to
the wrong movie.

### Embed fallback chain (mirror domains)
`loadExtractor` (built-ins, relabelled with source/language prefix) →
filemoon-style packed-JS unpack → dood `pass_md5` flow → WebViewResolver
m3u8/mp4 interception (for JS-rendered mirrors such as `bysejikuar.com`).

### Logging
Every step of every source logs under `Kdesa` with `[Source]` tags —
request URLs, HTTP codes, parsed counts, embed URLs, m3u8s — so failures
are traceable in logcat.

---

## 2026-08-26 — Kdesa v3 (multi-audio fix + source de-clutter)

**Status:** ✅ built (`:Kdesa:make` passed, version 2)

### Root cause of silent "multi audio" streams (verified live)
Nova Titan/Orion masters — and any vidsrc-style multi-audio master — keep
their audio as a **separate `#EXT-X-MEDIA:TYPE=AUDIO` rendition** that the
`#EXT-X-STREAM-INF` variants only reference via `AUDIO="nova-aud"`. The old
code ran every master through `M3u8Helper.generateM3u8()`, which splits the
master into per-quality **variant** links — variants alone have no audio, so
those sources played completely silent. (CloudStream's own M3u8Helper source
says extensions must decide this: "The m3u8 should not be split it that
causes a loss of audio".)

Verified practically by fetching live manifests:
- Nova Titan JP master: `EXT-X-MEDIA:TYPE=AUDIO ... URI="v3.m3u8"` (relative) +
  3 HEVC variants referencing `AUDIO="nova-aud"` → **must stay unsplit**
- Nova Titan Hindi master: separate file, `LANGUAGE`-tagged Hindi audio
- Nova Vega/Falcon "Auto": muxed (mp4a inside variants, no audio group)
- 7Movies "Earth": 3 identical mirrors of the same muxed master (same variant
  hash dirs `7a67bab…/7e39f9…/9e523a…`) — pure duplicates
- CornClick/megaplay (TQQ), 1Embed vidsrc for single-language titles: muxed

### What changed
- **`probeHls()`** — fetches an m3u8, extracts audio-rendition languages
  (NAME/LANGUAGE), max RESOLUTION height, variant count and a content
  signature (audio langs + last-2 path chunks of variant/segment URLs).
- **`emitHls()`** — the new single emission point for HLS:
  - demuxed audio found → **one unsplit master link** (audio tracks stay
    selectable in the CS player track selector), `(Multi-Audio: …)` suffix
    when more than one track
  - muxed master → per-quality links via `M3u8Helper` as before
  - probe failed → raw link fallback so nothing is lost
- **Nova** — the 20+ raw `provider × language × quality` entries are collapsed
  into **one link per language** (One Piece: 25 → 8: Japanese/Hindi/Kannada/
  Malayalam/Spanish/Tamil/Telugu/French), best provider per language picked
  by probing (up to 3 attempts), mp4 fallback if no hls master answers.
  Names like `Nova Japanese 1080p`, `Nova Spanish 720p`.
- **7Movies** — duplicate Earth mirrors deduped by manifest signature.
- **Cuevana3** — first working host per language instead of every embed.
- **1Embed** — `(Multi-Audio: …)` in the name when the API reports several
  audioTracks (streamUrl was already passed unsplit).
- CornClick / TQQ / Anidap / filemoonInline / webViewIntercept all route
  through `emitHls` so demuxed-audio masters survive everywhere.
- Subtitle handling untouched (Nova/CornClick/1Embed/TQQ/Anidap vtt/srt tracks).
- Logging extended: probe results (audio langs / maxRes / signature), dedupe
  decisions, per-language Nova choices, Cuevana3 per-language outcomes.
