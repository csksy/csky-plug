# AnimeInWeb

CloudStream plugin for animeinweb.com (ANIMEIN Web, Indonesian hardsub anime).

## How the site works

- Next.js frontend on animeinweb.com, CodeIgniter API behind its server proxy:
  `GET /api/proxy/3/2/<endpoint>` with header `x-proxy-secret: animein-secure-proxy-key-123`
  (hardcoded in the site's own JS bundle; requests without it get 403).
- Endpoints used: `home/data`, `explore/movie` (catalog + search, 60/page),
  `schedule/data` (full Indonesian day names only), `movie/detail/{id}`,
  `movie/episode/{id}?page=N` (30/page, newest first),
  `episode/streamnew/{episode_id}` (server list).
- Posters require `Referer: https://animeinweb.com/` plus a real UA, so every
  response sets `posterHeaders` accordingly. Episode images have no header
  support in CloudStream and are intentionally not used.

## Sources

| Server | Type | Status |
|---|---|---|
| storages.animein.net (RAPSODI) | direct | live, 360p-1080p MP4, this is what the site player itself plays |
| new.uservideo.xyz (NANIMEX) | semi | dead upstream (connection timeout), skipped |
| nanifile.com (ZORO) | semi | dead upstream (Cloudflare 526), skipped |
| www.blogger.com / gdplayer.to (ANO) | semi | legacy entries, best-effort via loadExtractor |

The site has no softsub system and no dubs anywhere: all videos are
Indonesian hardsub. There are no subtitle files to extract.
