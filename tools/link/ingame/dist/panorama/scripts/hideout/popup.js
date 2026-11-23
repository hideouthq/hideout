"use strict";
(() => {
  var __defProp = Object.defineProperty;
  var __defNormalProp = (obj2, key, value) => key in obj2 ? __defProp(obj2, key, { enumerable: true, configurable: true, writable: true, value }) : obj2[key] = value;
  var __publicField = (obj2, key, value) => __defNormalProp(obj2, typeof key !== "symbol" ? key + "" : key, value);

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
      phase: oneOf(["finding", "review", "cleanup", "progress", "stopping", "restart", "restarting", "notin", "failed"])(o.phase),
      steps: list(o.steps, step),
      words: opt(o.words, words),
      busy: opt(o.busy, str),
      restart: opt(o.restart, restart),
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
          return { t: "game", host: opt(o.host, host), join: opt(o.join, join), restartAhead: opt(o.restartAhead, restart) };
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
  function art(parent, cls, url, style = "") {
    if (!url) return panel(parent, `${cls} NoArt`, "Panel", { style });
    return $.CreatePanel("Image", parent, "", { class: `${cls} Art`, src: url, scaling: "stretch-to-cover-preserve-aspect", style });
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

  // src/entries/popup.ts
  var dialog = $.GetContextPanel();
  dialog.style.padding = "32px 0px 0px 0px";
  var band = $.CreatePanel("Button", dialog, "HideoutBand");
  band.BLoadLayout("file://{resources}/layout/hideout/band.xml", false, false);
  var cap = band.FindChildTraverse("HBandCap");
  cap.MoveChildBefore(mark(cap, 18), cap.GetChild(0));
  band.SetPanelEvent("onactivate", () => {
    $.DispatchEvent("UIShowCustomLayoutPopupParameters", "HideoutGames", "file://{resources}/layout/hideout/games.xml", "tab=games");
  });
  var ANGLES = [
    [0, -10],
    [30, 2],
    [62, 12]
  ];
  var hand = new Section();
  var link = new Link(band, HideoutConfig, draw);
  function draw() {
    const modes = link.snapshot.catalog?.modes ?? [];
    const shown = modes.slice(0, 3);
    hand.draw(band.FindChildTraverse("HBandHand"), JSON.stringify(shown.map((m) => m.art)), (into) => {
      shown.forEach((mode2, i) => {
        const [x, angle] = ANGLES[i];
        art(into, "HBandCard", mode2.art.card ?? mode2.art.tile ?? mode2.art.backdrop, `x: ${x}px; transform: rotateZ(${angle}deg);`);
      });
    });
    const offline = link.status === "offline";
    band.FindChildTraverse("HBandSub").text = offline ? "Open Hideout to play" : "Community modes to play with friends";
  }
  draw();
})();
