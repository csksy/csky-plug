// cs-social-hub worker: comment threads plus real time rooms for CloudStream plugins.
// Deploy free on Cloudflare, see README.md in this folder.

const MAX_ROOM_SIZE = 5;
const MAX_COMMENTS = 300;
const RATE_LIMIT_SECONDS = 8;
const ROOM_TTL_MS = 3 * 60 * 1000;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json", ...CORS },
  });
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });

    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean);

    try {
      if (parts[0] === "c" && parts.length === 2) return await handleComments(request, env, parts[1]);
      if (parts[0] === "room" && parts.length === 2) return await handleRoom(request, env, parts[1]);
      if (url.pathname === "/rooms") return await handleRoomList(request, env);
      if (url.pathname === "/") return json({ ok: true, service: "cs-social-hub" });
      return json({ error: "not found" }, 404);
    } catch (err) {
      return json({ error: String(err && err.message ? err.message : err) }, 500);
    }
  },
};

async function handleComments(request, env, rawKey) {
  const store = env.STORE.get(env.STORE.idFromName("hub"));
  const key = sanitizeKey(rawKey);

  if (request.method === "GET") {
    const res = await store.fetch("https://store/comments/get/" + key);
    return new Response(res.body, { status: res.status, headers: { "Content-Type": "application/json", ...CORS } });
  }

  if (request.method === "POST") {
    const body = await request.json().catch(() => null);
    if (!body || typeof body.u !== "string" || typeof body.t !== "string") {
      return json({ error: "bad body" }, 400);
    }
    const user = body.u.trim().slice(0, 32);
    const text = body.t.trim().slice(0, 300);
    if (!user || !text) return json({ error: "empty" }, 400);
    const ip = request.headers.get("CF-Connecting-IP") || "unknown";
    const init = {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ user, text, ip }),
    };
    const res = await store.fetch("https://store/comments/add/" + key, init);
    return new Response(res.body, { status: res.status, headers: { "Content-Type": "application/json", ...CORS } });
  }

  return json({ error: "method" }, 405);
}

async function handleRoom(request, env, pin) {
  if (request.headers.get("Upgrade") !== "websocket") return json({ error: "websocket required" }, 400);
  const room = env.ROOM.get(env.ROOM.idFromName(pin));
  return room.fetch(request);
}

async function handleRoomList(request, env) {
  const store = env.STORE.get(env.STORE.idFromName("hub"));
  const res = await store.fetch("https://store/rooms/list");
  return new Response(res.body, { status: res.status, headers: { "Content-Type": "application/json", ...CORS } });
}

function sanitizeKey(key) {
  return key.replace(/[^a-zA-Z0-9_-]/g, "").slice(0, 120);
}

// Storage for comments and the public room registry. One instance holds all
// comment threads so appends stay atomic per key.
export class Store {
  constructor(state, env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request) {
    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean);

    if (parts[0] === "comments" && parts[1] === "get") return await this.getComments(parts[2]);
    if (parts[0] === "comments" && parts[1] === "add") return await this.addComment(parts[2], request);
    if (parts[0] === "rooms" && parts[1] === "register") return await this.registerRoom(request);
    if (parts[0] === "rooms" && parts[1] === "list") return await this.listRooms();
    return json({ error: "bad route" }, 404);
  }

  async getComments(key) {
    const list = (await this.state.storage.get("cmt:" + key)) || [];
    return json(list);
  }

  async addComment(key, request) {
    const body = await request.json().catch(() => null);
    if (!body) return json({ error: "bad body" }, 400);

    // Durable Object storage TTLs have a 60 second floor, so an 8s expiry flag
    // would actually block posters for a full minute. Store the last post
    // timestamp and compare, with a generous cleanup TTL on the marker itself.
    const rlKey = "rl:" + key + ":" + body.ip;
    const last = Number((await this.state.storage.get(rlKey)) || 0);
    if (Date.now() - last < RATE_LIMIT_SECONDS * 1000) return json({ error: "rate limited" }, 429);
    await this.state.storage.put(rlKey, Date.now(), { expirationTtl: 300 });

    const list = (await this.state.storage.get("cmt:" + key)) || [];
    list.push({ u: body.user, t: body.text, ts: Date.now() });
    while (list.length > MAX_COMMENTS) list.shift();
    await this.state.storage.put("cmt:" + key, list);
    return json(list);
  }

  async registerRoom(request) {
    const body = await request.json().catch(() => null);
    if (!body || !body.pin) return json({ error: "bad body" }, 400);
    const rooms = (await this.state.storage.get("rooms")) || {};
    rooms[body.pin] = {
      pin: body.pin,
      title: String(body.title || "Watch Party").slice(0, 80),
      count: Math.max(0, Math.min(MAX_ROOM_SIZE, body.count | 0)),
      host: String(body.host || "").slice(0, 32),
      ts: Date.now(),
    };
    await this.state.storage.put("rooms", rooms);
    return json({ ok: true });
  }

  async listRooms() {
    const rooms = (await this.state.storage.get("rooms")) || {};
    const now = Date.now();
    const live = {};
    let dirty = false;
    for (const pin of Object.keys(rooms)) {
      if (now - rooms[pin].ts > ROOM_TTL_MS) {
        dirty = true;
        continue;
      }
      live[pin] = rooms[pin];
    }
    if (dirty) await this.state.storage.put("rooms", live);
    return json(Object.values(live).sort((a, b) => b.ts - a.ts));
  }
}

// One instance per room PIN. Relays messages between connected clients and
// tracks roster, host and lock state so guests survive a host disconnect.
export class Room {
  constructor(state, env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request) {
    const url = new URL(request.url);
    const pin = url.pathname.split("/").filter(Boolean)[1] || "0";
    const cid = (url.searchParams.get("cid") || "").replace(/[^a-zA-Z0-9-]/g, "").slice(0, 40);
    if (!cid) return new Response("cid required", { status: 400, headers: CORS });

    if (!(await this.state.storage.get("pin"))) await this.state.storage.put("pin", pin);
    const roster = (await this.state.storage.get("roster")) || {};
    const liveCount = this.state.getWebSockets().length;

    if (Object.keys(roster).length >= MAX_ROOM_SIZE && !(cid in roster)) {
      if (liveCount === 0) await this.state.storage.deleteAll();
      return new Response("room full", { status: 409, headers: CORS });
    }

    const locked = (await this.state.storage.get("locked")) || false;
    if (locked && !(cid in roster)) {
      if (liveCount === 0) await this.state.storage.deleteAll();
      return new Response("room locked", { status: 403, headers: CORS });
    }

    if (!(cid in roster)) {
      const seqs = Object.values(roster).map((r) => r.seq);
      roster[cid] = { seq: seqs.length ? Math.max(...seqs) + 1 : 1, name: "Guest" };
      await this.state.storage.put("roster", roster);
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.state.acceptWebSocket(server, [cid]);
    server.serializeAttachment({ cid });

    const hostCid = this.pickHost(roster);
    server.send(
      JSON.stringify({
        type: "ROOM_STATE",
        cid,
        seq: roster[cid].seq,
        count: Object.keys(roster).length,
        hostCid,
        locked,
        roster: Object.entries(roster).map(([id, r]) => ({ cid: id, seq: r.seq })),
      })
    );

    this.broadcast(
      JSON.stringify({
        type: "PEER_JOINED",
        cid,
        seq: roster[cid].seq,
        count: Object.keys(roster).length,
        hostCid,
      }),
      cid
    );
    await this.reportRegistry(roster, hostCid);
    return new Response(null, { status: 101, webSocket: client });
  }

  pickHost(roster) {
    const entries = Object.entries(roster);
    if (entries.length === 0) return null;
    entries.sort((a, b) => a[1].seq - b[1].seq);
    return entries[0][0];
  }

  // roster entries survive reconnects, only sockets that are actually
  // connected right now take part in relays and host picking
  async rosterNow() {
    const stored = (await this.state.storage.get("roster")) || {};
    const live = {};
    for (const ws of this.state.getWebSockets()) {
      const att = ws.deserializeAttachment();
      if (att && att.cid && att.cid in stored) live[att.cid] = stored[att.cid];
    }
    return live;
  }

  broadcast(text, excludeCid) {
    for (const ws of this.state.getWebSockets()) {
      const att = ws.deserializeAttachment();
      if (!att || !att.cid) continue;
      if (excludeCid && att.cid === excludeCid) continue;
      try {
        ws.send(text);
      } catch (e) {
        // a dying socket throws here, the close handler cleans it up
      }
    }
  }

  async webSocketMessage(ws, message) {
    if (typeof message !== "string") return;
    let msg;
    try {
      msg = JSON.parse(message);
    } catch (e) {
      return;
    }

    const att = ws.deserializeAttachment();
    const cid = att ? att.cid : "";
    const roster = await this.rosterNow();
    if (!(cid in roster)) return;
    const hostCid = this.pickHost(roster);

    if (msg.type === "HELLO" && typeof msg.name === "string") {
      const stored = (await this.state.storage.get("roster")) || {};
      if (stored[cid]) {
        stored[cid].name = String(msg.name).trim().slice(0, 32) || "Guest";
        await this.state.storage.put("roster", stored);
      }
      this.broadcast(JSON.stringify({ type: "HELLO", cid, name: (stored[cid] || {}).name || "Guest" }));
      return;
    }

    if (msg.type === "ROOM_META") {
      const title = String(msg.title || "").slice(0, 80);
      await this.state.storage.put("title", title);
      this.broadcast(JSON.stringify({ type: "ROOM_META", cid, title }), cid);
      const stored = (await this.state.storage.get("roster")) || {};
      await this.reportRegistry(stored, this.pickHost(stored), title);
      return;
    }

    if (msg.type === "LEAVE_ROOM") {
      try {
        ws.close(1000, "bye");
      } catch (e) {}
      return;
    }

    if (msg.type === "LOCK") {
      if (cid !== hostCid) return;
      const locked = !!msg.locked;
      await this.state.storage.put("locked", locked);
      this.broadcast(JSON.stringify({ type: "LOCK_STATE", cid, locked }));
      return;
    }

    if (msg.type === "KICK") {
      if (cid !== hostCid || !msg.targetCid) return;
      for (const target of this.state.getWebSockets()) {
        const tAtt = target.deserializeAttachment();
        if (tAtt && tAtt.cid === msg.targetCid) {
          try {
            target.close(1000, "kicked");
          } catch (e) {}
        }
      }
      return;
    }

    // everything else is relayed untouched so game or sync logic stays client side
    this.broadcast(JSON.stringify({ ...msg, cid }), cid);
  }

  async reportRegistry(roster, hostCid, titleOverride) {
    const pin = await this.state.storage.get("pin");
    if (!pin) return;
    const names = (await this.state.storage.get("roster")) || {};
    const title = titleOverride !== undefined ? titleOverride : await this.state.storage.get("title") || "Watch Party";
    const hostName = (names[hostCid] && names[hostCid].name) || "";
    const store = this.env.STORE.get(this.env.STORE.idFromName("hub"));
    await store.fetch("https://store/rooms/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ pin, title, count: Object.keys(roster).length, host: hostName }),
    });
  }

  async webSocketClose(ws) {
    await this.handleLeave(ws);
  }

  async webSocketError(ws) {
    await this.handleLeave(ws);
  }

  async handleLeave(ws) {
    const att = ws.deserializeAttachment();
    const cid = att ? att.cid : "";
    const stored = (await this.state.storage.get("roster")) || {};
    if (cid in stored) delete stored[cid];

    // empty room: drop the storage so the PIN can be reused clean,
    // the registry entry ages out on its own
    if (Object.keys(stored).length === 0) {
      await this.state.storage.deleteAll();
      return;
    }

    await this.state.storage.put("roster", stored);
    const hostCid = this.pickHost(stored);
    this.broadcast(JSON.stringify({ type: "PEER_LEFT", cid, count: Object.keys(stored).length, hostCid }));
    await this.reportRegistry(stored, hostCid);
  }
}
