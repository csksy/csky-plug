# cs-social-hub worker

Free backend for the AnimeComments, AnimeParty and CineQuiz plugins.
Runs on Cloudflare's free tier: no server, no credit card, about 100k requests per day.

**Status: deployed.** The shared worker lives at
`https://cs-social-hub.saprujatin70.workers.dev` and `endpoint.json` next to
this file already points there, so plugin users need to configure nothing.
Everything below stays for anyone who wants to self host or redeploy.

## Who needs to deploy this

Only the repo owner, once. The plugins read `endpoint.json` next to this file,
so when it holds your worker URL every user of the repo shares your worker
automatically. Users never deploy, configure or paste anything. A personal URL
in the plugin settings still overrides the shared one for anyone who wants to
self host.

Comments are therefore shared: everyone watching the same episode sees the
same thread, the same way everyone on YouTube sees the same comments. Old
comments stay until the 300 per episode cap trims the oldest.

## What it does

- `GET /c/{key}` and `POST /c/{key}` - comment threads per anime episode
- `WS /room/{pin}?cid={id}` - real time rooms (watch parties, quiz games), up to 5 people
- `GET /rooms` - public list of active rooms

## Deploy it (one click)

If you already tried the dashboard and ended on a screen saying
"Deploy a site by uploading your project" - that is Cloudflare Pages, the
wrong product, and the `cs-social-hub` it created is a dead Pages project.
Delete it first so the name is free: Workers & Pages, open `cs-social-hub`,
Settings, Delete project. Then:

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/csksy/csky-plug/tree/main/social-worker)

1. Tap the button above (works from a phone browser).
2. Sign in to Cloudflare if asked.
3. It offers to connect GitHub and copy this folder into a small repo in your
   account - allow it. This is what applies the Durable Object bindings and
   the storage migration automatically, which the dashboard editor cannot do
   for you.
4. Keep the default name `cs-social-hub` and wait for the build to finish,
   usually a minute or two.
5. Open Workers & Pages, open the `cs-social-hub` worker. In Settings,
   Domains & Routes you find its address, like
   `https://cs-social-hub.<your-subdomain>.workers.dev`.
6. Open that address in a browser. You should see
   `{"ok":true,"service":"cs-social-hub"}`.
7. Put that address into `endpoint.json` next to this file and commit, or
   send it to whoever maintains the repo. Done - everyone shares it.

## Wrangler alternative

For anyone comfortable with a terminal:

```
npm install -g wrangler
wrangler login
cd social-worker
wrangler deploy
```

`wrangler.toml` in this folder declares both Durable Object bindings and the
migration, so the plain deploy command creates everything.

## Why not the dashboard editor

The dashboard can create a Worker and you can paste the code into Edit code,
but the room relay needs two Durable Object bindings and a storage migration.
The dashboard's Add binding screen is unreliable for Durable Objects, so use
the button or wrangler, both of which read `wrangler.toml` and set everything
up correctly.

## Notes

- Comments are capped at 300 per episode and rate limited to one post every 8 seconds per IP.
- Rooms die when the last person leaves; the public list drops rooms silent for 3 minutes.
- Nothing is logged, no accounts, no personal data beyond the display name you type in the plugin.
