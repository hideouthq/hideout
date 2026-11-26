"use strict";
(() => {
  var __defProp = Object.defineProperty;
  var __defNormalProp = (obj2, key, value) => key in obj2 ? __defProp(obj2, key, { enumerable: true, configurable: true, writable: true, value }) : obj2[key] = value;
  var __publicField = (obj2, key, value) => __defNormalProp(obj2, typeof key !== "symbol" ? key + "" : key, value);

  // src/friends.ts
  var NO_FRIENDS = { online: [], offline: 0, names: {} };
  var GROUPS = [
    { id: "FriendsPlayingCitadel", presence: "playing" },
    { id: "FriendsOnline", presence: "online" },
    { id: "FriendsOffline", presence: "offline" }
  ];
  var MATCH_MODES = ["Deadlock", "Ranked", "Hero Labs", "Lobby", "Bots", "Tutorial", "Sandbox", "Placement Match", "Street Brawl", "Playing"];
  var IN_DEADLOCK = [
    { raw: /^Main Menu/i, text: "In the main menu", inMatch: false },
    { raw: /^Finding Match/i, text: "Finding a match", inMatch: false },
    { raw: /^Picking Hero/i, text: "In a match", inMatch: true },
    { raw: new RegExp(`^(${MATCH_MODES.join("|")}):`, "i"), text: "In a match", inMatch: true },
    { raw: /^Testing .* in Sandbox Mode/i, text: "In a match", inMatch: true },
    { raw: /^In a Party Hideout/i, text: "In a party", inMatch: false },
    { raw: /Hideout/i, text: "In the hideout", inMatch: false },
    { raw: /^Online$/i, text: "Online", inMatch: false }
  ];
  function matchMinutes(raw) {
    const clock = raw.match(/(?:(\d+):)?(\d+):(\d\d)/);
    if (clock) return Number(clock[1] ?? 0) * 60 + Number(clock[2]);
    const minutes = raw.match(/(\d+)\s*min\b/i);
    return minutes ? Number(minutes[1]) : null;
  }
  function statusOf(presence, raw) {
    const known = presence === "playing" ? IN_DEADLOCK.find((s) => s.raw.test(raw.trim())) : void 0;
    if (!known) return { status: raw.trim() || "Online", inMatch: false };
    const minutes = known.inMatch ? matchMinutes(raw) : null;
    return { status: minutes === null ? known.text : `${known.text} \xB7 ${minutes} min`, inMatch: known.inMatch };
  }
  function friendsOf(raw) {
    const seen = /* @__PURE__ */ new Set();
    const online = [];
    const names = {};
    let offline = 0;
    for (const f of raw) {
      if (f.currentUser || !/^\d{17}$/.test(f.steamid) || seen.has(f.steamid)) continue;
      seen.add(f.steamid);
      names[f.steamid] = f.name;
      if (f.presence === "offline") offline++;
      else online.push({ id: f.steamid, name: f.name, presence: f.presence, ...statusOf(f.presence, f.status) });
    }
    return { online, offline, names };
  }
  function textOf(panel2) {
    if (!panel2) return "";
    if (panel2.paneltype === "Label" && panel2.text) return panel2.text;
    for (let i = 0; i < panel2.GetChildCount(); i++) {
      const text2 = textOf(panel2.GetChild(i));
      if (text2) return text2;
    }
    return "";
  }
  function friendPanels(root, out = []) {
    if (root.paneltype === "CitadelFriend") out.push(root);
    for (let i = 0; i < root.GetChildCount(); i++) {
      const child = root.GetChild(i);
      if (child) friendPanels(child, out);
    }
    return out;
  }
  function readMenu(menu) {
    const raw = [];
    for (const group of GROUPS) {
      const box = menu.FindChildTraverse(group.id);
      if (!box) continue;
      for (const f of friendPanels(box)) {
        raw.push({
          steamid: String(f.steamid),
          name: textOf(f.FindChildTraverse("FriendName")),
          status: textOf(f.FindChildTraverse("FriendRichPresence")),
          presence: group.presence,
          currentUser: f.BHasClass("CurrentUser")
        });
      }
    }
    return raw;
  }
  var FriendWatch = class {
    constructor(menu, changed) {
      __publicField(this, "current", NO_FRIENDS);
      __publicField(this, "key", "");
      menu.RemoveClass("Hidden");
      const read = () => {
        const now = friendsOf(readMenu(menu));
        const key = JSON.stringify(now);
        if (key === this.key) return false;
        this.key = key;
        this.current = now;
        return true;
      };
      const tick = () => {
        if (!menu.IsValid()) return;
        if (read()) changed();
        $.Schedule(2, tick);
      };
      read();
      $.Schedule(2, tick);
    }
  };

  // src/joining.ts
  var LABELS = {
    stop: { label: "Cancel", primary: false },
    dismiss: { label: "Close", primary: false },
    again: { label: "Try again", primary: true },
    restart: { label: "Restart Deadlock", primary: true },
    play: { label: "Play", primary: true }
  };
  function artFor(name, modes) {
    const mode2 = modes.find((m) => m.name === name);
    return mode2 ? mode2.art.backdrop ?? mode2.art.card ?? mode2.art.tile : null;
  }
  function hostOf(card, friends) {
    const name = card.hostId && friends.names[card.hostId] || card.hostName;
    return name ? { id: card.hostId, text: `${name}\u2019s game` } : null;
  }
  function viewJoining(snapshot, putAway, status, friends) {
    const card = snapshot.game?.join;
    if (!card || card.phase === putAway || card.phase === "notin" || status === "offline") return null;
    const words2 = card.words;
    const failed = card.phase === "failed";
    const buttons = card.actions.map((action2) => ({ kind: "act", action: action2, ...LABELS[action2] }));
    if (card.phase === "review") buttons.push({ kind: "confirm", restart: card.restart?.reason ?? null, label: "Join", primary: true });
    buttons.sort((a, b) => Number(a.primary) - Number(b.primary));
    let box = null;
    if (failed && words2) box = { tone: "failed", title: words2.title, body: words2.body };
    else if (card.restart) box = { tone: "note", title: card.title, body: card.restart.text };
    else if (words2 && !card.steps.length) box = { tone: "note", title: words2.title, body: words2.body };
    return {
      card,
      title: failed || !words2 ? card.title : words2.title,
      line: [...card.picks, card.capacity ? `${card.capacity} players` : null].filter(Boolean).join(" \xB7 "),
      art: artFor(card.mode, snapshot.catalog?.modes ?? []),
      host: hostOf(card, friends),
      steps: card.steps,
      busy: card.steps.length ? null : card.busy,
      box,
      buttons
    };
  }

  // src/titles.ts
  var PART = "modlock:frame:";
  var LINE = "modlock:snapshot:";
  var TitleReader = class {
    constructor() {
      __publicField(this, "parts", null);
      __publicField(this, "last", "");
    }
    read(title) {
      if (title.startsWith(LINE)) {
        if (title === this.last) return { kind: "repeat" };
        this.last = title;
        return { kind: "part", line: null };
      }
      if (!title.startsWith(PART)) {
        this.parts = null;
        this.last = "";
        return title === "" ? { kind: "offline" } : { kind: "waiting" };
      }
      if (title === this.last) return { kind: "repeat" };
      this.last = title;
      const fields = title.slice(PART.length).split(":", 4);
      const [id, offset, total] = fields.map(Number);
      const chunk = title.slice(PART.length + fields.join(":").length + 1);
      if (fields.length !== 4 || !id || !total || offset === void 0) {
        this.parts = null;
        return { kind: "broken" };
      }
      if (offset === 0) this.parts = { id, total, text: "" };
      const parts = this.parts;
      if (!parts || parts.id !== id || parts.text.length !== offset) {
        this.parts = null;
        return { kind: "broken" };
      }
      parts.text += chunk;
      if (parts.text.length < parts.total) return { kind: "part", line: null };
      this.parts = null;
      const line = text(parts.text);
      return line === null ? { kind: "broken" } : { kind: "part", line };
    }
  };
  function unpack(packed) {
    const bytes = new Uint8Array(packed.length);
    for (let i = 0; i < packed.length; i++) {
      const code = packed.charCodeAt(i);
      if (code >= 1 && code <= 127) bytes[i] = code;
      else if (code >= 256 && code <= 511) bytes[i] = code - 256;
      else return null;
    }
    return bytes;
  }
  function utf8(bytes) {
    let latin = "";
    for (let at = 0; at < bytes.length; at += 4096) latin += String.fromCharCode.apply(null, bytes.subarray(at, at + 4096));
    try {
      return decodeURIComponent(escape(latin));
    } catch {
      return null;
    }
  }
  function text(packed) {
    const bytes = unpack(packed);
    return bytes && utf8(bytes);
  }

  // src/wire.ts
  var PROTOCOL = 1;
  var RESULTS = ["accepted", "busy", "gone", "invalid", "unavailable", "outdated", "stale"];
  function resultOfWidth(width, scale = 1) {
    return RESULTS[Math.round(width / scale) - 1] ?? "unreachable";
  }
  var EMPTY = { hello: null, catalog: null, game: null, inbox: null, servers: null };
  function withFrame(snapshot, frame) {
    switch (frame.t) {
      case "hello":
        return { ...snapshot, hello: frame };
      case "catalog":
        return { ...snapshot, catalog: frame };
      case "game":
        return { ...snapshot, game: frame };
      case "inbox":
        return { ...snapshot, inbox: frame };
      case "servers":
        return { ...snapshot, servers: frame };
      case "alive":
        return snapshot;
    }
  }
  var Malformed = class extends Error {
  };
  function obj(v) {
    if (typeof v !== "object" || v === null || Array.isArray(v)) throw new Malformed();
    return v;
  }
  function str(v) {
    if (typeof v !== "string") throw new Malformed();
    return v;
  }
  function bool(v) {
    if (typeof v !== "boolean") throw new Malformed();
    return v;
  }
  function num(v) {
    if (typeof v !== "number" || !isFinite(v)) throw new Malformed();
    return v;
  }
  function list(v, item) {
    if (!Array.isArray(v)) throw new Malformed();
    return v.map(item);
  }
  function opt(v, item) {
    return v === null || v === void 0 ? null : item(v);
  }
  function oneOf(values) {
    return (v) => {
      if (!values.includes(v)) throw new Malformed();
      return v;
    };
  }
  function record(v, item) {
    const out = {};
    for (const [key, value] of Object.entries(obj(v))) out[key] = item(value);
    return out;
  }
  function pair(v) {
    const [a, b] = list(v, num);
    if (a === void 0 || b === void 0) throw new Malformed();
    return [a, b];
  }
  var action = oneOf(["stop", "restart", "play", "dismiss", "again"]);
  var listing = oneOf(["invite-only", "public"]);
  function words(v) {
    const o = obj(v);
    return { title: str(o.title), body: str(o.body) };
  }
  function restart(v) {
    const o = obj(v);
    return { reason: oneOf(["not-set-up", "game-updated", "menus-did-not-start"])(o.reason), text: str(o.text) };
  }
  function step(v) {
    const o = obj(v);
    return { label: str(o.label), status: oneOf(["done", "running", "waiting"])(o.status), note: opt(o.note, str), progress: opt(o.progress, num) };
  }
  function mode(v) {
    const o = obj(v);
    const art2 = obj(o.art);
    return {
      id: str(o.id),
      name: str(o.name),
      tagline: str(o.tagline),
      kind: opt(o.kind, str),
      players: pair(o.players),
      minutes: opt(o.minutes, pair),
      goal: opt(o.goal, str),
      by: list(o.by, str),
      bytes: opt(o.bytes, num),
      art: { card: opt(art2.card, str), backdrop: opt(art2.backdrop, str), render: opt(art2.render, str), overlay: opt(art2.overlay, str), tile: opt(art2.tile, str) },
      settings: list(o.settings, (s) => {
        const so = obj(s);
        return {
          key: str(so.key),
          label: str(so.label),
          hint: opt(so.hint, str),
          default: str(so.default),
          options: list(so.options, (p) => {
            const po = obj(p);
            return { value: str(po.value), label: str(po.label), players: opt(po.players, num) };
          })
        };
      })
    };
  }
  function host(v) {
    const o = obj(v);
    return {
      mode: str(o.mode),
      phase: oneOf(["starting", "restart", "restarting", "entering", "ready", "notin", "ending", "failed", "ended"])(o.phase),
      steps: list(o.steps, step),
      words: opt(o.words, words),
      link: opt(o.link, str),
      players: opt(o.players, pair),
      listing: listing(o.listing),
      invitees: list(o.invitees, (i) => str(i)),
      deliveries: record(o.deliveries, oneOf(["sending", "delivered", "not-reached"])),
      actions: list(o.actions, action)
    };
  }
  function join(v) {
    const o = obj(v);
    return {
      title: str(o.title),
      mode: str(o.mode),
      picks: list(o.picks, str),
      capacity: opt(o.capacity, num),
      hostId: opt(o.hostId, (i) => str(i)),
      hostName: opt(o.hostName, str),
      phase: oneOf(["finding", "review", "cleanup", "progress", "stopping", "restart", "restarting", "notin", "failed"])(o.phase),
      steps: list(o.steps, step),
      words: opt(o.words, words),
      busy: opt(o.busy, str),
      restart: opt(o.restart, restart),
      actions: list(o.actions, action)
    };
  }
  function session(v) {
    const o = obj(v);
    return {
      role: oneOf(["host", "guest"])(o.role),
      mode: str(o.mode),
      phase: oneOf(["starting", "in", "notin", "ending", "over"])(o.phase),
      hostId: opt(o.hostId, (i) => str(i)),
      hostName: opt(o.hostName, str),
      actions: list(o.actions, action)
    };
  }
  function invite(v) {
    const o = obj(v);
    return { id: str(o.id), from: str(o.from), mode: str(o.mode), picks: list(o.picks, str), tile: opt(o.tile, str), players: pair(o.players), expires: num(o.expires) };
  }
  function server(v) {
    const o = obj(v);
    return {
      id: str(o.id),
      host: opt(o.host, str),
      mode: str(o.mode),
      picks: list(o.picks, str),
      tile: opt(o.tile, str),
      region: str(o.region),
      players: pair(o.players),
      ping: opt(o.ping, num),
      status: oneOf(["lobby", "match", "full"])(o.status)
    };
  }
  function decodeFrame(text2) {
    try {
      const o = obj(JSON.parse(text2));
      switch (o.t) {
        case "hello":
          return {
            t: "hello",
            protocol: num(o.protocol),
            epoch: str(o.epoch),
            social: oneOf(["on", "off", "other-account", "blocked"])(o.social),
            supportId: opt(o.supportId, (v) => str(v))
          };
        case "catalog":
          return { t: "catalog", modes: list(o.modes, mode) };
        case "game":
          return {
            t: "game",
            host: opt(o.host, host),
            join: opt(o.join, join),
            session: opt(o.session, session),
            restartAhead: opt(o.restartAhead, restart),
            startsHost: bool(o.startsHost),
            takesInvite: bool(o.takesInvite)
          };
        case "inbox":
          return { t: "inbox", invites: list(o.invites, invite) };
        case "servers":
          return { t: "servers", state: oneOf(["idle", "loading", "ready", "unreachable", "unavailable"])(o.state), rows: list(o.rows, server) };
        case "alive":
          return { t: "alive" };
        default:
          return null;
      }
    } catch (e) {
      if (e instanceof Malformed || e instanceof SyntaxError) return null;
      throw e;
    }
  }

  // src/link.ts
  var STALE = 25;
  var LOAD_DEADLINE = 10;
  var SIZE_DELAY = 0.1;
  var COMMAND_DEADLINE = 8;
  var Link = class {
    constructor(host2, config, changed) {
      __publicField(this, "snapshot", EMPTY);
      __publicField(this, "status", "connecting");
      __publicField(this, "titles", new TitleReader());
      __publicField(this, "html");
      __publicField(this, "boot", String(Math.floor(Math.random() * 1e9)));
      __publicField(this, "sequence", 0);
      __publicField(this, "acked", false);
      __publicField(this, "watchdog");
      __publicField(this, "queue", []);
      __publicField(this, "sending", false);
      __publicField(this, "host");
      __publicField(this, "config");
      __publicField(this, "changed");
      this.host = host2;
      this.config = config;
      this.changed = changed;
      this.html = $.CreatePanel("HTML", host2, "", { hittest: "false", style: "width: 2px; height: 2px; opacity: 0.01;" });
      $.RegisterEventHandler("HTMLStartRequest", this.html, () => false);
      $.RegisterEventHandler("HTMLTitle", this.html, (_, title) => {
        this.read(title);
        return false;
      });
      this.load(LOAD_DEADLINE);
    }
    send(command, done = () => {
    }) {
      this.queue.push({ command, done });
      if (!this.sending) this.next();
    }
    load(deadline) {
      this.html.SetURL(this.config.page);
      this.arm(deadline);
    }
    arm(seconds) {
      if (this.watchdog !== void 0) $.CancelScheduled(this.watchdog);
      this.watchdog = $.Schedule(seconds, () => {
        this.watchdog = void 0;
        this.load(LOAD_DEADLINE);
      });
    }
    acknowledge() {
      this.acked = !this.acked;
      this.html.SetURL(`${this.config.page}#${this.acked ? 1 : 0}`);
    }
    read(title) {
      const read = this.titles.read(title);
      switch (read.kind) {
        case "repeat":
          return;
        case "waiting":
          this.arm(STALE);
          return;
        case "offline":
          this.arm(STALE);
          return this.become("offline");
        case "broken":
          $.Msg("[hideout] a frame part came out of order");
          return;
        case "part":
          this.arm(STALE);
          this.acknowledge();
          if (read.line === null) return;
          const frame = decodeFrame(read.line);
          if (!frame) return void $.Msg("[hideout] could not read a frame");
          this.snapshot = withFrame(this.snapshot, frame);
          if (frame.t === "alive" && this.status !== "connecting" && this.status !== "offline") return;
          this.status = this.snapshot.hello && this.snapshot.hello.protocol !== PROTOCOL ? "outdated" : "online";
          this.changed();
      }
    }
    become(status) {
      if (this.status === status) return;
      this.status = status;
      this.changed();
    }
    next() {
      const item = this.queue.shift();
      if (!item) return void (this.sending = false);
      this.sending = true;
      const query = [
        `p=${PROTOCOL}`,
        `t=${this.config.token}`,
        `e=${this.snapshot.hello?.epoch ?? ""}`,
        `b=${this.boot}`,
        `n=${this.sequence++}`,
        `c=${encodeURIComponent(JSON.stringify(item.command))}`
      ].join("&");
      const image = $.CreatePanel("Image", this.host, "", {
        src: `http://127.0.0.1:${this.config.port}/c?${query}`,
        hittest: "false",
        style: "opacity: 0.01; width: 1px; height: 1px;"
      });
      let settled = false;
      const finish = (result) => {
        if (settled) return;
        settled = true;
        image.DeleteAsync(0);
        try {
          item.done(result);
        } finally {
          this.next();
        }
      };
      $.RegisterEventHandler("ImageLoaded", image, () => {
        $.Schedule(SIZE_DELAY, () => finish(resultOfWidth(image.contentwidth, image.actualuiscale_x)));
      });
      $.RegisterEventHandler("ImageFailedLoad", image, () => finish("unreachable"));
      $.Schedule(COMMAND_DEADLINE, () => finish("unreachable"));
    }
  };

  // src/games.ts
  function takesInvite(snapshot) {
    return snapshot.game?.takesInvite !== false;
  }
  var LEAVE_FIRST = "Leave your current game first.";
  function solo(mode2) {
    return mode2.players[1] === 1;
  }
  var REFUSALS = {
    accepted: "",
    busy: "Finish the game that\u2019s running first.",
    gone: "That\u2019s no longer there. Pick another.",
    invalid: "Hideout couldn\u2019t read that. Try again.",
    unavailable: "This Hideout can\u2019t do that yet.",
    outdated: "Restart Deadlock to update Hideout games.",
    stale: "Hideout reconnected. Press again.",
    unreachable: "Hideout didn\u2019t answer. Is it still open?"
  };
  var OUTDATED = { title: "Restart Deadlock", body: REFUSALS.outdated };

  // src/strip.ts
  var HIDDEN = ["failed", "ended"];
  function viewStrip(snapshot, status, pressed, pointer, friends) {
    if (status === "offline" || status === "outdated") return null;
    const session2 = snapshot.game?.session;
    const host2 = snapshot.game?.host;
    if (host2 && !HIDDEN.includes(host2.phase)) return hostStrip(snapshot, host2, pressed, pointer);
    if (session2?.role === "guest" && session2.phase === "notin") return guestStrip(snapshot, session2, pressed, pointer, friends);
    return null;
  }
  function hostStrip(snapshot, host2, pressed, pointer) {
    const mode2 = snapshot.catalog?.modes.find((m) => m.id === host2.mode);
    const art2 = mode2 ? mode2.art.tile ?? mode2.art.card : null;
    const name = mode2?.name ?? "your game";
    const alone = mode2 ? solo(mode2) : false;
    if (host2.phase === "notin") {
      return { art: art2, line: [[mode2?.name ?? "Your game", true], ["is still running", false]], sub: alone ? null : inIt(host2), buttons: outButtons(host2.actions, "Stop", pressed, pointer) };
    }
    const buttons = !host2.actions.includes("stop") || pressed ? { kind: "spinner" } : pointer ? { kind: "acts", acts: [{ action: "stop", label: "Stop", primary: false }] } : { kind: "esc", words: "to stop" };
    if (host2.phase === "ready") {
      if (alone) return { art: art2, line: [["Playing", false], [name, true]], sub: null, buttons };
      const players = host2.players ? [[`\xB7 ${host2.players[0]} of ${host2.players[1]}`, false]] : [];
      return { art: art2, line: [["Hosting", false], [name, true], ...players], sub: null, buttons };
    }
    return { art: art2, line: [[name, true], [`\xB7 ${host2.words?.title ?? ""}`, false]], sub: null, buttons };
  }
  function inIt(host2) {
    if (!host2.players) return null;
    const others = Math.max(0, host2.players[0] - 1);
    return others ? `${others} ${others === 1 ? "player" : "players"} in it` : "Nobody in it right now";
  }
  function guestStrip(snapshot, session2, pressed, pointer, friends) {
    const mode2 = snapshot.catalog?.modes.find((m) => m.name === session2.mode);
    const art2 = mode2 ? mode2.art.tile ?? mode2.art.card : null;
    const hostName = session2.hostId && friends.names[session2.hostId] || session2.hostName;
    const game = hostName ? `${hostName}\u2019s ${session2.mode}` : session2.mode;
    return { art: art2, line: [["You left", false], [game, true]], sub: null, buttons: outButtons(session2.actions, "Leave", pressed, pointer) };
  }
  function outButtons(actions, end, pressed, pointer) {
    if (pressed) return { kind: "spinner" };
    const rejoin = actions.includes("play");
    if (!pointer) return { kind: "esc", words: rejoin ? "to rejoin" : `to ${end.toLowerCase()}` };
    const out = [];
    if (actions.includes("stop")) out.push({ action: "stop", label: end, primary: false });
    if (rejoin) out.push({ action: "play", label: "Rejoin", primary: true });
    return { kind: "acts", acts: out };
  }

  // src/toasts.ts
  function wantedToasts(snapshot, friends, now, arrived, hidden, screen) {
    const views = [];
    if (screen === "dashboard") return views;
    const pointer = screen === "escape";
    const joinable = takesInvite(snapshot);
    for (const invite2 of snapshot.inbox?.invites ?? []) {
      const name = friends.names[invite2.from];
      if (name === void 0 || invite2.expires <= now || hidden.has(invite2.id)) continue;
      const start = Math.min(arrived[invite2.id] ?? now, now);
      const left = invite2.expires > start ? (invite2.expires - now) / (invite2.expires - start) : 0;
      const line = [...invite2.picks, `${invite2.players[0]} of ${invite2.players[1]}`].join(" \xB7 ");
      views.push({ kind: "invite", key: invite2.id, name, invite: invite2, line, full: views.length === 0, left: Math.max(0, Math.min(1, left)), pointer, joinable, blocked: joinable ? null : LEAVE_FIRST });
    }
    const host2 = snapshot.game?.host;
    if (host2?.link) {
      const game = tagOf(host2.link);
      for (const [id, delivery] of Object.entries(host2.deliveries)) {
        const key = `unreached:${game}:${id}`;
        if (delivery !== "not-reached" || hidden.has(key)) continue;
        views.push({ kind: "unreached", key, friend: id, name: friends.names[id] ?? "A friend", link: host2.link, full: views.length === 0, pointer });
      }
    }
    return views;
  }
  function tagOf(link) {
    let hash = 2166136261;
    for (let i = 0; i < link.length; i++) hash = Math.imul(hash ^ link.charCodeAt(i), 16777619);
    return (hash >>> 0).toString(36);
  }

  // src/ui.ts
  function panel(parent, cls, type = "Panel", props = {}) {
    return $.CreatePanel(type, parent, "", { class: cls, ...props });
  }
  function label(parent, cls, text2) {
    return $.CreatePanel("Label", parent, "", { class: cls, text: text2 });
  }
  function art(parent, cls, url, style = "") {
    if (!url) return panel(parent, `${cls} NoArt`, "Panel", { style });
    return $.CreatePanel("Image", parent, "", { class: `${cls} Art`, src: url, scaling: "stretch-to-cover-preserve-aspect", style });
  }
  function button(parent, cls, text2, press) {
    const b = $.CreatePanel("Button", parent, "", { class: cls });
    label(b, "", text2);
    if (press) b.SetPanelEvent("onactivate", press);
    else b.enabled = false;
    return b;
  }
  function escHint(parent, words2) {
    const hint = panel(parent, "EscHint");
    label(panel(hint, "EscKey"), "", "Esc");
    label(hint, "EscWords", words2);
    return hint;
  }
  function spinner(parent, cls = "") {
    return panel(parent, `HSpinner ${cls}`);
  }
  var BACK = { x: 15.5, y: 12.5, w: 25, h: 37, round: 3.4, color: "#F1DFB8", rotate: -14, origin: "50% 115%" };
  var FRONT = { x: 21.3, y: 10.3, w: 29.4, h: 41.4, round: 5.4, color: "#141313", rotate: 9, origin: "50% 108%" };
  var FACE = [
    { x: 2.2, y: 2.2, w: 25, h: 37, round: 3.4, color: "#F1DFB8" },
    { x: 6.5, y: 9.6, w: 16.4, h: 16.4, round: 8.2, color: "#2F8F5B" },
    { x: 8.6, y: 14.3, w: 12.1, h: 12.1, round: 6, color: "transparent", smile: true },
    { x: 7.6, y: 30.5, w: 14.3, h: 2.3, round: 1.1, color: "#141313" },
    { x: 9.1, y: 27.5, w: 2.3, h: 4.2, round: 1.1, color: "#141313", rotate: 28 },
    { x: 18.3, y: 27.5, w: 2.3, h: 4.2, round: 1.1, color: "#141313", rotate: -28 }
  ];
  function part(parent, p, k) {
    const px = (v) => `${(v * k).toFixed(2)}px`;
    const style = [
      `x: ${px(p.x)}`,
      `y: ${px(p.y)}`,
      `width: ${px(p.w)}`,
      `height: ${px(p.h)}`,
      `background-color: ${p.color}`,
      p.round ? `border-radius: ${px(p.round)}` : "",
      p.smile ? `border-bottom: ${px(2.3)} solid #141313` : "",
      p.rotate ? `transform: rotateZ(${p.rotate}deg)` : "",
      p.origin ? `transform-origin: ${p.origin}` : ""
    ];
    return panel(parent, "MarkPart", "Panel", { style: style.filter(Boolean).join("; ") + ";" });
  }
  function mark(parent, size) {
    const k = size / 64;
    const box = panel(parent, "Mark", "Panel", { style: `width: ${size}px; height: ${size}px;` });
    part(box, BACK, k);
    const front = part(box, FRONT, k);
    for (const p of FACE) part(front, p, k);
    return box;
  }
  function paper(into, width, height, seed) {
    const edge = panel(into, "PaperEdge");
    panel(edge, "PaperBody", "Panel", { style: `x: 2px; y: 2px; width: ${width - 4}px; height: ${height - 4}px;` });
    let s = seed;
    const depth = () => {
      s = s * 16807 % 2147483647;
      return s % 3;
    };
    for (let x = 2; x < width - 2; x += 26) {
      const w = Math.min(26, width - 2 - x);
      panel(edge, "PaperTooth", "Panel", { style: `x: ${x}px; y: ${2 - depth()}px; width: ${w}px; height: 2px;` });
      panel(edge, "PaperTooth", "Panel", { style: `x: ${x}px; y: ${height - 4 + depth()}px; width: ${w}px; height: 2px;` });
    }
    for (let y = 2; y < height - 2; y += 26) {
      const h = Math.min(26, height - 2 - y);
      panel(edge, "PaperTooth", "Panel", { style: `x: ${2 - depth()}px; y: ${y}px; width: 2px; height: ${h}px;` });
      panel(edge, "PaperTooth", "Panel", { style: `x: ${width - 4 + depth()}px; y: ${y}px; width: 2px; height: ${h}px;` });
    }
    into.MoveChildBefore(edge, into.GetChild(0));
    return edge;
  }
  function tear(parent, width, seed) {
    const strip = panel(parent, "Tear");
    let s = seed;
    for (let x = 0; x < width; x += 11) {
      s = s * 16807 % 2147483647;
      panel(strip, "Tooth", "Panel", { style: `height: ${1 + s % 7}px;` });
    }
    return strip;
  }
  var Section = class {
    constructor() {
      __publicField(this, "last", "\0");
    }
    draw(into, key, build) {
      if (key === this.last) return;
      this.last = key;
      into.RemoveAndDeleteChildren();
      build(into);
    }
  };

  // src/entries/boot.ts
  function boot() {
    let top = $.GetContextPanel();
    while (top.GetParent()) top = top.GetParent();
    if (top.FindChildTraverse("HideoutRoot")) return;
    let overlay = $.GetContextPanel();
    while (overlay && overlay.id !== "CitadelHudDBOverlay") overlay = overlay.GetParent();
    const host2 = $.CreatePanel("Panel", overlay ?? $.GetContextPanel(), "HideoutRoot", { hittest: "false" });
    host2.BLoadLayout("file://{resources}/layout/hideout/root.xml", false, false);
    const find = (id) => host2.FindChildTraverse(id);
    paper(find("HODialog"), 700, 640, 11);
    let wasOnline = false;
    const link = new Link(host2, HideoutConfig, () => {
      const online = link.status === "online";
      if (online && !wasOnline) link.send({ k: "hello", self: null });
      wasOnline = online;
      draw();
    });
    const friends = new FriendWatch(find("HOFriendMenu"), draw);
    const hidden = /* @__PURE__ */ new Set();
    const arrived = {};
    const toasts = /* @__PURE__ */ new Map();
    let putAway = null;
    function drawToasts(views) {
      const stack = find("HOToasts");
      const wanted = new Set(views.map((v) => v.key));
      for (const key of toasts.keys()) {
        if (wanted.has(key)) continue;
        stack.FindChildTraverse(`HOToast-${key}`)?.DeleteAsync(0);
        toasts.delete(key);
      }
      let previous = null;
      for (const view of views) {
        const look = JSON.stringify(view.kind === "invite" ? [view.full, view.name, view.invite, view.pointer] : view);
        let shown = toasts.get(view.key);
        let toastPanel = stack.FindChildTraverse(`HOToast-${view.key}`);
        if (!shown || shown.look !== look || !toastPanel) {
          toastPanel?.DeleteAsync(0);
          toastPanel = $.CreatePanel("Panel", stack, `HOToast-${view.key}`, { class: `HOToast${view.full ? "" : " Slim"}` });
          paper(toastPanel, 580, view.full ? 130 : 70, view.key.length * 31 + 5);
          shown = { look, bar: buildToast(toastPanel, view) };
          toasts.set(view.key, shown);
        }
        if (previous) stack.MoveChildAfter(toastPanel, previous);
        previous = toastPanel;
        if (view.kind === "invite" && shown.bar) shown.bar.style.width = `${(view.left * 100).toFixed(2)}%`;
      }
    }
    function buildToast(toast, view) {
      panel(toast, "Grain", "Panel", { hittest: "false" });
      const row = panel(toast, "HOToastRow");
      const close = () => {
        hidden.add(view.key);
        if (view.kind === "invite") link.send({ k: "forget", invite: view.invite.id });
        draw();
      };
      const pic = panel(row, "HOToastArt");
      const friend = view.kind === "invite" ? view.invite.from : view.friend;
      art(pic, "HOToastPic", view.kind === "invite" ? view.invite.tile : null);
      $.CreatePanel("CitadelAvatarImage", pic, "", { class: "HOToastAvatar", steamid: friend });
      if (view.kind === "unreached") {
        line(row, [[view.name, true], ["couldn\u2019t get your invite.", false]]);
        if (!view.pointer) escHint(row, "to copy the link");
        else button(row, "SecondaryButton Outline Small Gold", "Copy link", () => {
          $.DispatchEvent("CopyStringToClipboard", view.link, host2);
          $.DispatchEvent("SteamChatWithFriend", view.friend);
          hidden.add(view.key);
          draw();
        });
      } else {
        const join2 = view.joinable ? () => {
          hidden.add(view.key);
          link.send({ k: "join", invite: view.invite.id }, (result) => {
            if (result !== "accepted") hidden.delete(view.key);
          });
          draw();
        } : null;
        if (view.full) {
          const text2 = panel(row, "HOToastText");
          const cap = panel(text2, "CapRow");
          mark(cap, 14);
          label(cap, "Cap", "Hideout invite");
          line(text2, [[view.name, true], ["invited you to", false]]);
          label(text2, "Title HOToastMode", view.invite.mode);
          label(text2, "HOToastLine", view.blocked ?? view.line);
          if (view.pointer) button(row, "Cream HOToastJoin", "Join", join2);
        } else {
          line(row, [[view.name, true], ["invited you to", false], [view.invite.mode, true]]);
          if (view.blocked) label(row, "HOToastLine", view.blocked);
          if (view.pointer) button(row, "SecondaryButton Outline Small Gold", "Join", join2);
        }
        if (!view.pointer && view.joinable) escHint(row, "to join");
      }
      if (view.pointer) {
        const x = panel(row, "HOToastClose", "Button");
        panel(x, "HOCross A");
        panel(x, "HOCross B");
        x.SetPanelEvent("onactivate", close);
      }
      return view.kind === "invite" && view.full ? panel(panel(toast, "HOToastBar"), "HOToastBarFill") : null;
    }
    function line(parent, runs) {
      const row = panel(parent, "HOToastRuns");
      for (const [text2, bold] of runs) label(row, `HOToastFrom${bold ? " Bold" : ""}`, text2);
    }
    const banner = new Section();
    const steps = new Section();
    const box = new Section();
    const acts = new Section();
    function drawJoining() {
      const view = viewJoining(link.snapshot, putAway, link.status, friends.current);
      const layer = find("HOJoining");
      layer.SetHasClass("Shown", view !== null);
      if (!view) return false;
      banner.draw(find("HOJoinBanner"), JSON.stringify([view.art, view.card.mode, view.host]), (into) => {
        art(into, "HOJoinArt", view.art);
        panel(into, "HOJoinFade");
        tear(into, 700, view.card.mode.length * 7919 + 3);
        label(panel(into, "Plate HOJoinPlate"), "", view.card.mode);
        if (view.host) {
          const host3 = panel(into, "HOJoinHost");
          if (view.host.id) $.CreatePanel("CitadelAvatarImage", host3, "", { class: "HOJoinAvatar", steamid: view.host.id });
          label(host3, "HOJoinHostName", view.host.text);
        }
      });
      find("HOJoinTitle").text = view.title;
      find("HOJoinLine").text = view.line;
      steps.draw(find("HOSteps"), JSON.stringify([view.steps, view.busy, view.card.phase]), (into) => {
        const rows = view.busy ? [...view.steps, { label: view.busy, note: null, progress: null, status: "running" }] : view.steps;
        for (const step2 of rows) {
          const look = step2.status === "done" ? "Done" : step2.status === "running" ? "Running" : "Waiting";
          const row = panel(into, `HOStep ${look}`);
          const icon = panel(row, "HOStepIcon");
          if (step2.status === "done") panel(icon, "HOCheck");
          if (step2.status === "running") spinner(icon);
          const text2 = panel(row, "HOStepText");
          label(text2, "HOStepLabel", step2.label);
          if (step2.note) label(text2, "HOStepNote", step2.note);
          if (step2.status === "running" && step2.progress !== null) {
            panel(panel(text2, "HOProgress"), "HOProgressFill", "Panel", { style: `width: ${(step2.progress * 100).toFixed(1)}%;` });
          }
        }
      });
      box.draw(find("HOBox"), JSON.stringify(view.box), (into) => {
        if (!view.box) return;
        const b = panel(into, `HOBox${view.box.tone === "note" ? " Note" : ""}`);
        label(b, "HOBoxTitle", view.box.title);
        label(b, "HOBoxBody", view.box.body);
      });
      acts.draw(find("HOActs"), JSON.stringify(view.buttons), (into) => {
        for (const b of view.buttons) button(into, b.primary ? "Cream HORetry" : "SecondaryButton Outline", b.label, () => press(b, view.card.phase));
      });
      return true;
    }
    function press(b, phase) {
      if (b.kind === "confirm") return link.send({ k: "confirm", restart: b.restart });
      if (b.action === "dismiss") {
        putAway = phase;
        draw();
      }
      link.send({ k: "act", action: b.action });
    }
    function screen() {
      const status = top.FindChildTraverse("ClientStatus");
      const escape2 = status?.BHasClass("InEscapeMenu") ?? false;
      const dashboard = status?.BHasClass("InDashboard") ?? false;
      return {
        room: top.FindChildTraverse("HudAndDBOverlay")?.BHasClass("InHideout") ?? false,
        pointer: escape2 || dashboard,
        escape: escape2,
        screen: escape2 ? "escape" : dashboard ? "dashboard" : "playing"
      };
    }
    const strip = new Section();
    let stripPressed = null;
    function drawStrip(where) {
      if (stripPressed && stripPressed.in !== link.snapshot.game) stripPressed = null;
      const view = viewStrip(link.snapshot, link.status, stripPressed !== null, where.pointer, friends.current);
      const into = find("HOStrip");
      into.SetHasClass("Shown", view !== null && where.room && !where.pointer);
      strip.draw(into, JSON.stringify(view), (into2) => {
        if (view) buildStrip(into2, view);
      });
    }
    function buildStrip(into, view) {
      const card = panel(into, "HOToast Slim HOStripCard");
      paper(card, 580, 70, 23);
      panel(card, "Grain", "Panel", { hittest: "false" });
      const row = panel(card, "HOToastRow");
      art(panel(row, "HOToastArt"), "HOToastPic", view.art);
      const text2 = panel(row, "HOStripText");
      line(text2, view.line);
      if (view.sub) label(text2, "HOToastLine", view.sub);
      const buttons = view.buttons;
      if (buttons.kind === "esc") escHint(row, buttons.words);
      else if (buttons.kind === "spinner") spinner(button(row, "SecondaryButton Outline Small Gold HOStop Busy", "", null));
      else for (const act of buttons.acts) button(row, act.primary ? "Cream HOStripPrimary" : "SecondaryButton Outline Small Gold HOStop", act.label, () => stripAct(act));
    }
    function stripAct(act) {
      stripPressed = { in: link.snapshot.game };
      link.send({ k: "act", action: act.action }, (result) => {
        if (result === "accepted") return;
        stripPressed = null;
        draw();
      });
      draw();
    }
    function draw() {
      var _a;
      const now = Date.now();
      for (const invite2 of link.snapshot.inbox?.invites ?? []) arrived[_a = invite2.id] ?? (arrived[_a] = now);
      if (link.snapshot.game?.join?.phase !== putAway) putAway = null;
      const joining = drawJoining();
      const where = screen();
      const corner = find("HOCorner");
      corner.SetHasClass("Away", joining);
      corner.SetHasClass("InEscape", where.escape);
      drawToasts(wantedToasts(link.snapshot, friends.current, now, arrived, hidden, where.screen));
      drawStrip(where);
    }
    function tick() {
      if (!host2.IsValid()) return;
      draw();
      $.Schedule(0.25, tick);
    }
    tick();
    $.Msg("[hideout] in-game UI started");
  }
  boot();
})();
