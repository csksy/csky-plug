# cs-social-hub worker

Free backend for the AnimeComments, AnimeParty and CineQuiz plugins.
Runs on Cloudflare's free tier: no server, no card, about 100k requests per day.

## What it does

- `GET /c/{key}` and `POST /c/{key}` - comment threads per anime episode
- `WS /room/{pin}?cid={id}` - real time rooms (watch parties, quiz games), up to 5 people
- `GET /rooms` - public list of active rooms

## Deploy in 5 minutes

1. Create a free account at https://dash.cloudflare.com (no credit card needed).
2. Go to Workers & Pages -> Create -> Create Worker -> name it `cs-social-hub` -> Deploy.
3. Click Edit code, delete the sample code, paste the full contents of `worker.js` from this folder, Deploy.
4. Add the two Durable Object bindings the relay needs:
   - Open your worker -> Settings -> Bindings -> Add binding -> Durable Object Namespace
   - Name: `ROOM`, Class: `Room`, save
   - Add binding again, Name: `STORE`, Class: `Store`, save
   - If the dashboard asks to apply a migration, confirm it. If it does not offer
     Durable Object bindings in the dashboard, use the wrangler path below.
5. Your worker URL looks like `https://cs-social-hub.<your-subdomain>.workers.dev`. Open it in a browser: you should see `{"ok":true,"service":"cs-social-hub"}`.
6. In CloudStream, open Extensions -> AnimeComments (or AnimeParty / CineQuiz) -> Settings -> paste the URL and save.

## Wrangler alternative for the dashboard-impatient

```
npm install -g wrangler
wrangler login
cd social-worker
wrangler deploy
```

`wrangler.toml` in this folder already declares both bindings and the migration.

## Notes

- Comments are capped at 300 per episode and rate limited to one post every 8 seconds per IP.
- Rooms die when the last person leaves; the public list drops rooms silent for 3 minutes.
- Nothing is logged, no accounts, no personal data beyond the display name you type in the plugin.
