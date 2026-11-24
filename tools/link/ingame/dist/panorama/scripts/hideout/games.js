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
  var IN_DEADLOCK = [
    { raw: /^Main Menu/i, text: "In the main menu", inMatch: false },
    { raw: /^Finding Match/i, text: "Finding a match", inMatch: false },
    { raw: /^Picking Hero/i, text: "In a match", inMatch: true },
    { raw: /^Playing:/i, text: "In a match", inMatch: true }
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
  function friendPanels(root2, out = []) {
    if (root2.paneltype === "CitadelFriend") out.push(root2);
    for (let i = 0; i < root2.GetChildCount(); i++) {
      const child = root2.GetChild(i);
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

  // src/games.ts
  function initialGames(tab) {
    return { tab, draft: { mode: null, picks: {}, listing: "invite-only", invitees: [] }, request: { kind: "idle" }, serversAnswer: null };
  }
  function runningHost(snapshot) {
    const host2 = snapshot.game?.host ?? null;
    return host2 && host2.phase !== "ended" ? host2 : null;
  }
  function selectedMode(state2, snapshot) {
    const modes = snapshot.catalog?.modes ?? [];
    return modes.find((m) => m.id === state2.draft.mode) ?? modes[0] ?? null;
  }
  function pickOf(setting, picks) {
    const picked = picks[setting.key];
    return picked !== void 0 && setting.options.some((o) => o.value === picked) ? picked : setting.default;
  }
  function reduceGames(state2, event, snapshot, friends2) {
    const host2 = runningHost(snapshot);
    const draft = state2.draft;
    const sending = (what, command) => [
      { ...state2, request: { kind: "sending", what } },
      [{ kind: "send", what, command }]
    ];
    switch (event.kind) {
      case "tab":
        return [{ ...state2, tab: event.tab, request: state2.request.kind === "refused" ? { kind: "idle" } : state2.request }, []];
      case "mode":
        return [{ ...state2, draft: { ...draft, mode: event.mode, picks: {} }, request: { kind: "idle" } }, []];
      case "pick":
        return [{ ...state2, draft: { ...draft, picks: { ...draft.picks, [event.key]: event.value } } }, []];
      case "listing":
        if (host2) return host2.listing === event.listing ? [state2, []] : sending("listing", { k: "listing", listing: event.listing });
        return [{ ...state2, draft: { ...draft, listing: event.listing } }, []];
      case "friend": {
        const inMatch = friends2.online.some((f) => f.id === event.id && f.inMatch);
        const button2 = friendButton(event.id, inMatch, state2, snapshot);
        if (button2 === "in-match") return [state2, []];
        if (host2) {
          if (button2 === "invite") return sending("invite", { k: "invite", to: [event.id] });
          return button2 === "copy-link" && host2.link ? [state2, [{ kind: "copy-and-chat", link: host2.link, friend: event.id }]] : [state2, []];
        }
        const invitees = draft.invitees.includes(event.id) ? draft.invitees.filter((id) => id !== event.id) : [...draft.invitees, event.id];
        return [{ ...state2, draft: { ...draft, invitees } }, []];
      }
      case "play": {
        const mode2 = selectedMode(state2, snapshot);
        if (!mode2 || host2 || state2.request.kind === "sending") return [state2, []];
        const picks = {};
        for (const setting of mode2.settings) picks[setting.key] = pickOf(setting, draft.picks);
        return sending("play", {
          k: "host",
          mode: mode2.id,
          picks,
          listing: draft.listing,
          invite: draftInvitees(state2, snapshot),
          restart: snapshot.game?.restartAhead?.reason ?? null
        });
      }
      case "act":
        return sending("act", { k: "act", action: event.action });
      case "join":
        return state2.request.kind === "sending" ? [state2, []] : sending("join", { k: "join", server: event.server });
      case "settled":
        if (event.result !== "accepted") return [{ ...state2, request: { kind: "refused", what: event.what, result: event.result } }, []];
        if (event.what === "play" && !host2) return [state2, []];
        return [{ ...state2, request: { kind: "idle" } }, []];
      case "servers-answered":
        return [{ ...state2, serversAnswer: event.result }, []];
      case "seen": {
        const request = state2.request;
        if (request.kind === "sending" && request.what === "play" && host2) return [{ ...state2, request: { kind: "idle" } }, []];
        if (request.kind === "refused" && request.what === "join" && snapshot.game?.join) return [{ ...state2, request: { kind: "idle" } }, []];
        return [state2, []];
      }
    }
  }
  var KINDS = { duel: "Duel", teams: "Teams", "free-for-all": "Free-for-all", "co-op": "Co-op" };
  function playersText([least, most]) {
    return least === most ? `${most} players` : `${least}\u2013${most} players`;
  }
  function sizeText(bytes) {
    if (bytes < 1e6) return `${Math.max(1, Math.round(bytes / 1e3))} KB`;
    const mb = bytes / 1e6;
    return `${mb < 10 ? Math.round(mb * 10) / 10 : Math.round(mb)} MB`;
  }
  function goalText(goal, settings2, picks) {
    return goal.replace(/\{([^{}]+)\}/g, (raw, key) => {
      const setting = settings2.find((s) => s.key === key);
      const value = setting && pickOf(setting, picks);
      return setting?.options.find((o) => o.value === value)?.label ?? raw;
    });
  }
  function chipsOf(mode2, picks) {
    const chips2 = [];
    const kind = mode2.kind && KINDS[mode2.kind];
    if (kind) chips2.push({ icon: "kind", text: kind });
    chips2.push({ icon: "players", text: playersText(mode2.players) });
    const minutes = mode2.minutes;
    if (minutes) chips2.push({ icon: "time", text: minutes[0] === minutes[1] ? `~${minutes[0]} min` : `${minutes[0]}\u2013${minutes[1]} min` });
    if (mode2.goal) chips2.push({ icon: "goal", text: goalText(mode2.goal, mode2.settings, picks) });
    return chips2;
  }
  function metaLine(mode2) {
    const kind = mode2.kind && KINDS[mode2.kind];
    return [kind, playersText(mode2.players)].filter(Boolean).join(" \xB7 ");
  }
  var ACTION_LABELS = { stop: "Stop game", restart: "Restart Deadlock", play: "Play", again: "Try again", dismiss: "Close" };
  var REFUSALS = {
    accepted: "",
    busy: "Finish the game that\u2019s running first.",
    gone: "That\u2019s no longer there. Pick another.",
    invalid: "Hideout couldn\u2019t read that. Try again.",
    unavailable: "This Hideout can\u2019t do that yet.",
    outdated: "Restart Deadlock to update Hideout games.",
    unreachable: "Hideout didn\u2019t answer. Is it still open?"
  };
  function refusalText(result) {
    return REFUSALS[result];
  }
  function stepLine(host2) {
    const step2 = host2.steps.find((s) => s.status === "running");
    return step2 ? [step2.label, step2.note].filter(Boolean).join(" \xB7 ") : null;
  }
  function draftInvitees(state2, snapshot) {
    return snapshot.hello?.social === "blocked" ? [] : state2.draft.invitees;
  }
  function blockedLine(snapshot) {
    const hello = snapshot.hello;
    if (hello?.social !== "blocked" || !hello.supportId) return null;
    return { text: `Invites are blocked on this PC. Send support this ID to reset it: ${hello.supportId}`, id: hello.supportId };
  }
  function inviteHint(state2, snapshot) {
    if (state2.draft.invitees.length && snapshot.hello?.social === "other-account") return "Open Hideout with the Steam account playing Deadlock to send invites.";
    const n = draftInvitees(state2, snapshot).length;
    if (n) return `${n} ${n === 1 ? "friend gets" : "friends get"} an invite when your game is ready.`;
    return `Your PC hosts this game. ${state2.draft.listing === "public" ? "Anyone in Servers can join." : "Invite friends here or share the code."}`;
  }
  var OFFLINE = { title: "Open Hideout to play", body: "Hideout games run from the Hideout app on this PC." };
  var OUTDATED = { title: "Restart Deadlock", body: REFUSALS.outdated };
  function emptyList(link2) {
    if (link2 === "offline") return { kind: "note", ...OFFLINE };
    if (link2 === "outdated") return { kind: "note", ...OUTDATED };
    return { kind: "loading" };
  }
  function playButton(state2, snapshot, link2, mode2) {
    if (link2 === "offline") return { kind: "blocked", label: OFFLINE.title, hint: OFFLINE.body };
    if (link2 === "outdated") return { kind: "blocked", label: OUTDATED.title, hint: OUTDATED.body };
    if (link2 === "connecting" && !snapshot.catalog) return { kind: "busy", label: "Reaching Hideout\u2026", hint: "" };
    const host2 = runningHost(snapshot);
    if (host2) {
      const words2 = host2.words;
      switch (host2.phase) {
        case "starting":
        case "restarting":
        case "entering":
          return { kind: "busy", label: "Starting\u2026", hint: stepLine(host2) ?? words2?.body ?? "" };
        case "ending":
          if (!host2.actions.length) return { kind: "busy", label: "Stopping\u2026", hint: words2?.body ?? "" };
      }
      const action2 = host2.actions[0];
      if (!action2) return { kind: "busy", label: "Starting\u2026", hint: words2?.body ?? "" };
      return { kind: "act", label: ACTION_LABELS[action2], action: action2, hint: (host2.phase === "failed" ? words2?.title : words2?.body) ?? "" };
    }
    const join2 = snapshot.game?.join;
    const label2 = mode2 ? `Play ${mode2.name}` : "Play";
    if (join2) return { kind: "blocked", label: label2, hint: join2.title };
    if (state2.request.kind === "sending" && state2.request.what === "play") return { kind: "busy", label: "Starting\u2026", hint: "" };
    if (!mode2) return { kind: "blocked", label: label2, hint: "" };
    if (state2.request.kind === "refused" && state2.request.what === "play") return { kind: "play", label: label2, hint: REFUSALS[state2.request.result] };
    return { kind: "play", label: label2, hint: snapshot.game?.restartAhead?.text ?? inviteHint(state2, snapshot) };
  }
  function friendButton(id, inMatch, state2, snapshot) {
    const button2 = inviteButton(id, state2, snapshot);
    return inMatch && button2 !== "invited" ? "in-match" : button2;
  }
  function inviteButton(id, state2, snapshot) {
    const host2 = runningHost(snapshot);
    const blocked = snapshot.hello?.social === "blocked";
    if (!host2) return blocked ? "off" : state2.draft.invitees.includes(id) ? "invited" : "invite";
    if (blocked || host2.deliveries[id] === "not-reached") return host2.link ? "copy-link" : "off";
    if (host2.invitees.includes(id)) return "invited";
    if (snapshot.hello?.social === "on") return "invite";
    return host2.link ? "copy-link" : "off";
  }
  var REGIONS = {
    ams: "EU West",
    lhr: "EU West",
    par: "EU West",
    mad: "EU West",
    fra: "EU Central",
    waw: "EU East",
    nyc: "NA East",
    mia: "NA East",
    tor: "NA East",
    ord: "NA Central",
    dfw: "NA Central",
    den: "NA Central",
    sfo: "NA West",
    sea: "NA West",
    lax: "NA West",
    hnl: "Hawaii",
    sao: "South America",
    jnb: "Africa South",
    nai: "Africa East",
    dbi: "Middle East",
    blr: "India",
    sin: "Southeast Asia",
    hkg: "Asia East",
    tok: "Japan",
    syd: "Oceania"
  };
  var SERVER_STATUS = { lobby: "In the lobby", match: "In a match", full: "Full" };
  var SERVER_NOTES = {
    idle: null,
    loading: null,
    ready: "No public games right now. Choose Public before you play to list yours here.",
    unreachable: "Hideout couldn\u2019t reach the server list. It tries again in a moment.",
    unavailable: "This Hideout can\u2019t list servers."
  };
  function serverLine(row) {
    const [now, most] = row.players;
    return {
      row,
      name: row.host ? `${row.host}\u2019s ${row.mode}` : row.mode,
      sub: [row.mode, ...row.picks, row.host ? `Hosted by ${row.host}` : "Hideout server"].join(" \xB7 "),
      region: REGIONS[row.region] ?? row.region.toUpperCase(),
      fill: most > 0 ? Math.min(1, now / most) : 0,
      ping: row.ping === null ? null : { text: `${row.ping} ms`, tone: row.ping < 60 ? "good" : row.ping < 100 ? "fair" : "poor" },
      status: SERVER_STATUS[row.status],
      joinable: row.status !== "full"
    };
  }
  function viewServers(snapshot, answer = null) {
    const servers2 = snapshot.servers;
    if (!servers2) {
      if (answer === "unavailable") return { state: "unavailable", lines: [], note: SERVER_NOTES.unavailable };
      return answer && answer !== "accepted" ? { state: "unreachable", lines: [], note: REFUSALS[answer] } : { state: "idle", lines: [], note: null };
    }
    const lines = servers2.rows.map(serverLine);
    return { state: servers2.state, lines, note: lines.length ? null : SERVER_NOTES[servers2.state] };
  }
  function viewGames(state2, snapshot, link2, friends2, me2) {
    const modes = snapshot.catalog?.modes ?? [];
    const selected = selectedMode(state2, snapshot);
    const host2 = runningHost(snapshot);
    const listing2 = host2 ? host2.listing : state2.draft.listing;
    const servers2 = viewServers(snapshot, state2.serversAnswer);
    return {
      tab: state2.tab,
      counts: { games: modes.length, servers: snapshot.servers?.state === "ready" ? servers2.lines.length : null },
      servers: servers2,
      link: link2,
      rows: modes.map((mode2) => ({ mode: mode2, selected: mode2 === selected, meta: metaLine(mode2) })),
      empty: modes.length ? null : emptyList(link2),
      selected,
      byline: selected ? [`by ${selected.by.join(", ")}`, selected.bytes === null ? null : sizeText(selected.bytes)].filter(Boolean).join(" \xB7 ") : "",
      chips: selected ? chipsOf(selected, state2.draft.picks) : [],
      settings: selected ? selected.settings.map((setting) => ({ setting, value: pickOf(setting, state2.draft.picks) })) : [],
      listing: listing2,
      listingLine: listing2 === "public" ? me2 && selected ? `Listed in Servers as \u201C${me2}\u2019s ${selected.name}\u201D` : "Listed in Servers for anyone to join" : "Only friends you invite, or anyone with the code",
      friends: friends2.online.map((friend) => ({ friend, button: friendButton(friend.id, friend.inMatch, state2, snapshot) })),
      blocked: blockedLine(snapshot),
      offline: friends2.offline,
      play: playButton(state2, snapshot, link2, selected)
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
  var RESULTS = ["accepted", "busy", "gone", "invalid", "unavailable", "outdated"];
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
            social: oneOf(["on", "off", "other-account", "blocked"])(o.social),
            supportId: opt(o.supportId, (v) => str(v))
          };
        case "catalog":
          return { t: "catalog", modes: list(o.modes, mode) };
        case "game":
          return { t: "game", host: opt(o.host, host), join: opt(o.join, join), session: opt(o.session, session), restartAhead: opt(o.restartAhead, restart) };
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

  // src/entries/games.ts
  var root = $.GetContextPanel();
  var find = (id) => root.FindChildTraverse(id);
  var me = find("HGMe");
  var state = initialGames(root.GetAttributeString("tab", "games") === "servers" ? "servers" : "games");
  paper(root, 1440, 862, 7);
  var link = new Link(root, HideoutConfig, () => dispatch({ kind: "seen" }));
  var friends = new FriendWatch(find("HGFriendMenu"), render);
  for (const [id, size] of [["HGTitleRow", 40], ["HGMore", 16]]) {
    const row = find(id);
    row.MoveChildBefore(mark(row, size), row.GetChild(0));
  }
  find("HGTabGames").SetPanelEvent("onactivate", () => dispatch({ kind: "tab", tab: "games" }));
  find("HGTabServers").SetPanelEvent("onactivate", () => dispatch({ kind: "tab", tab: "servers" }));
  function readServers() {
    link.send({ k: "servers" }, (result) => dispatch({ kind: "servers-answered", result }));
  }
  function dispatch(event) {
    const [next, effects] = reduceGames(state, event, link.snapshot, friends.current);
    const wasServers = state.tab === "servers";
    state = next;
    effects.forEach(run);
    if (state.tab === "servers" && !wasServers) readServers();
    render();
  }
  function run(effect) {
    switch (effect.kind) {
      case "send":
        link.send(effect.command, (result) => dispatch({ kind: "settled", what: effect.what, result }));
        break;
      case "copy-and-chat":
        $.DispatchEvent("CopyStringToClipboard", effect.link, root);
        $.DispatchEvent("SteamChatWithFriend", effect.friend);
        break;
    }
  }
  var list2 = new Section();
  var banner = new Section();
  var chips = new Section();
  var settings = new Section();
  var friendRows = new Section();
  var hint = new Section();
  var play = new Section();
  var servers = new Section();
  function render() {
    const view = viewGames(state, link.snapshot, link.status, friends.current, textOf(me));
    root.SetHasClass("Games", view.tab === "games");
    root.SetHasClass("Servers", view.tab === "servers");
    find("HGTabGames").SetHasClass("On", view.tab === "games");
    find("HGTabServers").SetHasClass("On", view.tab === "servers");
    find("HGGamesCount").text = view.counts.games ? String(view.counts.games) : "";
    find("HGServersCount").text = view.counts.servers === null ? "" : String(view.counts.servers);
    drawList(view);
    drawDetail(view);
    drawServers(view.servers, view.link === "online" && link.snapshot.hello?.social !== void 0);
  }
  function drawList(view) {
    const key = JSON.stringify([view.empty, view.rows.map((r) => [r.mode.id, r.mode.name, r.mode.tagline, r.meta, r.mode.art, r.selected])]);
    list2.draw(find("HGList"), key, (into) => {
      const empty = view.empty;
      if (empty?.kind === "loading") spinner(panel(into, "HGRow"), "HGEmptySpinner");
      if (empty?.kind === "note") {
        const row = panel(into, "HGRow HGEmpty");
        mark(row, 48);
        const text2 = panel(row, "HGRowText");
        label(text2, "Title HGRowName", empty.title);
        label(text2, "HGRowDesc Wrap", empty.body);
      }
      for (const row of view.rows) {
        const b = panel(into, `HGRow${row.selected ? " On" : ""}`, "Button");
        art(b, "HGThumb", row.mode.art.card ?? row.mode.art.tile ?? row.mode.art.backdrop);
        const text2 = panel(b, "HGRowText");
        label(text2, "Title HGRowName", row.mode.name);
        label(text2, "HGRowDesc", row.mode.tagline);
        label(text2, "HGRowMeta", row.meta);
        b.SetPanelEvent("onactivate", () => dispatch({ kind: "mode", mode: row.mode.id }));
      }
    });
  }
  var FRIEND_BUTTONS = {
    invite: { caption: "Invite", look: "Gold", pressable: true },
    invited: { caption: "Invited", look: "Done", pressable: true },
    "copy-link": { caption: "Copy link", look: "Gold", pressable: true },
    "in-match": { caption: "In match", look: "", pressable: false },
    off: { caption: "Invite", look: "", pressable: false }
  };
  function drawDetail(view) {
    const mode2 = view.selected;
    find("HGDetail").visible = mode2 !== null;
    if (!mode2) return;
    banner.draw(find("HGBanner"), JSON.stringify([mode2.id, mode2.name, mode2.art, view.byline]), (into) => {
      art(into, "HGBannerArt", mode2.art.backdrop ?? mode2.art.card ?? mode2.art.tile);
      panel(into, "HGBannerFade");
      tear(into, 830, mode2.id.length * 7919 + 17);
      label(panel(into, "Plate HGBannerPlate"), "", mode2.name);
      label(into, "HGBannerBy", view.byline);
    });
    find("HGDesc").text = mode2.tagline;
    chips.draw(find("HGChips"), JSON.stringify(view.chips), (into) => {
      for (const chip of view.chips) {
        const c = panel(into, "HGChip");
        const icon = panel(c, `HGChipIcon ${chip.icon[0].toUpperCase()}${chip.icon.slice(1)}`);
        if (chip.icon === "kind") {
          panel(icon, "FlagPole");
          panel(icon, "FlagCloth");
        }
        label(c, "", chip.text);
      }
    });
    settings.draw(find("HGSettings"), JSON.stringify([view.settings, view.listing, view.listingLine]), (into) => {
      for (const { setting, value } of view.settings) {
        const options = setting.options.map((o) => ({ label: o.label, on: o.value === value, press: () => dispatch({ kind: "pick", key: setting.key, value: o.value }) }));
        settingRow(into, setting.label, setting.hint ?? "", options);
      }
      const listings = [
        ["invite-only", "Invite only"],
        ["public", "Public"]
      ];
      settingRow(
        into,
        "Who can join",
        view.listingLine,
        listings.map(([listing2, text2]) => ({ label: text2, on: view.listing === listing2, press: () => dispatch({ kind: "listing", listing: listing2 }) }))
      );
    });
    find("HGOnline").text = `${view.friends.length} online`;
    friendRows.draw(find("HGFriends"), JSON.stringify([view.friends, view.offline, view.blocked]), (into) => {
      const blocked = view.blocked;
      if (blocked) {
        const row = panel(into, "HGFriend");
        label(row, "HGBlockedText", blocked.text);
        button(row, "SecondaryButton Outline Small Gold", "Copy", () => $.DispatchEvent("CopyStringToClipboard", blocked.id, root));
      }
      if (!view.friends.length) label(into, "HGFriendsEmpty", "No friends online right now.");
      for (const { friend, button: kind } of view.friends) {
        const row = panel(into, "HGFriend");
        $.CreatePanel("CitadelAvatarImage", row, "", { class: "HGAvatar", steamid: friend.id });
        const text2 = panel(row, "HGFriendText");
        label(text2, "HGFriendName", friend.name);
        label(text2, `HGFriendStatus${friend.inMatch ? " InMatch" : ""}`, friend.status);
        const { caption, look, pressable } = FRIEND_BUTTONS[kind];
        button(row, `SecondaryButton Outline Small ${look}`, caption, pressable ? () => dispatch({ kind: "friend", id: friend.id }) : null);
      }
      if (view.offline) label(into, "HGOffline", `${view.offline} ${view.offline === 1 ? "friend" : "friends"} offline`);
    });
    hint.draw(find("HGHint"), view.play.hint, (into) => {
      if (!view.play.hint) return;
      mark(into, 20);
      label(into, "HGHintText", view.play.hint);
    });
    play.draw(find("HGPlay"), JSON.stringify(view.play), (into) => {
      const inner = panel(into, "HGPlayInner");
      if (view.play.kind === "busy") spinner(inner, "Dark");
      label(inner, "", view.play.label);
      const p = view.play;
      into.enabled = p.kind === "play" || p.kind === "act";
      into.SetHasClass("Busy", p.kind === "busy");
      into.SetPanelEvent("onactivate", () => {
        if (p.kind === "play") dispatch({ kind: "play" });
        else if (p.kind === "act") dispatch({ kind: "act", action: p.action });
      });
    });
  }
  function settingRow(into, name, hintText, options) {
    const row = panel(into, "HGSettingRow");
    const text2 = panel(row, "HGSettingText");
    label(text2, "HGSettingName", name);
    if (hintText) label(text2, "HGSettingHint", hintText);
    const seg = panel(row, "HGSeg");
    for (const option of options) button(seg, `HGSegOption${option.on ? " On" : ""}`, option.label, option.press);
  }
  function drawServers(view, reachable) {
    const refusal = state.request.kind === "refused" && state.request.what === "join" ? state.request.result : null;
    servers.draw(find("HGServerRows"), JSON.stringify([view, reachable, refusal]), (into) => {
      if ((view.state === "idle" || view.state === "loading") && !view.lines.length) spinner(panel(into, "HGServersLoading"));
      for (const line of view.lines) {
        const row = panel(into, `HGServer${line.joinable ? "" : " Full"}`);
        const name = panel(row, "HGColName HGServerName");
        art(name, "HGServerThumb", line.row.tile);
        const text3 = panel(name, "HGServerText");
        label(text3, "HGServerTitle", line.name);
        label(text3, "HGServerSub", line.sub);
        label(row, "HGColRegion HGServerCell", line.region);
        const players = panel(row, "HGColPlayers HGServerPlayers");
        panel(panel(players, "HGBar"), "HGBarFill", "Panel", { style: `width: ${Math.round(line.fill * 100)}%;` });
        label(players, "HGServerCell", `${line.row.players[0]} / ${line.row.players[1]}`);
        label(row, `HGColPing HGServerCell HGPing ${line.ping ? line.ping.tone[0].toUpperCase() + line.ping.tone.slice(1) : ""}`, line.ping?.text ?? "\u2013");
        label(row, "HGColStatus HGServerStatus", line.status);
        button(row, `SecondaryButton Outline Small ${line.joinable ? "Gold" : ""} HGServerJoin`, line.joinable ? "Join" : "Full", line.joinable ? () => dispatch({ kind: "join", server: line.row.id }) : null);
      }
      const note = find("HGServerNote");
      note.RemoveAndDeleteChildren();
      const text2 = refusal ? refusalText(refusal) : view.note ?? "Hideout servers are always on. Player servers close when the host leaves.";
      mark(note, 16);
      label(note, "HGMoreText", reachable || view.note ? text2 : "Open Hideout to see servers.");
    });
  }
  function refreshServers() {
    if (!root.IsValid()) return;
    if (state.tab === "servers" && link.status === "online") readServers();
    $.Schedule(15, refreshServers);
  }
  $.Schedule(15, refreshServers);
  if (state.tab === "servers") readServers();
  render();
})();
