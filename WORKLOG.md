# csky-plug Worklog

Tracked plugin updates for this repository.

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
| KMMovies | 2 | ✅ working (rewritten 2026-08-12) |
| TheMoviesFlix | 18 | reference implementation |
