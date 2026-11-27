// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
/*
 * The home-page film: an 80-second animated explainer of what Yukta is, in 2D motion graphics (inline SVG).
 * Mintlify loads every .js file in docs/ on every page, so this script only acts when the page has an element
 * with id "yk-film".
 *
 * Every frame is a pure function of the time t, so play, pause, scrubbing and chapter jumps all go through one
 * render(t). Colours come from CSS variables, so the film follows the docs' light and dark theme. The narration
 * is in the captions. With reduced motion the film does not start by itself.
 *
 *   0  the problem       a dozen release steps by hand; an AI agent does them fast but skips the tests
 *  19  meet Yukta        the logo builds: the workflow is in charge, not the prompt
 *  25  build             you ask your AI; it builds the workflow in Yukta over MCP (or the CLI or REST), step by step
 *  35  run               a script runs the tests, an agent session fixes them on a budget, it cannot skip ahead
 *  47  control           you or your AI pause, step and restart the run
 *  52  supervise         a supervisor pauses at 80% of the budget; your AI resumes through it; a person approves
 *  66  any trigger       your AI, a script, a webhook, an API call, a schedule or you in the web UI starts it; Yukta runs it
 *  73  end card
 */
(function () {
  "use strict";
  if (window.__ykFilm) return;
  window.__ykFilm = true;

  var current = null;
  function check() {
    var el = document.getElementById("yk-film");
    if (!el || el === current || el.dataset.ykFilm) return;
    current = el;
    el.dataset.ykFilm = "1";
    try {
      build(el);
    } catch (e) {
      console.warn("[yk-film]", e);
    }
  }

  // --------------------------------------------------------------------------- script

  var DUR = 80;
  // *words* are emphasised in blue, _words_ in orange.
  var CAPS = [
    [0, 4.5, "Every process is a *dozen small steps.*"],
    [4.5, 9, "Onboarding, invoices, releases, reviews: every time, in the *right order.*"],
    [9, 13.4, "So you hand it to your *AI agent.*"],
    [13.4, 19, "It's fast. But it _skips_ the steps it thinks don't matter, and you _can't stop it._"],
    [19, 25, "Meet *Yukta.* The workflow is in charge, not the prompt."],
    [25, 29, "You ask your AI to *build the workflow* in Yukta."],
    [29, 35, "It writes it over *MCP,* step by step. Yukta *checks* it before it saves."],
    [35, 38.4, "Every run follows *the arrows.* A script checks the input."],
    [38.4, 44.6, "One step is a whole *AI agent:* Claude Code, Codex or *one you built,* working inside the step on a *budget.*"],
    [44.6, 47.4, "It _can't skip ahead._ There's no arrow for that."],
    [47.4, 52.4, "*You* in the web UI, or *your AI* over MCP, the CLI or REST: pause, step or restart any run."],
    [52.4, 57.4, "A *supervisor* watches the whole run, and acts on its own rules."],
    [57.4, 61.4, "Your AI steers *through it,* here over REST. Every command is *checked and recorded.*"],
    [61.4, 66, "A high-risk question? Only *a person* can answer it."],
    [66, 69.5, "*Anything* can start a run: your AI, a script, a webhook, an API call, a schedule or *you* in the web UI."],
    [69.5, 73, "The trigger decides how it starts. *Yukta* runs every step. *AI-first,* not AI-dependent."],
    [73, 80, "*Yukta.* Workflows your AI agents can't skip."],
  ];
  // Chapter title cards, shown for a few seconds as each chapter starts.
  var KICK = ["The problem", "Meet Yukta", "Build it with your AI", "Every step, in order", "You stay in control", "Supervised", "AI-first, not AI-dependent"];
  var CHAPTERS = [[0, "The problem"], [19, "Meet Yukta"], [25, "Build"], [35, "Run"], [47.4, "Control"], [52.4, "Supervise"], [66, "Any trigger"], [73, "Yukta"]];

  var NX = [500, 625, 765, 905, 1030, 1150], NY = 330;
  // What can start a run, and where each chip sits in the row.
  var TRIG = ["AI", "Script", "Webhook", "API call", "Web UI", "Schedule"], TGX = [];
  TRIG.reduce(function (x, l) { TGX.push(x + chipW(l) / 2); return x + chipW(l) + 12; }, 572);
  var NODES = [
    { t: "Start", p: "API_TRIGGER", c: "orange", i: "play" },
    { t: "Check the input", p: "PROCESS_EXECUTOR", c: "slate", i: "term" },
    { t: "Agent session", p: "AGENT_SESSION", c: "blue", i: "spark" },
    { t: "All valid?", p: "GATE", c: "slate", i: "check" },
    { t: "Ask a person", p: "ASK", c: "orange", i: "ask" },
    { t: "Send it", p: "HTTP_REQUEST", c: "blue", i: "up" },
  ];
  var CHIPS = ['{"type":"API_TRIGGER"}', '{"type":"PROCESS_EXECUTOR"}', '{"type":"AGENT_SESSION"}', '{"type":"GATE"}',
    '{"type":"ASK","risk":"HIGH"}', '{"type":"HTTP_REQUEST"}'];
  var LIST = ["Check the details", "Fix what is wrong", "Check them again", "Make sure all is valid", "Ask before acting", "Send it"];
  var TERM = ['$ agent "fix the 2 problems"', "reading input.json", "fixing records 14 and 22",
    "checking the input again", "all checks pass"];
  var ICON = {
    play: '<path d="M-6 -9 L10 0 L-6 9Z" class="ic-f"/>',
    term: '<path d="M-10 -7 L-2 0 L-10 7 M1 8 H11" class="ic"/>',
    spark: '<path d="M0 -12 Q2 -2 12 0 Q2 2 0 12 Q-2 2 -12 0 Q-2 -2 0 -12Z" class="ic-f"/>',
    check: '<path d="M-10 0 L-3 7 L11 -8" class="ic"/>',
    ask: '<path d="M-6 -5 a6 6 0 1 1 8.5 5.4 c-2 1 -2.5 2 -2.5 4" class="ic"/><circle cx="0" cy="11" r="2.4" class="ic-f"/>',
    up: '<path d="M0 10 V-10 M-8 -2 L0 -10 L8 -2" class="ic"/>',
  };

  // Token position (node index, fractional between nodes) over time, and when it is hidden or held.
  var TOK = [[35.2, 0], [35.8, 0], [36.4, 1], [37.8, 1], [38.4, 2], [46.6, 2], [47.2, 3], [50.3, 3], [50.9, 4], [51.7, 4],
    [51.72, 0], [53.8, 0], [54.3, 1], [55.0, 1], [55.5, 2], [60.6, 2], [61.1, 3], [61.4, 3], [61.9, 4], [64.2, 4], [64.8, 5],
    [66.05, 5], [66.1, 0], [67.9, 0], [68.4, 1], [68.9, 2], [69.4, 3], [69.9, 4], [70.6, 4], [71.1, 5], [80, 5]];
  var TOK_HIDE = [[0, 35.1], [51.66, 51.85], [66.05, 67.9], [73.2, 80]];
  var HOLD = [[48.5, 50.25], [58.1, 60.5]];

  // --------------------------------------------------------------------------- style

  var MONO = "'JetBrains Mono',ui-monospace,SFMono-Regular,Menlo,monospace";
  var CSS =
    ".ykf{container-type:inline-size;--bg:#fbfcfe;--panel:#fff;--ink:#11161f;--mut:#545e6f;--line:#dee1e8;--soft:#f0f4f9;--blue:#2f6bff;--blue2:#6fa2ff;--bluet:#245be0;--slate:#6a7a9b;" +
    "--orange:#e85d0c;--green:#008235;--red:#e7000b;--head:#f1cfb6;--hair:#3b2a24;--pants:#3a4356;--robot:#fff;--robotl:#c9d2e3;--robotd:#dfe5ef;--desk:#e7ebf2;--shade:rgba(17,22,31,.12);" +
    "position:relative;border-radius:16px;overflow:hidden;background:var(--bg);color:var(--ink);font-family:Inter,ui-sans-serif,system-ui,sans-serif;" +
    "box-shadow:0 2px 4px rgb(17 22 31/.04),0 12px 28px -12px rgb(17 22 31/.16),0 0 0 1px var(--line);user-select:none;-webkit-user-select:none}" +
    "html.dark .ykf{--bg:#0a0d14;--panel:#11161f;--ink:#f3f5f9;--mut:#9aa5b8;--line:#2d333d;--soft:#1c222b;--bluet:#6fa2ff;--orange:#fc8c45;--green:#4bcb71;--red:#ff6467;" +
    "--desk:#1c222b;--pants:#2a3243;--robot:#e9eef6;--robotl:#9aa5b8;--robotd:#c3ccd9;--shade:rgba(0,0,0,.45);box-shadow:0 2px 4px rgb(0 0 0/.3),0 14px 32px -12px rgb(0 0 0/.55),0 0 0 1px var(--line)}" +
    ".ykf-stage{position:relative;cursor:pointer}.ykf svg{display:block;width:100%;height:auto}" +
    ".ykf text{font-family:Inter,ui-sans-serif,system-ui,sans-serif}.ykf .mono{font-family:" + MONO + "}" +
    ".ykf .t-ink{fill:var(--ink)}.ykf .t-mut{fill:var(--mut)}.ykf .t-blue{fill:var(--bluet)}.ykf .t-or{fill:var(--orange)}.ykf .t-w{fill:#fff}" +
    ".ykf .bg{fill:var(--bg)}.ykf .dots{fill:var(--slate);opacity:.22}.ykf .trace{fill:none;stroke:var(--slate);stroke-width:2;opacity:.28;stroke-linecap:round}" +
    ".ykf .panel{fill:var(--panel);stroke:var(--robotl);stroke-width:2}.ykf .soft{fill:var(--soft)}.ykf .ln{stroke:var(--line);stroke-width:1.5;fill:none}" +
    ".ykf .f-blue{fill:var(--blue)}.ykf .f-blue2{fill:var(--blue2)}.ykf .f-slate{fill:var(--slate)}.ykf .f-orange{fill:var(--orange)}.ykf .f-green{fill:var(--green)}.ykf .f-red{fill:var(--red)}" +
    ".ykf .s-blue{fill:none;stroke:var(--blue)}.ykf .s-slate{fill:none;stroke:var(--slate)}.ykf .s-orange{fill:none;stroke:var(--orange)}.ykf .s-red{fill:none;stroke:var(--red)}.ykf .s-green{fill:none;stroke:var(--green)}" +
    ".ykf .ic{fill:none;stroke:#fff;stroke-width:3.6;stroke-linecap:round;stroke-linejoin:round}.ykf .ic-f{fill:#fff;stroke:#fff;stroke-width:2;stroke-linejoin:round}" +
    ".ykf .head{fill:var(--head)}.ykf .hair{fill:var(--hair)}.ykf .pants{fill:var(--pants)}.ykf .desk{fill:var(--desk)}.ykf .shade{fill:var(--shade)}" +
    ".ykf .shoe{fill:#232a38}.ykf .belt{fill:#000;opacity:.12}.ykf .seam{stroke:#000;stroke-width:1.6;opacity:.12}.ykf .btnc{fill:#fff;opacity:.7}.ykf .collar{fill:#fff}" +
    ".ykf .sleeve{fill:none;stroke:var(--orange);stroke-width:24;stroke-linecap:round;stroke-linejoin:round}.ykf .eye{fill:#1d2230}.ykf .brow{fill:none;stroke:var(--hair);stroke-width:3;stroke-linecap:round}" +
    ".ykf .nose{fill:none;stroke:#c4957a;stroke-width:2.2;stroke-linecap:round}.ykf .mouth{fill:none;stroke:#8f4a3c;stroke-width:2.8;stroke-linecap:round;stroke-linejoin:round}.ykf .cheek{fill:#ff9b8a;opacity:.5}" +
    ".ykf .ol{fill:none;stroke:var(--robotl);stroke-width:2}.ykf .shd{fill:#000;opacity:.1}.ykf .gloss{fill:none;stroke:#fff;stroke-width:4;stroke-linecap:round;opacity:.35}" +
    ".ykf .shine{fill:none;stroke:#fff;stroke-width:4.5;stroke-linecap:round;opacity:.28}.ykf .collarR{fill:none;stroke:#fff;stroke-width:6;stroke-linecap:round}" +
    ".ykf .robo{fill:var(--robot)}.ykf .robo-l{fill:none;stroke:var(--robotl);stroke-width:2}.ykf .robo-d{fill:var(--robotd)}.ykf .visor{fill:#121826}" +
    ".ykf .spark{fill:#8fb8ff}.ykf .spark2{fill:#fff}" +
    ".ykf .conn{stroke:var(--slate);stroke-width:2.5;fill:none;stroke-linecap:round}.ykf .flow{stroke:var(--blue2);stroke-width:3;fill:none;stroke-dasharray:8 10;stroke-linecap:round}" +
    ".ykf .ok rect{fill:color-mix(in srgb,var(--green) 13%,var(--panel));stroke:color-mix(in srgb,var(--green) 40%,transparent)}.ykf .ok text{fill:var(--green)}" +
    ".ykf .bad rect{fill:color-mix(in srgb,var(--red) 11%,var(--panel));stroke:color-mix(in srgb,var(--red) 40%,transparent)}.ykf .bad text{fill:var(--red)}" +
    ".ykf .wait rect{fill:color-mix(in srgb,var(--orange) 12%,var(--panel));stroke:color-mix(in srgb,var(--orange) 45%,transparent)}.ykf .wait text{fill:var(--orange)}" +
    ".ykf .info rect{fill:color-mix(in srgb,var(--blue) 10%,var(--panel));stroke:color-mix(in srgb,var(--blue) 40%,transparent)}.ykf .info text{fill:var(--bluet)}" +
    ".ykf .lit rect{fill:var(--blue);stroke:var(--blue)}.ykf .lit text{fill:#fff}.ykf .btn rect{fill:var(--panel);stroke:var(--line);stroke-width:1.5}.ykf .btn text{fill:var(--ink)}.ykf .btn.on rect{stroke:var(--blue);stroke-width:2.5}" +
    ".ykf .go rect{fill:var(--bluet);stroke:none}.ykf .go text{fill:#fff}" +
    ".ykf-cap{position:relative;display:flex;align-items:center;justify-content:center;min-height:96px;padding:14px 7% 16px;text-align:center;pointer-events:none;background:var(--bg);border-top:1px solid var(--line)}" +
    ".ykf-cap p{margin:0;font-family:'Inter Tight',Inter,ui-sans-serif,system-ui,sans-serif;font-size:clamp(16px,3cqw,27px);font-weight:600;line-height:1.28;letter-spacing:-.022em;color:var(--ink);text-wrap:balance}" +
    ".ykf-cap i{font-style:normal;display:inline-block;animation:ykf-w .55s cubic-bezier(.2,.8,.2,1) both}" +
    "@keyframes ykf-w{from{opacity:0;transform:translateY(10px);filter:blur(6px)}}" +
    ".ykf-cap i.e1,.ykf-cap i.e2{font-weight:750}.ykf-cap i.e1{color:var(--bluet)}.ykf-cap i.e2{color:var(--orange)}" +
    ".ykf-kick{position:absolute;left:28px;top:24px;display:flex;align-items:center;gap:12px;pointer-events:none;opacity:0;transform:translateX(-14px);transition:opacity .5s,transform .6s cubic-bezier(.2,.8,.2,1)}" +
    ".ykf-kick.on{opacity:1;transform:none}.ykf-kick b{display:grid;place-items:center;height:28px;min-width:28px;padding:0 8px;border-radius:9px;background:#245be0;color:#fff;font:600 12px/1 " + MONO + "}" +
    ".ykf-kick span{font-family:'Inter Tight',Inter,sans-serif;font-size:clamp(14px,2.2cqw,21px);font-weight:650;letter-spacing:-.02em;color:var(--ink)}" +
    ".ykf-kick small{display:block;font:500 10.5px/1 " + MONO + ";letter-spacing:.12em;text-transform:uppercase;color:var(--mut);margin-bottom:5px}" +
    ".ykf-bar .ykf-snd svg{width:17px;height:17px}.ykf-snd span{display:none}.ykf-snd[aria-pressed=true]{color:var(--bluet)}" +
    ".ykf .glow-b{opacity:.16}.ykf .glow-o{opacity:.1}html.dark .ykf .glow-b{opacity:.26}html.dark .ykf .glow-o{opacity:.14}" +
    ".ykf-big{position:absolute;inset:0;margin:auto;width:max-content;height:max-content;display:flex;align-items:center;gap:7px;padding:10px 15px 10px 12px;border:0;border-radius:999px;" +
    "background:#245be0;color:#fff;font:600 13px/1 Inter,system-ui,sans-serif;cursor:pointer;box-shadow:0 10px 22px -10px rgba(36,91,224,.6);transition:opacity .25s,transform .2s}" +
    ".ykf-big:hover{transform:scale(1.03)}.ykf-big svg{width:16px;height:16px}.ykf-big[hidden]{display:none}" +
    // Controls over the bottom of the picture, on a fade so they read on any scene.
    ".ykf-ctl{position:absolute;left:0;right:0;bottom:0;z-index:4;padding-top:28px;cursor:default;transition:opacity .25s,transform .25s;" +
    "background:linear-gradient(to top,color-mix(in srgb,var(--panel) 94%,transparent) 0,color-mix(in srgb,var(--panel) 80%,transparent) 55%,transparent)}" +
    ".ykf-ctl .ykf-bar,.ykf-ctl .ykf-chap{background:transparent;border:0}" +
    ".ykf.idle .ykf-ctl{opacity:0;transform:translateY(6px);pointer-events:none}.ykf.idle .ykf-stage{cursor:none}" +
    ".ykf.idle:focus-within .ykf-ctl{opacity:1;transform:none;pointer-events:auto}" +
    ".ykf-thin{position:absolute;left:0;right:0;bottom:0;z-index:3;height:3px;background:color-mix(in srgb,var(--line) 70%,transparent);opacity:0;transition:opacity .25s;pointer-events:none}" +
    ".ykf-thin i{display:block;width:0;height:100%;background:var(--blue)}.ykf.idle .ykf-thin{opacity:1}" +
    "@media (prefers-reduced-motion:reduce){.ykf-ctl,.ykf-thin{transition:none}}" +
    ".ykf-bar{display:flex;align-items:center;gap:12px;padding:10px 14px;border-top:1px solid var(--line);background:var(--panel)}" +
    ".ykf-bar button{flex:none;display:grid;place-items:center;width:34px;height:34px;border:0;border-radius:10px;background:var(--soft);color:var(--ink);cursor:pointer;box-shadow:inset 0 0 0 1px var(--line)}" +
    ".ykf-bar button:focus-visible,.ykf-big:focus-visible,.ykf-range:focus-visible{outline:2px solid var(--blue);outline-offset:2px}" +
    ".ykf-time{flex:none;font:500 11.5px/1 " + MONO + ";color:var(--mut);min-width:86px}" +
    ".ykf-track{position:relative;flex:1;height:34px}.ykf-range{position:absolute;inset:0;width:100%;margin:0;opacity:0;cursor:pointer}" +
    ".ykf-rail{position:absolute;left:0;right:0;top:15px;height:4px;border-radius:4px;background:var(--soft);box-shadow:inset 0 0 0 1px var(--line);pointer-events:none}" +
    ".ykf-fill{position:absolute;left:0;top:15px;height:4px;border-radius:4px;background:var(--blue);pointer-events:none}" +
    ".ykf-knob{position:absolute;top:10px;width:14px;height:14px;margin-left:-7px;border-radius:50%;background:var(--panel);box-shadow:0 0 0 3px var(--blue);pointer-events:none}" +
    ".ykf-tick{position:absolute;top:13px;width:2px;height:8px;margin-left:-1px;border-radius:1px;background:var(--slate);opacity:.5;pointer-events:none}" +
    ".ykf-chap{display:flex;flex-wrap:wrap;gap:4px;padding:0 14px 12px;background:var(--panel)}" +
    ".ykf-chap button{border:0;background:transparent;padding:5px 8px;border-radius:8px;font:500 11.5px/1 " + MONO + ";color:var(--mut);cursor:pointer}" +
    ".ykf-chap button.on{color:var(--bluet);background:var(--soft);box-shadow:inset 0 0 0 1px var(--line)}" +
    "@media (max-width:640px){.ykf-cap{min-height:92px;padding:12px 14px;justify-content:flex-start;text-align:left}.ykf-cap p{font-size:17px}" +
    ".ykf-kick{left:12px;top:10px}.ykf-kick b{height:22px;min-width:22px;font-size:10.5px}.ykf-kick small{display:none}" +
    ".ykf-time{min-width:0}.ykf-big{padding:9px 13px 9px 11px;font-size:12px}}" +
    // A narrow film (beside the hero text on a laptop): a shorter caption, chapters on one scrolling row.
    "@container (max-width:780px){.ykf-cap{min-height:70px;padding:10px 5% 12px}.ykf-bar{padding:8px 12px}" +
    ".ykf-chap{flex-wrap:nowrap;overflow-x:auto;scrollbar-width:none;padding-bottom:8px}.ykf-chap button{flex:none;padding:5px 6px}}";

  // --------------------------------------------------------------------------- drawing

  function esc(s) { return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/"/g, "&quot;"); }
  function chipW(s) { return Math.round(s.length * 8.6 + 26); }
  function badge(k) {
    return '<g data-k="' + k + '"><rect x="-60" y="-14" width="120" height="28" rx="14" stroke-width="1.5"/>' +
      '<text y="5" text-anchor="middle" class="mono" font-size="13" font-weight="600"></text></g>';
  }
  function bubble(k, lines, w, tail) {
    var h = 22 + lines.length * 24;
    return '<g data-k="' + k + '"><rect x="0" y="0" width="' + w + '" height="' + h + '" rx="16" class="panel" filter="url(#ykf-sh)"/>' +
      '<path d="' + tail + '" class="panel" stroke="none"/>' +
      lines.map(function (l, i) { return '<text x="18" y="' + (34 + i * 24) + '" font-size="18" class="t-ink" font-weight="500">' + esc(l) + "</text>"; }).join("") + "</g>";
  }
  // You: a flat illustrated person, with a face, hair, a collared shirt, long sleeves, trousers and shoes. Two arm
  // poses: typing at the desk, and standing.
  function person() {
    return '<g data-k="you"><ellipse cx="0" cy="2" rx="58" ry="10" class="shade"/>' +
      '<path d="M-44 0 C-46 -15 -31 -21 -16 -18 L-5 -16 L-5 0 Z M44 0 C46 -15 31 -21 16 -18 L5 -16 L5 0 Z" class="shoe"/>' +
      '<path d="M-36 -0.5 H-8 M36 -0.5 H8" stroke="#fff" stroke-width="3" opacity=".25" stroke-linecap="round"/>' +
      '<path d="M-34 -152 L-30 -16 L-8 -16 L-4 -152 Z M34 -152 L30 -16 L8 -16 L4 -152 Z" class="pants"/>' +
      '<path d="M-52 -238 C-52 -255 -38 -263 -22 -265 L22 -265 C38 -263 52 -255 52 -238 L48 -150 C26 -141 -26 -141 -48 -150 Z" class="f-orange"/>' +
      '<path d="M30 -263 C44 -259 52 -251 52 -238 L48 -150 C42 -147 36 -146 30 -145 Z" class="shd"/>' +
      '<path d="M-52 -238 C-52 -255 -38 -263 -22 -265 L22 -265 C38 -263 52 -255 52 -238 L48 -150 C26 -141 -26 -141 -48 -150 Z" class="ol"/>' +
      '<path d="M-40 -250 Q-30 -258 -16 -259" class="gloss"/>' +
      '<rect x="-10" y="-282" width="20" height="22" rx="8" class="head"/>' +
      '<path d="M-20 -265 Q0 -247 20 -265" class="collarR"/><circle cx="-24" cy="-224" r="6" class="f-blue"/><circle cx="-26" cy="-226" r="1.8" fill="#fff" opacity=".7"/>' +
      '<g data-k="armsSide"><g data-k="armSL"><path d="M-45 -250 Q-62 -214 -61 -178" class="sleeve"/><circle cx="-60" cy="-163" r="11" class="head"/><circle cx="-60" cy="-163" r="11" class="ol"/></g>' +
      '<g data-k="armSR"><path d="M45 -250 Q62 -214 61 -178" class="sleeve"/><circle cx="60" cy="-163" r="11" class="head"/><circle cx="60" cy="-163" r="11" class="ol"/></g></g>' +
      '<g data-k="armsDesk"><path data-k="armDL" d="M-45 -250 Q-70 -206 -56 -184 L-30 -176" class="sleeve"/><path data-k="armDR" d="M45 -250 Q70 -206 56 -184 L30 -176" class="sleeve"/></g>' +
      '<g data-k="headG"><ellipse cx="-43" cy="-320" rx="7.5" ry="10.5" class="head"/><ellipse cx="43" cy="-320" rx="7.5" ry="10.5" class="head"/>' +
      '<path d="M-47 -330 C-56 -300 -50 -282 -40 -276 L-36 -320 Z M47 -330 C56 -300 50 -282 40 -276 L36 -320 Z" class="hair"/>' +
      '<ellipse cx="0" cy="-324" rx="44" ry="45" class="head"/><ellipse cx="0" cy="-324" rx="44" ry="45" class="ol"/>' +
      '<path d="M-47 -314 C-55 -372 -8 -394 22 -381 C46 -371 55 -344 47 -312 C42 -333 32 -343 16 -347 C23 -336 19 -328 10 -324 C2 -341 -14 -349 -31 -344 C-39 -338 -44 -328 -47 -314 Z" class="hair"/>' +
      '<path d="M4 -385 C-1 -401 13 -409 22 -400 C15 -400 9 -395 4 -385 Z" class="hair"/>' +
      '<path d="M-28 -365 Q-10 -378 10 -373" class="shine"/>' +
      '<g data-k="eyes"><ellipse cx="-16" cy="-316" rx="6" ry="7.6" class="eye"/><ellipse cx="16" cy="-316" rx="6" ry="7.6" class="eye"/>' +
      '<circle cx="-14" cy="-319" r="2.3" fill="#fff"/><circle cx="18" cy="-319" r="2.3" fill="#fff"/><circle cx="-17.5" cy="-312.5" r="1" fill="#fff" opacity=".8"/><circle cx="14.5" cy="-312.5" r="1" fill="#fff" opacity=".8"/></g>' +
      '<path d="M-23 -330 q6 -4 12 -1 M11 -331 q6 -3 12 1" class="brow"/>' +
      '<ellipse cx="-27" cy="-301" rx="8" ry="5" class="cheek"/><ellipse cx="27" cy="-301" rx="8" ry="5" class="cheek"/>' +
      '<path d="M-1.5 -307 q1.5 2.2 3 0" class="nose"/>' +
      '<path data-k="mouth" d="M-7 -298 q7 6 14 0" class="mouth"/></g></g>';
  }
  // Your AI: an assistant robot. Its visor shows the sparkle people know as "AI", the antenna carries the
  // orange dot of the Yukta logo, and it hovers on a thruster glow.
  function ai(k) {
    return '<g data-k="' + k + '"><ellipse data-k="' + k + 'Sh" cx="0" cy="122" rx="44" ry="8" class="shade"/>' +
      '<g data-k="' + k + 'B"><ellipse data-k="' + k + 'Fl" cx="0" cy="78" rx="15" ry="20" class="f-blue2" opacity=".5"/>' +
      '<g data-k="' + k + 'AL"><rect x="-63" y="4" width="18" height="50" rx="9" class="f-blue"/></g>' +
      '<g data-k="' + k + 'AR"><rect x="45" y="4" width="18" height="50" rx="9" class="f-blue"/></g>' +
      '<rect x="-38" y="-6" width="76" height="76" rx="32" class="robo"/><rect x="-38" y="-6" width="76" height="76" rx="32" class="robo-l"/>' +
      '<circle cx="0" cy="30" r="13" class="s-blue" stroke-width="3.5"/><circle data-k="' + k + 'Ch" cx="0" cy="30" r="6" class="f-blue2"/>' +
      '<rect x="-15" y="-20" width="30" height="16" rx="6" class="robo-d"/>' +
      '<rect x="-69" y="-76" width="15" height="32" rx="7.5" class="f-blue"/><rect x="54" y="-76" width="15" height="32" rx="7.5" class="f-blue"/>' +
      '<rect x="-58" y="-106" width="116" height="90" rx="36" class="robo"/><rect x="-58" y="-106" width="116" height="90" rx="36" class="robo-l"/>' +
      '<rect x="-45" y="-94" width="90" height="64" rx="26" class="visor"/><path d="M-34 -84 q10 -5 22 -4" stroke="#fff" stroke-width="3" stroke-linecap="round" fill="none" opacity=".25"/>' +
      '<g data-k="' + k + 'Sp" transform="translate(0 -62)"><path d="M0 -21 Q3.5 -3.5 21 0 Q3.5 3.5 0 21 Q-3.5 3.5 -21 0 Q-3.5 -3.5 0 -21Z" class="spark"/>' +
      '<path transform="translate(20 -13) scale(.42)" d="M0 -21 Q3.5 -3.5 21 0 Q3.5 3.5 0 21 Q-3.5 3.5 -21 0 Q-3.5 -3.5 0 -21Z" class="spark2"/></g>' +
      '<path d="M0 -106 V-124" class="s-slate" stroke-width="4" stroke-linecap="round"/><circle data-k="' + k + 'An" cy="-130" r="7.5" class="f-orange"/></g></g>';
  }
  function robo(Q, k, t, bob, point, off) {
    Q[k + "B"].setAttribute("transform", "translate(0 " + bob.toFixed(2) + ")");
    Q[k + "Sh"].setAttribute("rx", (44 - bob * 0.8).toFixed(1));
    Q[k + "Fl"].setAttribute("ry", (off ? 6 : 18 + 4 * Math.sin(t * 14)).toFixed(1));
    Q[k + "Sp"].setAttribute("transform", "translate(0 -62) rotate(" + (Math.sin(t * 1.3) * 10).toFixed(1) + ") scale(" + (1 + 0.08 * Math.sin(t * 3.2)).toFixed(3) + ")");
    Q[k + "Sp"].style.opacity = off ? 0.2 : 1;
    Q[k + "An"].style.opacity = off ? 0.25 : 0.55 + 0.45 * (0.5 + 0.5 * Math.sin(t * 4));
    Q[k + "Ch"].style.opacity = off ? 0.2 : 1;
    Q[k + "AL"].setAttribute("transform", "rotate(" + (8 + Math.sin(t * 1.6) * 5).toFixed(1) + " -54 8)");
    Q[k + "AR"].setAttribute("transform", "rotate(" + (-8 - Math.sin(t * 1.6) * 5 - 120 * point).toFixed(1) + " 54 8)");
  }
  function markInner() {
    return '<rect x="18" y="19" width="212" height="212" rx="40" class="panel"/>' +
      '<g class="s-slate" stroke-width="7" stroke-linecap="round" stroke-linejoin="round"><path d="M67 62 82 77H138M82 77V89M61 184 82 163H131M82 163V151M35 120H51M165 104V129M130 128 141 139M184 58 194 48M189 187 195 193"/></g>' +
      '<circle cx="54" cy="49" r="10" class="s-orange" stroke-width="6"/><circle cx="82" cy="120" r="25" class="f-blue"/>' +
      '<circle cx="165" cy="77" r="15" class="s-blue" stroke-width="12"/><circle cx="165" cy="163" r="20" class="s-blue" stroke-width="16"/>' +
      '<circle cx="206" cy="204" r="10" class="f-orange"/>';
  }
  function mark(x, y, size) {
    var k = size / 212;
    return '<g transform="translate(' + x + ' ' + y + ') scale(' + k.toFixed(4) + ') translate(-18 -19)">' + markInner() + '</g>';
  }

  function svgMarkup() {
    var s = [];
    s.push('<svg viewBox="0 0 1280 720" role="img" aria-label="Animated explainer: what Yukta is" xmlns="http://www.w3.org/2000/svg">');
    s.push('<defs><pattern id="ykf-dots" width="32" height="32" patternUnits="userSpaceOnUse"><circle cx="2" cy="2" r="1.6" class="dots"/></pattern>' +
      '<filter id="ykf-sh" x="-20%" y="-20%" width="140%" height="160%"><feDropShadow dx="0" dy="8" stdDeviation="12" flood-color="#11161f" flood-opacity=".12"/></filter>' +
      '<marker id="ykf-ar" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0 L10 5 L0 10 Z" class="f-slate"/></marker>' +
      '<marker id="ykf-arr" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0 0 L10 5 L0 10 Z" class="f-red"/></marker>' +
      '<radialGradient id="ykf-gb"><stop offset="0" style="stop-color:var(--blue)"/><stop offset="1" style="stop-color:var(--blue)" stop-opacity="0"/></radialGradient>' +
      '<radialGradient id="ykf-go"><stop offset="0" style="stop-color:var(--orange)"/><stop offset="1" style="stop-color:var(--orange)" stop-opacity="0"/></radialGradient>' +
      '<radialGradient id="ykf-vg" cx=".5" cy=".45" r=".75"><stop offset=".6" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity=".07"/></radialGradient>' +
      "</defs>");
    s.push('<rect width="1280" height="720" class="bg"/>');
    s.push('<g data-k="cam"><g data-k="bgL"><circle data-k="glowB" r="520" fill="url(#ykf-gb)" class="glow-b"/><circle data-k="glowO" r="420" fill="url(#ykf-go)" class="glow-o"/>' +
      '<rect x="-40" y="-40" width="1360" height="800" fill="url(#ykf-dots)"/>');
    // Circuit traces in the corners, with light running along them.
    var TR = ["M-10 96 H120 L164 52 H300", "M1290 610 H1150 L1106 654 H980", "M-10 640 H70 L114 596 V520", "M1290 70 H1200 L1160 110 V200",
      "M380 -10 V40 L420 80 H520", "M860 730 V690 L900 650 H1000"];
    TR.forEach(function (d, i) { s.push('<path d="' + d + '" class="trace" data-k="tr' + i + '"/><circle r="4" class="' + (i % 2 ? "f-orange" : "f-blue2") + '" data-k="tp' + i + '"/>'); });
    s.push("</g>");

    // ---- scene A/B: the desk, the checklist, the clock.
    s.push('<g data-k="sceneA">');
    s.push('<g data-k="ghosts">' + [["Ship a release", "f-blue"], ["Pay an invoice", "f-orange"], ["Onboard a customer", "f-slate"]].map(function (c, i) { return [c, i]; }).reverse().map(function (ci) {
      var c = ci[0], i = ci[1];
      return '<g data-k="g' + i + '"><rect x="0" y="0" width="340" height="392" rx="22" class="panel" filter="url(#ykf-sh)"/>' +
        '<circle cx="30" cy="25" r="6" class="' + c[1] + '"/><text x="46" y="31" font-size="16" font-weight="650" class="t-ink">' + c[0] + "</text></g>";
    }).join("") + "</g>");
    s.push('<g data-k="list"><rect width="340" height="392" rx="22" class="panel" filter="url(#ykf-sh)"/>' +
      '<path d="M22 14 Q60 6 110 8" class="gloss" opacity=".9" stroke="#e9eef6"/><text x="26" y="44" font-size="21" font-weight="650" class="t-ink">Your process</text>' +
      '<text x="26" y="68" font-size="13" class="mono t-mut">by hand · every time</text>' +
      LIST.map(function (t, i) {
        var y = 102 + i * 48;
        return '<g data-k="li' + i + '"><rect x="26" y="' + (y - 4) + '" width="24" height="24" rx="7" class="ln" stroke-width="2"/>' +
          '<text x="64" y="' + (y + 14) + '" font-size="17" class="t-ink">' + esc(t) + "</text>" +
          '<path data-k="tk' + i + '" d="M31 ' + (y + 8) + ' l6 6 l10 -12" class="s-blue" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round"/></g>';
      }).join("") +
      '<line data-k="strike" x1="60" y1="110" x2="210" y2="110" class="s-red" stroke-width="3" stroke-linecap="round"/>' +
      '<g data-k="skipTag" transform="translate(268 108)"><rect x="-46" y="-14" width="92" height="28" rx="14" class="f-red"/><text y="5" text-anchor="middle" font-size="13" font-weight="700" class="mono t-w">skipped</text></g>' +
      "</g>");
    s.push('<g data-k="clock" transform="translate(1196 588)"><circle r="40" class="panel" filter="url(#ykf-sh)"/><path d="M-28 -16 A32 32 0 0 1 -12 -30" class="gloss" stroke="#c9d2e3"/><circle r="3" class="f-slate"/>' +
      '<line data-k="hand1" x1="0" y1="0" x2="0" y2="-26" class="s-slate" stroke-width="4" stroke-linecap="round"/>' +
      '<line data-k="hand2" x1="0" y1="0" x2="0" y2="-18" class="s-orange" stroke-width="4" stroke-linecap="round"/></g>');
    s.push("</g>");
    s.push(person());
    s.push('<g data-k="sceneA2">');
    s.push('<g data-k="desk"><rect x="250" y="520" width="360" height="18" rx="7" class="desk"/><rect x="270" y="538" width="320" height="150" rx="6" class="desk"/>' +
      '<rect x="345" y="420" width="150" height="100" rx="14" class="f-slate"/><path d="M358 432 Q380 426 410 427" class="gloss"/>' + mark(398, 446, 44) + '' +
      '<rect x="326" y="514" width="188" height="10" rx="4" class="f-slate" opacity=".8"/></g>');
    s.push('<g data-k="cost" transform="translate(560 586)"><rect width="250" height="76" rx="16" class="panel" filter="url(#ykf-sh)"/>' +
      '<text x="18" y="28" font-size="12" class="mono t-mut" font-weight="600">TOKENS SPENT</text><rect x="18" y="44" width="214" height="12" rx="6" class="soft"/>' +
      '<rect data-k="costBar" x="18" y="44" width="10" height="12" rx="6" class="f-red"/><text data-k="costTxt" x="232" y="28" text-anchor="end" font-size="13" class="mono t-or" font-weight="700"></text></g>');
    s.push('<g data-k="alert"><rect x="-70" y="-17" width="140" height="34" rx="17" class="f-red"/><text y="6" text-anchor="middle" font-size="14" font-weight="700" class="t-w">Check skipped</text></g>');
    s.push('<text data-k="huh" x="0" y="0" font-size="44" font-weight="800" class="t-or" text-anchor="middle">?!</text>');
    s.push(bubble("bubA", ["Can you take care of this?"], 262, "M196 58 L214 82 L222 58 Z"));
    s.push(bubble("bubDone", ["Done ✓"], 104, "M70 58 L84 78 L90 58 Z"));
    s.push("</g>");

    // ---- scene C: the logo builds.
    s.push('<g data-k="logo"><g transform="translate(640 292) scale(1.12) translate(-124 -125)">' +
      '<rect data-k="lgTile" x="18" y="19" width="212" height="212" rx="40" class="panel" filter="url(#ykf-sh)"/><path d="M44 36 Q90 28 140 30" class="gloss" stroke="#c9d2e3"/>' +
      '<g class="s-slate" stroke-width="6" stroke-linecap="round" stroke-linejoin="round">' +
      ["M67 62 82 77H138", "M82 77V89", "M61 184 82 163H131", "M82 163V151", "M35 120H51", "M165 104V129", "M130 128 141 139", "M184 58 194 48", "M189 187 195 193"]
        .map(function (d, i) { return '<path data-k="lp' + i + '" d="' + d + '"/>'; }).join("") + "</g>" +
      '<g data-k="lc0"><circle cx="54" cy="49" r="10" class="s-orange" stroke-width="5"/></g><g data-k="lc1"><circle cx="82" cy="120" r="25" class="f-blue"/></g>' +
      '<g data-k="lc2"><circle cx="165" cy="77" r="15" class="s-blue" stroke-width="12"/></g><g data-k="lc3"><circle cx="165" cy="163" r="20" class="s-blue" stroke-width="16"/></g>' +
      '<g data-k="lc4"><circle cx="206" cy="204" r="10" class="f-orange"/></g></g>' +
      '<text data-k="lgT" x="640" y="520" text-anchor="middle" font-size="66" font-weight="700" letter-spacing="-2" class="t-ink">Yukta</text>' +
      '<text data-k="lgS" x="640" y="562" text-anchor="middle" font-size="22" class="t-mut">The workflow is in charge, not the prompt.</text></g>');

    // ---- scenes D-H: the Yukta panel and the workflow. "world" is the camera.
    s.push('<g data-k="world">');
    s.push('<g data-k="panel"><rect x="420" y="120" width="800" height="500" rx="24" class="panel" filter="url(#ykf-sh)"/>' +
      '<line x1="420" y1="172" x2="1220" y2="172" class="ln"/>' + mark(440, 132, 28) + '' +
      '<text x="478" y="152" font-size="17" font-weight="650" class="t-ink">Yukta</text><text x="536" y="152" font-size="13" class="mono t-mut">your-process</text>' +
      [["Workflows", 723, 86], ["Runs", 808, 56], ["Agents", 884, 68], ["Inbox", 962, 60]].map(function (b, i) {
        return '<g data-k="tab' + i + '" class="btn" transform="translate(' + b[1] + ' 146)"><rect x="' + (-b[2] / 2) + '" y="-14" width="' + b[2] + '" height="28" rx="9"/>' +
          '<text y="5" text-anchor="middle" font-size="13" font-weight="600">' + b[0] + "</text></g>";
      }).join("") + '' +
      '<g data-k="valid" transform="translate(1124 146)"><rect x="-78" y="-15" width="156" height="30" rx="15" stroke-width="1.5"/><text y="5" text-anchor="middle" font-size="13" font-weight="600" class="mono">valid · saved v1</text></g>' +
      "</g>");
    // Supervisor (behind the nodes).
    s.push('<g data-k="sup"><rect x="452" y="228" width="746" height="236" rx="36" class="s-blue" stroke-width="2" stroke-dasharray="6 8" opacity=".7"/>' +
      NX.map(function (x, i) { return '<line x1="825" y1="214" x2="' + x + '" y2="' + (NY - (i === 2 ? 46 : 42)) + '" class="s-blue" stroke-width="1.5" opacity=".45"/>'; }).join("") +
      '<g transform="translate(825 196)"><circle r="21" class="panel"/><circle r="17" class="s-slate" stroke-width="8"/><circle data-k="bcc" r="7" class="f-blue2"/></g>' +
      '<text x="792" y="192" text-anchor="end" font-size="16" font-weight="650" class="t-ink">Supervisor</text>' +
      '<text x="792" y="212" text-anchor="end" font-size="12" class="mono t-mut">owns this run</text>' +
      '<circle data-k="supPulse" r="7" class="f-orange"/></g>');
    // Connectors.
    for (var i = 0; i < 5; i++) {
      var a = NX[i] + (i === 2 ? 64 : 46), b = NX[i + 1] - (i + 1 === 2 ? 66 : 48);
      s.push('<line data-k="e' + i + '" x1="' + a + '" y1="' + NY + '" x2="' + b + '" y2="' + NY + '" class="conn" marker-end="url(#ykf-ar)"/>');
    }
    // Nodes.
    NODES.forEach(function (n, i) {
      var x = NX[i];
      var body = i === 2
        ? '<rect x="-60" y="-46" width="120" height="92" rx="24" class="panel" filter="url(#ykf-sh)"/><rect x="-60" y="-46" width="120" height="92" rx="24" class="s-blue" stroke-width="2.5"/>' +
          '<g data-k="agentIc"><circle r="26" class="f-blue"/>' + ICON.spark + '<path d="M-18 -16 A24 24 0 0 1 -2 -24" stroke="#fff" stroke-width="4.5" stroke-linecap="round" fill="none" opacity=".4"/></g>'
          + '<path d="M-46 -36 Q-30 -42 -10 -42" stroke="#fff" stroke-width="4" stroke-linecap="round" fill="none" opacity=".5"/>'
        : '<circle r="40" class="panel" filter="url(#ykf-sh)"/><circle r="30" class="f-' + n.c + '"/>' + ICON[n.i] + '<path d="M-18 -16 A24 24 0 0 1 -2 -24" stroke="#fff" stroke-width="4.5" stroke-linecap="round" fill="none" opacity=".4"/>';
      s.push('<g data-k="n' + i + '" transform="translate(' + x + " " + NY + ')">' + body + "</g>");
      s.push('<text data-k="nt' + i + '" x="' + x + '" y="' + (NY + 70) + '" text-anchor="middle" font-size="17" font-weight="600" class="t-ink">' + esc(n.t) + "</text>");
      s.push('<text data-k="np' + i + '" x="' + x + '" y="' + (NY + 90) + '" text-anchor="middle" font-size="11.5" class="mono t-mut">' + n.p + "</text>");
      s.push('<g transform="translate(' + x + " " + (NY - 70) + ')">' + badge("bd" + i) + "</g>");
    });
    // A ring that ripples out as each step is created, and a burst when the release goes out.
    NX.forEach(function (x, i) { s.push('<circle data-k="rp' + i + '" cx="' + x + '" cy="' + NY + '" r="40" fill="none" class="s-' + NODES[i].c + '" stroke-width="3"/>'); });
    [0, 1].forEach(function (j) {
      s.push('<g data-k="bst' + j + '" transform="translate(' + NX[5] + " " + NY + ')">' + [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11].map(function (i) {
        return '<circle data-k="bd' + j + "_" + i + '" r="' + (i % 3 ? 4 : 6) + '" class="' + ["f-blue", "f-orange", "f-blue2", "f-green"][i % 4] + '"/>';
      }).join("") + "</g>");
    });
    // Inside the agent session: a terminal and a second AI, seen when the camera zooms in.
    s.push('<g data-k="inside" transform="translate(' + NX[2] + " " + NY + ')"><rect x="-54" y="-40" width="108" height="80" rx="18" class="panel"/>' +
      '<rect x="-48" y="-34" width="80" height="54" rx="6" fill="#141922"/>' +
      TERM.map(function (l, i) { return '<text data-k="tl' + i + '" x="-44" y="' + (-24 + i * 9.5) + '" font-size="5.4" class="mono" fill="' + (i === 0 ? "#c9d3ff" : i === 4 ? "#5ee3a1" : "#9aa7d6") + '"></text>'; }).join("") +
      '<g transform="translate(44 4) scale(.16)">' + ai("ai2") + "</g>" +
      '<text x="-48" y="33" font-size="5.6" class="mono t-mut">your agent · in this step</text></g>');
    // Budget bar under the agent.
    s.push('<g data-k="budget" transform="translate(' + (NX[2] - 60) + " " + (NY + 104) + ')"><rect width="120" height="8" rx="4" class="soft"/><rect data-k="budBar" width="10" height="8" rx="4" class="f-blue"/>' +
      '<text data-k="budTxt" x="60" y="26" text-anchor="middle" font-size="11.5" class="mono t-mut"></text></g>');
    // The agent tries to jump to Deploy.
    s.push('<path data-k="jump" d="M' + NX[2] + " " + (NY - 50) + " Q " + ((NX[2] + NX[5]) / 2) + " " + (NY - 190) + " " + NX[5] + " " + (NY - 46) + '" class="s-red" stroke-width="3" stroke-linecap="round"/>');
    s.push('<g data-k="wall" transform="translate(' + ((NX[2] + NX[5]) / 2 - 20) + " " + (NY - 118) + ')"><circle r="24" class="f-red"/><path d="M-8 -8 L8 8 M8 -8 L-8 8" class="ic"/>' +
      '<rect x="32" y="-16" width="200" height="32" rx="16" class="panel"/><text x="132" y="5" text-anchor="middle" font-size="14" font-weight="650" class="t-ink">Not in the workflow</text></g>');
    // The token.
    s.push('<g data-k="tok"><circle data-k="tokHalo" r="22" class="f-blue2" opacity=".25"/><circle r="10" class="f-blue2"/><circle r="4.5" fill="#fff"/></g>');
    s.push('<g data-k="hold"><rect x="-58" y="-15" width="116" height="30" rx="15" class="f-orange"/><text data-k="holdT" y="5" text-anchor="middle" font-size="12.5" font-weight="700" class="mono t-w">paused</text></g>');
    // Control bar.
    s.push('<g data-k="ctl"><text x="456" y="551" font-size="12" class="mono t-mut" font-weight="600">CONTROL</text>' +
      ["Pause", "Step", "Resume", "Restart"].map(function (l, i) {
        var x = 560 + i * 132;
        return '<g data-k="b' + i + '" class="btn" transform="translate(' + (x + 56) + ' 546)"><g data-k="bi' + i + '"><rect x="-56" y="-22" width="112" height="44" rx="12"/>' +
          '<text y="6" text-anchor="middle" font-size="15" font-weight="600">' + l + "</text></g></g>";
      }).join("") +
      '<g data-k="who" transform="translate(1106 500)"><rect x="-70" y="-17" width="140" height="34" rx="17" stroke-width="1.5"/><text data-k="whoT" y="5" text-anchor="middle" font-size="12.5" font-weight="600" class="mono"></text></g></g>');
    // What can start a run: one row where the control bar was.
    var tx = 572;
    s.push('<g data-k="trig"><text x="456" y="551" font-size="12" class="mono t-mut" font-weight="600">START FROM</text>' + TRIG.map(function (l, i) {
      var w = chipW(l), x = tx + w / 2;
      tx += w + 12;
      return '<g data-k="tg' + i + '" class="btn" transform="translate(' + x + ' 546)"><rect x="' + (-w / 2) + '" y="-17" width="' + w + '" height="34" rx="17"/>' +
        '<text y="5" text-anchor="middle" font-size="14" font-weight="600">' + l + "</text></g>";
    }).join("") + '<circle data-k="tgDot" r="7" class="f-orange"/></g>');
    // Ask card.
    s.push('<g data-k="ask" transform="translate(' + NX[4] + ' 150)"><rect x="-136" y="0" width="272" height="124" rx="18" class="panel" filter="url(#ykf-sh)"/>' +
      '<rect x="-136" y="0" width="272" height="124" rx="18" class="s-orange" stroke-width="2"/>' +
      '<text x="-114" y="28" font-size="11.5" font-weight="700" class="mono t-or">ASK · HIGH RISK</text>' +
      '<text x="-114" y="54" font-size="19" font-weight="650" class="t-ink">Send it to the customer?</text>' +
      '<text x="-114" y="76" font-size="13" class="t-mut">Only a person can answer this.</text>' +
      '<g data-k="approve" class="go" transform="translate(-58 100)"><rect x="-56" y="-15" width="112" height="30" rx="10"/><text y="5" text-anchor="middle" font-size="14" font-weight="650">Approve</text></g>' +
      '<g class="btn" transform="translate(64 100)"><rect x="-56" y="-15" width="112" height="30" rx="10"/><text y="5" text-anchor="middle" font-size="14" font-weight="600">Refuse</text></g></g>');
    // MCP line and the chips that travel along it.
    s.push('<path data-k="mcp" d="M348 286 C 372 232 400 224 432 250" class="flow"/>');
    s.push('<g data-k="chip"><rect data-k="chipR" x="0" y="-16" width="200" height="32" rx="10" class="panel" filter="url(#ykf-sh)"/><text data-k="chipT" x="13" y="5" font-size="13.5" class="mono t-blue" font-weight="600"></text></g>');
    s.push('<g data-k="cursor"><path d="M0 0 L0 30 L8 23 L14 36 L20 33 L14 21 L25 20 Z" class="f-orange" stroke="#fff" stroke-width="2.5" stroke-linejoin="round"/>' +
      '<g transform="translate(26 40)"><rect x="0" y="-13" width="104" height="24" rx="12" class="f-orange"/><text x="52" y="4" text-anchor="middle" font-size="12" font-weight="700" class="t-w">You · web UI</text></g>' +
      '<circle data-k="click" r="10" class="s-orange" stroke-width="3"/></g>');
    s.push("</g>");

    // People and AIs travel across scenes, so they are outside the camera.
    s.push(ai("ai"));
    s.push('<g data-k="via">' + ["MCP", "CLI", "REST API"].map(function (l, i) {
      var w = chipW(l) + 4;
      return '<g data-k="via' + i + '" class="btn" transform="translate(208 ' + (164 + i * 42) + ')"><g data-k="viaS' + i + '"><rect x="' + (-w / 2) + '" y="-16" width="' + w + '" height="32" rx="16"/>' +
        '<text y="5" text-anchor="middle" font-size="14" font-weight="700" class="mono">' + l + "</text></g></g>";
    }).join("") + "</g>");
    s.push(bubble("bubD", ["Turn this into a workflow.", "Ask me before it acts."], 270, "M120 70 L132 94 L150 70 Z"));
    s.push(bubble("bubOn", ["On it."], 96, "M20 58 L12 78 L44 58 Z"));
    s.push('<g data-k="phone" transform="translate(262 560)"><rect x="-22" y="-38" width="44" height="76" rx="11" class="f-slate"/><rect x="-17" y="-30" width="34" height="58" rx="6" class="panel"/><path d="M-12 -24 Q-4 -27 4 -26" class="gloss" stroke="#c9d2e3"/>' +
      '<circle cy="-8" r="7" class="f-orange"/><rect x="-11" y="6" width="22" height="4" rx="2" class="soft"/><rect x="-11" y="14" width="16" height="4" rx="2" class="soft"/></g>');
    s.push('<path data-k="beam" d="" class="flow"/><circle data-k="beamDot" r="7" class="f-blue2"/>');


    // ---- end card.
    s.push('<g data-k="end">' + mark(584, 112, 112) + '' +
      '<text x="640" y="300" text-anchor="middle" font-size="72" font-weight="700" letter-spacing="-2.5" class="t-ink">Yukta</text>' +
      '<text x="640" y="346" text-anchor="middle" font-size="25" class="t-mut">Workflows your AI agents can’t skip.</text>' +
      [["Any process. Any agent, off the shelf or your own", 392], ["Web UI, CLI, REST API and MCP", 440], ["Supervisors and approvals", 488], ["Runs without AI", 536]].map(function (p, i) {
        var w = chipW(p[0]) + 6;
        return '<g data-k="pill' + i + '" transform="translate(640 ' + p[1] + ')"><rect x="' + (-w / 2) + '" y="-18" width="' + w + '" height="36" rx="18" class="panel"/>' +
          '<circle cx="' + (-w / 2 + 20) + '" cy="0" r="5" class="' + ["f-blue", "s-blue", "f-orange", "s-orange"][i] + '" stroke-width="2.5"/>' +
          '<text x="8" y="5" text-anchor="middle" font-size="15" font-weight="550" class="t-ink">' + esc(p[0]) + "</text></g>";
      }).join("") +
      '<text x="640" y="602" text-anchor="middle" font-size="14" class="mono t-mut">Open source · Community edition under Apache-2.0</text></g>');
    s.push('</g><rect width="1280" height="720" fill="url(#ykf-vg)" pointer-events="none"/>');
    s.push('<rect data-k="lbT" x="0" y="0" width="1280" height="0" fill="#0a0d14"/><rect data-k="lbB" x="0" y="720" width="1280" height="0" fill="#0a0d14"/>');
    s.push('<g data-k="wipe"><path d="M0 -60 L1500 -60 L1300 780 L-200 780 Z" class="f-blue"/><path d="M1500 -60 L1620 -60 L1420 780 L1300 780 Z" class="f-orange"/>' +
      '<path d="M-150 -60 L0 -60 L-200 780 L-350 780 Z" class="f-blue2"/>' + mark(560, 280, 160) + "</g>");
    s.push("</svg>");
    return s.join("");
  }

  // --------------------------------------------------------------------------- timing helpers

  function cl(x) { return x < 0 ? 0 : x > 1 ? 1 : x; }
  function eo(x) { return 1 - Math.pow(1 - x, 3); }
  function eio(x) { return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2; }
  function eb(x) { var c = 1.7; x = cl(x); return 1 + (c + 1) * Math.pow(x - 1, 3) + c * Math.pow(x - 1, 2); }
  function pr(t, a, b, e) { return (e || eo)(cl((t - a) / (b - a))); }
  function win(t, a, b, f) { f = f || 0.35; return Math.min(pr(t, a, a + f), 1 - pr(t, b - f, b)); }
  function lerp(a, b, u) { return a + (b - a) * u; }
  function kf(t, K) {
    if (t <= K[0][0]) return K[0].slice(1);
    for (var i = 1; i < K.length; i++) {
      if (t <= K[i][0]) {
        var A = K[i - 1], B = K[i], u = eio(cl((t - A[0]) / (B[0] - A[0])));
        return A.slice(1).map(function (v, j) { return lerp(v, B[j + 1], u); });
      }
    }
    return K[K.length - 1].slice(1);
  }
  function inAny(t, W) { for (var i = 0; i < W.length; i++) if (t >= W[i][0] && t < W[i][1]) return true; return false; }
  function fmt(t) { t = Math.max(0, Math.floor(t)); return Math.floor(t / 60) + ":" + ("0" + (t % 60)).slice(-2); }

  // --------------------------------------------------------------------------- sound

  // An original score and sound effects, synthesised with Web Audio, so there is no audio file and no licence.
  // The score is written in the manner of a large film score (a ticking clock, string ostinatos, low brass hits,
  // risers, war drums), not after any existing piece. D minor, 100 BPM, with bars counted from each chapter's cut
  // so the harmony turns with the picture. Its theme is a staircase: four notes climbing one step at a time
  // (D, E, F, A), because the film is about steps taken in order. It is heard first when Yukta arrives, grows
  // through the runs, and resolves to D major on the end card. Sound starts off; browsers allow it only after a click.
  //
  //   The problem   drone, clock, heartbeat; the clock doubles and a riser climbs as the agent skips steps
  //   Meet Yukta    brass hit, the tension lets go, the theme on a soft horn
  //   Build         plucked strings under the nodes
  //   Run           brass hit, driving strings, drums and bass; one brass hit where the agent hits the wall
  //   Control       clock and a low pulse, near silence while the run is paused
  //   Supervise     strings and drums rebuild, a riser into the high-risk question, a heartbeat until you approve
  //   Any trigger   a breakdown, then everything, with the theme in octaves on brass
  //   Yukta         the last hit, and the theme in D major
  var BEAT = 0.6, EIGHTH = 0.3, BAR = 2.4;
  // i, VI, iv, V: Dm, Bb, Gm, A, as [bass note, chord tones].
  var PROG = [[38, [62, 65, 69]], [34, [62, 65, 70]], [31, [62, 67, 70]], [33, [61, 64, 69]]];
  var DMAJ = [38, [62, 66, 69]];
  // The theme, as [eighth from its start, note, length in eighths]; the last statement climbs into D major.
  var THEME = [[0, 62, 1], [1, 64, 1], [2, 65, 2], [4, 69, 4], [8, 67, 1], [9, 65, 1], [10, 64, 2], [12, 62, 4]];
  var FINAL = [[0, 62, 2], [2, 64, 2], [4, 66, 2], [6, 69, 12]];
  var SFX = [[15, "alert"], [18.6, "whoosh"], [20.8, "chime"], [24.6, "whoosh"], [34.2, "ok"], [34.6, "whoosh"], [39, "swoosh"], [43.6, "swoosh"],
    [45.6, "error"], [48.4, "click"], [50.2, "click"], [51.6, "click"], [57.5, "alert2"], [60.2, "click"], [62.4, "ping"], [63.7, "click"],
    [64.9, "chime"], [65.6, "whoosh"], [67.4, "switch"], [71.2, "chime"], [72.6, "whoosh"], [73.8, "final"]];
  for (var pk = 0; pk < 6; pk++) SFX.push([29.65 + pk * 0.85, "pop" + pk]);
  // The rest of the picture's beats, so each thing that moves on screen is heard where it moves.
  // The problem: the checklist builds, the other processes stack behind it, the agent arrives and talks.
  for (var li = 0; li < 6; li++) SFX.push([1.1 + li * 0.45, "item" + li]);
  [5, 5.6, 6.2].forEach(function (x) { SFX.push([x, "thud"]); });
  SFX.push([9, "whoosh"], [9.6, "blip"], [12.2, "zip"], [14, "blip"], [25.8, "blip"], [28.4, "blip"]);
  // The agent ticks the list off far too fast, then the cost meter runs up.
  for (var sk = 1; sk < 6; sk++) SFX.push([12.5 + sk * 0.28, "rush" + sk]);
  for (var cn = 0; cn < 10; cn++) SFX.push([15.8 + cn * 0.3, "coin" + cn]);
  // Meet Yukta: the logo draws, its name and tagline land; the ways in slide up.
  SFX.push([21.9, "tap"], [22.5, "tap"], [25.6, "tap"], [25.85, "tap"], [26.1, "tap"]);
  // The agent at work in its terminal: keys, and a return at the end of each line.
  [27, 18, 24, 24, 15].forEach(function (len, l) {
    for (var c = 0; c < len; c += 3) SFX.push([40.3 + l * 0.56 + (c / len) * 0.5, "key"]);
    SFX.push([40.3 + l * 0.56 + 0.52, l === 4 ? "ok" : "enter"]);
  });
  // Node badges: problems found, then fixed.
  [37.6, 54.9, 68.6].forEach(function (x) { SFX.push([x, "bad"]); });
  [46.4, 60.4, 69.1].forEach(function (x) { SFX.push([x, "fix"]); });
  // The supervisor appears; the phone buzzes for the high-risk question; the end card's pills.
  SFX.push([52.6, "rise"], [61.4, "buzz"], [61.85, "buzz"]);
  for (var pl = 0; pl < 4; pl++) SFX.push([74.6 + pl * 0.35, "item" + (pl + 2)]);
  // The token reaching each node: a note that climbs as the run moves on.
  for (var tk = 1; tk < TOK.length; tk++) {
    var at = TOK[tk][0], to = TOK[tk][1];
    if (to !== TOK[tk - 1][1] && to % 1 === 0 && !inAny(at, TOK_HIDE)) SFX.push([at, "step" + to]);
  }
  // Each chapter cut lands on a soft low hit, with a rising whoosh leading into it.
  CHAPTERS.slice(1, -1).forEach(function (c) { SFX.push([c[0], c[0] === 19 || c[0] === 35 || c[0] === 66 ? "braam" : "hit"]); });
  // The score's own events: risers into the two crises, a dissonant cluster on each, the wall, the approval.
  SFX.push([13.4, "riser"], [15, "cluster"], [45.55, "braam"], [57.4, "riser2"], [61.4, "cluster"], [63.7, "swell"], [73, "braamBig"]);
  function mtof(m) { return 440 * Math.pow(2, (m - 69) / 12); }

  function makeAudio() {
    var ctx = null, master = null, verb = null, bus = null, send = null, noise = null, enabled = false, until = 0;
    function init() {
      if (ctx) return true;
      var AC = window.AudioContext || window.webkitAudioContext;
      if (!AC) return false;
      ctx = new AC();
      master = ctx.createGain();
      master.gain.value = 0.5;
      var comp = ctx.createDynamicsCompressor();
      comp.threshold.value = -16;
      master.connect(comp);
      comp.connect(ctx.destination);
      // A small hall: a decaying noise impulse.
      var len = ctx.sampleRate * 2.6, ir = ctx.createBuffer(2, len, ctx.sampleRate);
      for (var c = 0; c < 2; c++) {
        var d = ir.getChannelData(c);
        for (var i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / len, 3);
      }
      verb = ctx.createConvolver();
      verb.buffer = ir;
      var wet = ctx.createGain();
      wet.gain.value = 0.32;
      verb.connect(wet);
      wet.connect(master);
      noise = ctx.createBuffer(1, ctx.sampleRate, ctx.sampleRate);
      var nd = noise.getChannelData(0);
      for (var j = 0; j < nd.length; j++) nd[j] = Math.random() * 2 - 1;
      return true;
    }
    function newBus() {
      if (bus) {
        var old = bus, oldSend = send;
        old.gain.setTargetAtTime(0, ctx.currentTime, 0.04);
        setTimeout(function () { try { old.disconnect(); oldSend.disconnect(); } catch (e) {} }, 500);
      }
      bus = ctx.createGain();
      bus.connect(master);
      send = ctx.createGain();
      send.gain.value = 1;
      bus.connect(send);
      send.connect(verb);
    }
    function env(g, when, peak, a, hold, r) {
      g.gain.setValueAtTime(0.0001, when);
      g.gain.exponentialRampToValueAtTime(peak, when + a);
      g.gain.setValueAtTime(peak, when + a + hold);
      g.gain.exponentialRampToValueAtTime(0.0001, when + a + hold + r);
    }
    // Route a voice to its bus, placed in the stereo field where its event is on screen (o.pan, -1 left to
    // 1 right), and moving across it when o.panTo is set.
    function out(node, o, when, dest) {
      if ((o.pan === undefined && o.panTo === undefined) || !ctx.createStereoPanner) { node.connect(dest); return; }
      var p = ctx.createStereoPanner();
      p.pan.setValueAtTime(o.pan || 0, when);
      if (o.panTo !== undefined) p.pan.linearRampToValueAtTime(o.panTo, when + (o.glide || 0.5));
      node.connect(p);
      p.connect(dest);
    }
    // A frequency-modulated voice: metallic and glassy partials from two sine waves, the brightness falling as it
    // rings out (o.ratio sets the timbre, o.index the starting brightness).
    function fm(when, f, o) {
      var car = ctx.createOscillator(), mod = ctx.createOscillator(), mg = ctx.createGain(), g = ctx.createGain();
      var dur = (o.a || 0.003) + (o.r || 0.8);
      car.frequency.value = f;
      mod.frequency.value = f * (o.ratio || 3.5);
      mg.gain.setValueAtTime(f * (o.index || 4), when);
      mg.gain.exponentialRampToValueAtTime(f * 0.05, when + dur);
      mod.connect(mg);
      mg.connect(car.frequency);
      car.connect(g);
      out(g, o, when, bus);
      env(g, when, o.g || 0.04, o.a || 0.003, 0, o.r || 0.8);
      car.start(when);
      mod.start(when);
      car.stop(when + dur + 0.05);
      mod.stop(when + dur + 0.05);
    }
    // A crisp interface click: a resonant noise transient over a tiny pitched tick.
    function click(when, f, g, pan) {
      hiss(when, { ft: "bandpass", f: f, q: 6, g: g, r: 0.012, pan: pan });
      tone(when, f / 2, { type: "sine", g: g * 0.6, a: 0.001, r: 0.025, pan: pan });
    }
    // The screen x (0 to 1280) of a thing in the picture, as a stereo position.
    function px(x) { return Math.max(-0.8, Math.min(0.8, (x - 640) / 640)); }
    function tone(when, f, o) {
      var osc = ctx.createOscillator(), g = ctx.createGain(), fl = ctx.createBiquadFilter();
      osc.type = o.type || "sine";
      osc.frequency.setValueAtTime(f, when);
      if (o.to) osc.frequency.exponentialRampToValueAtTime(o.to, when + (o.glide || 0.1));
      if (o.detune) osc.detune.value = o.detune;
      fl.type = o.ft || "lowpass";
      fl.frequency.value = o.cut || 8000;
      osc.connect(fl);
      fl.connect(g);
      out(g, o, when, o.dry ? master : bus);
      env(g, when, o.g || 0.05, o.a || 0.005, o.h || 0, o.r || 0.3);
      osc.start(when);
      osc.stop(when + (o.a || 0.005) + (o.h || 0) + (o.r || 0.3) + 0.05);
    }
    function hiss(when, o) {
      var src = ctx.createBufferSource(), g = ctx.createGain(), fl = ctx.createBiquadFilter();
      src.buffer = noise;
      fl.type = o.ft || "highpass";
      fl.frequency.setValueAtTime(o.f || 7000, when);
      if (o.to) fl.frequency.exponentialRampToValueAtTime(o.to, when + (o.glide || 0.5));
      fl.Q.value = o.q || 0.7;
      src.connect(fl);
      fl.connect(g);
      out(g, o, when, bus);
      env(g, when, o.g || 0.03, o.a || 0.002, o.h || 0, o.r || 0.05);
      src.start(when);
      src.stop(when + (o.a || 0.002) + (o.h || 0) + (o.r || 0.05) + 0.05);
    }
    // Time since the chapter began: the beat grid and the chords restart at every cut.
    function chapIdx(t) { var c = 0; for (var i = 0; i < CHAPTERS.length; i++) if (t >= CHAPTERS[i][0] - 1e-6) c = i; return c; }
    // Instruments.
    function pad(when, ch, dur, cut, lvl) {
      ch[1].forEach(function (m) {
        [m - 12, m].forEach(function (x) {
          [-7, 7].forEach(function (dt) { tone(when, mtof(x), { type: "sawtooth", detune: dt, cut: cut, g: lvl, a: 0.6, h: Math.max(0.1, dur - 1.1), r: 0.9 }); });
        });
      });
    }
    function string(when, m, g, len) {
      [-5, 5].forEach(function (dt) { tone(when, mtof(m), { type: "sawtooth", detune: dt, cut: 2100, g: g, a: 0.004, h: len, r: 0.09 }); });
    }
    function pluck(when, m, g) { tone(when, mtof(m), { type: "triangle", cut: 1800, g: g, a: 0.003, r: 0.28 }); }
    function horn(when, m, len, g) {
      [-6, 6].forEach(function (dt) { tone(when, mtof(m), { type: "sawtooth", detune: dt, cut: 1150, g: g, a: 0.12, h: Math.max(0.05, len - 0.12), r: 0.5 }); });
      tone(when, mtof(m - 12), { type: "sine", g: g * 0.8, a: 0.12, h: Math.max(0.05, len - 0.12), r: 0.5 });
    }
    function bass(when, m, g, len) {
      tone(when, mtof(m), { type: "sine", g: g, a: 0.008, h: len, r: 0.2 });
      tone(when, mtof(m + 12), { type: "sawtooth", cut: 500, g: g * 0.18, a: 0.008, h: len, r: 0.15 });
    }
    function drum(when, g) {
      tone(when, 95, { to: 38, glide: 0.28, type: "sine", g: g, a: 0.003, r: 0.5, dry: true });
      hiss(when, { ft: "lowpass", f: 600, g: g * 0.3, r: 0.12 });
    }
    function heart(when) {
      tone(when, 62, { to: 40, glide: 0.1, type: "sine", g: 0.16, a: 0.004, r: 0.16, dry: true });
      tone(when + 0.2, 58, { to: 38, glide: 0.1, type: "sine", g: 0.11, a: 0.004, r: 0.18, dry: true });
    }
    function clock(when, g) { tone(when, 2100, { type: "square", cut: 3600, g: g, r: 0.025 }); }
    // A low brass hit: detuned saws through a filter that opens and closes, over a sub.
    function braam(when, root, big) {
      [root - 12, root, root + 7].concat(big ? [root + 12, root + 16] : []).forEach(function (m) {
        [-10, 0, 10].forEach(function (dt) {
          var osc = ctx.createOscillator(), g = ctx.createGain(), fl = ctx.createBiquadFilter();
          osc.type = "sawtooth";
          osc.frequency.value = mtof(m);
          osc.detune.value = dt;
          fl.type = "lowpass";
          fl.Q.value = 3;
          fl.frequency.setValueAtTime(110, when);
          fl.frequency.exponentialRampToValueAtTime(big ? 1700 : 1150, when + 0.35);
          fl.frequency.exponentialRampToValueAtTime(200, when + (big ? 5 : 2.2));
          osc.connect(fl);
          fl.connect(g);
          g.connect(bus);
          env(g, when, big ? 0.03 : 0.035, 0.05, big ? 3 : 0.8, big ? 3 : 1.4);
          osc.start(when);
          osc.stop(when + (big ? 6.2 : 2.4));
        });
      });
      tone(when, mtof(root - 24), { type: "sine", g: 0.22, a: 0.02, h: big ? 2.5 : 0.5, r: 1.6, dry: true });
    }
    function riser(when, dur) {
      [50, 62, 74].forEach(function (m) { tone(when, mtof(m), { to: mtof(m + 12), glide: dur, type: "sawtooth", cut: 2400, g: 0.018, a: dur * 0.85, r: dur * 0.2 }); });
      hiss(when, { ft: "bandpass", f: 300, to: 5200, glide: dur, q: 1.4, g: 0.045, a: dur * 0.9, r: 0.25 });
    }
    function theme(k, notes, when, oct, g) {
      notes.forEach(function (x) { if (x[0] === k) oct.forEach(function (o) { horn(when, x[1] + o, x[2] * EIGHTH, g); }); });
    }
    // One eighth note of the score: n counts eighths from the start of the chapter that t falls in.
    function music(n, t, when) {
      var ci = chapIdx(t), b8 = n % 8, ch = ci === 7 ? DMAJ : PROG[Math.floor(n / 8) % 4];
      var lo = ch[1].map(function (m) { return m - 12; });
      if (b8 === 0 && ci > 0 && ci < 7) pad(when, ch, BAR, [0, 1100, 1300, 1700, 800, 1600, 2000][ci], ci === 4 ? 0.008 : 0.013);
      if (ci === 0) {
        if (b8 === 0) tone(when, mtof(38), { type: "sawtooth", cut: 240 + t * 22, g: 0.08, a: 0.9, h: BAR - 1.3, r: 0.9 });
        if (t >= 4.6 && t < 13.4 && n % 2 === 0) clock(when, 0.014);
        if (t >= 13.4) clock(when, 0.02);
        if (t >= 9 && (t < 13.4 ? b8 === 0 : n % 4 === 0)) heart(when);
      } else if (ci === 1) {
        theme(n - 2, THEME, when, [0], 0.028);
        if (b8 === 0) bass(when, ch[0], 0.13, BAR - 0.3);
      } else if (ci === 2) {
        pluck(when, lo[[0, 2, 1, 2, 0, 2, 1, 2][b8]], 0.045);
        if (n % 4 === 0) bass(when, ch[0], 0.12, 0.5);
      } else if (ci === 3) {
        if (t >= 45.4) return;
        var duck = t > 39.3 && t < 43.7 ? 0.4 : 1, pat = [0, 1, 2, 1];
        string(when, lo[pat[(n * 2) % 4]], 0.022 * duck * (n % 2 === 0 ? 1.3 : 1), 0.06);
        string(when + EIGHTH / 2, lo[pat[(n * 2 + 1) % 4]], 0.018 * duck, 0.06);
        if (b8 === 0 || b8 === 3 || b8 === 6) drum(when, 0.2 * duck);
        bass(when, ch[0], 0.1 * duck, 0.18);
        theme(n, THEME, when, [12], 0.014);
      } else if (ci === 4) {
        if (n % 2 === 0) clock(when, 0.012);
        if (n % 4 === 0 && !inAny(t, HOLD)) bass(when, 38, 0.1, 0.25);
      } else if (ci === 5) {
        if (t >= 61.4 && t < 63.7) {
          if (n % 4 === 0) heart(when);
          if (n % 2 === 0) clock(when, 0.013);
          return;
        }
        string(when, lo[[0, 1, 2, 1][(n * 2) % 4]], 0.02, 0.06);
        if (t >= 55) string(when + EIGHTH / 2, lo[[0, 1, 2, 1][(n * 2 + 1) % 4]], 0.016, 0.06);
        if (t >= 55 && (b8 === 0 || b8 === 4)) drum(when, 0.18);
        if (n % 2 === 0) bass(when, ch[0], 0.1, 0.25);
      } else if (ci === 6) {
        if (t < 68) { if (n % 2 === 0) clock(when, 0.014); return; }
        string(when, lo[[0, 1, 2, 1][(n * 2) % 4]], 0.032, 0.06);
        string(when + EIGHTH / 2, lo[[0, 1, 2, 1][(n * 2 + 1) % 4]], 0.027, 0.06);
        string(when, ch[1][[0, 1, 2, 1][(n * 2) % 4]], 0.012, 0.06);
        if (b8 === 0 || b8 === 2 || b8 === 3 || b8 === 6) drum(when, 0.27);
        bass(when, ch[0], 0.14, 0.18);
        theme(n - 8, THEME, when, [0, 12], 0.03);
      } else if (n === 0) {
        pad(when, DMAJ, 6.5, 2200, 0.014);
      } else if (ci === 7) {
        theme(n - 4, FINAL, when, [0, 12], 0.022);
      }
    }
    // The effects, each placed where its event is on screen: the checklist on the right, the agent left of centre,
    // the run's nodes from left to right (NX), the person on the left.
    function sfx(k, when) {
      var i = +k.replace(/\D/g, "");
      // Air moving: a swept band of noise with an airy top, crossing the stereo field.
      if (k === "whoosh") {
        hiss(when, { ft: "bandpass", f: 250, to: 3200, glide: 0.6, q: 1.1, g: 0.06, a: 0.3, r: 0.35, pan: -0.6, panTo: 0.6 });
        hiss(when + 0.05, { ft: "highpass", f: 5000, g: 0.02, a: 0.35, r: 0.25, pan: -0.4, panTo: 0.5, glide: 0.6 });
      } else if (k === "swoosh") {
        hiss(when, { ft: "bandpass", f: 3000, to: 350, glide: 0.5, q: 1.3, g: 0.05, a: 0.12, r: 0.4, pan: 0.5, panTo: -0.5 });
      } else if (k === "rise") {
        hiss(when, { ft: "bandpass", f: 200, to: 2600, glide: 1, q: 1, g: 0.04, a: 0.7, r: 0.4, pan: 0.3 });
        tone(when, 220, { to: 440, glide: 1, type: "sine", g: 0.015, a: 0.8, r: 0.3, pan: 0.3 });
      } else if (k === "zip") {
        hiss(when, { ft: "bandpass", f: 900, to: 7000, glide: 0.3, q: 3, g: 0.05, a: 0.04, r: 0.18, pan: -0.1, panTo: 0.6 });
      }
      // Bells, glass and metal (FM).
      else if (k === "chime") [76, 79, 83, 88].forEach(function (m, j) { fm(when + j * 0.085, mtof(m), { ratio: 3.5, index: 3, g: 0.03, r: 1.6, pan: -0.45 + j * 0.3 }); });
      else if (k === "ok") { fm(when, mtof(79), { ratio: 3.5, index: 2.5, g: 0.035, r: 1, pan: 0.1 }); fm(when + 0.1, mtof(86), { ratio: 3.5, index: 2.5, g: 0.035, r: 1.3, pan: 0.2 }); }
      else if (k === "fix") { fm(when, mtof(78), { ratio: 2, index: 2, g: 0.03, r: 0.7, pan: px(NX[2]) }); fm(when + 0.09, mtof(85), { ratio: 2, index: 2, g: 0.03, r: 1, pan: px(NX[2]) }); }
      else if (k === "ping") { fm(when, mtof(88), { ratio: 1.5, index: 1.5, g: 0.04, r: 0.5, pan: -0.5 }); fm(when + 0.15, mtof(93), { ratio: 1.5, index: 1.5, g: 0.04, r: 0.8, pan: -0.5 }); }
      else if (k.indexOf("coin") === 0) { fm(when, mtof(83 + i), { ratio: 1.41, index: 5, g: 0.02, r: 0.22, pan: 0.55 }); click(when, 6000, 0.012, 0.55); }
      // Each node the run reaches: a soft mallet note, climbing, from where the node is.
      else if (k.indexOf("step") === 0) {
        var sm = [69, 72, 76, 79, 81, 84][i];
        fm(when, mtof(sm), { ratio: 4, index: 1.2, g: 0.028, r: 0.9, pan: px(NX[i]) });
        tone(when, mtof(sm - 12), { type: "sine", g: 0.02, a: 0.004, r: 0.3, pan: px(NX[i]) });
      }
      // The node graph being built: bubbles that pop into place, left to right.
      else if (k.indexOf("pop") === 0) {
        var pm = [72, 74, 76, 79, 81, 84][i];
        tone(when, mtof(pm - 5), { to: mtof(pm + 7), glide: 0.04, type: "sine", g: 0.06, a: 0.002, r: 0.14, pan: px(NX[i]) });
        click(when, 3500, 0.02, px(NX[i]));
      }
      // The checklist, and the end card's pills: a soft marimba from the right.
      else if (k.indexOf("item") === 0) {
        var im = [69, 72, 74, 76, 79, 81][i];
        tone(when, mtof(im), { type: "sine", g: 0.04, a: 0.002, r: 0.25, pan: 0.5 });
        tone(when, mtof(im) * 4, { type: "sine", g: 0.008, a: 0.001, r: 0.05, pan: 0.5 });
      }
      // The agent racing down the list: quick ticks, falling.
      else if (k.indexOf("rush") === 0) click(when, 4200 - i * 300, 0.03, 0.55);
      // Interface: taps, clicks on Pause, Step and Restart, a switch, keys and return.
      else if (k === "tap") click(when, 2600, 0.03, 0);
      else if (k === "click") { click(when, 3200, 0.05, -0.2); tone(when, 180, { type: "sine", g: 0.04, a: 0.001, r: 0.04, pan: -0.2 }); }
      else if (k === "switch") { click(when, 2800, 0.05, -0.3); tone(when + 0.02, 700, { to: 220, glide: 0.25, type: "triangle", g: 0.04, r: 0.3, pan: -0.3 }); }
      else if (k === "key") {
        var kp = -0.1 + Math.random() * 0.2;
        click(when, 2800 + Math.random() * 1800, 0.028, kp);
        tone(when, 160 + Math.random() * 60, { type: "sine", g: 0.018, a: 0.001, r: 0.03, pan: kp });
      } else if (k === "enter") { click(when, 1800, 0.045, 0.1); tone(when, 120, { type: "sine", g: 0.04, a: 0.001, r: 0.06, pan: 0.1 }); }
      // Speech bubbles from the agent: a quick two-note chirp.
      else if (k === "blip") { tone(when, 990, { to: 1320, glide: 0.04, type: "sine", g: 0.03, r: 0.06, pan: -0.15 }); tone(when + 0.07, 1480, { type: "sine", g: 0.022, r: 0.08, pan: -0.15 }); }
      // Things landing: the stacked process cards.
      else if (k === "thud") { tone(when, 140, { to: 60, glide: 0.1, type: "sine", g: 0.09, a: 0.002, r: 0.16, pan: 0.45 }); hiss(when, { ft: "bandpass", f: 1200, q: 0.8, g: 0.025, r: 0.08, pan: 0.45 }); }
      // Trouble: problems found, the agent's skip refused, alerts, the phone.
      else if (k === "bad") {
        [[0, 52], [0.11, 49]].forEach(function (x) {
          fm(when + x[0], mtof(x[1] + 12), { ratio: 1, index: 1.5, g: 0.035, r: 0.25, pan: px(NX[1]) });
          tone(when + x[0], mtof(x[1]), { type: "triangle", g: 0.03, r: 0.2, pan: px(NX[1]) });
        });
      } else if (k === "error") {
        tone(when, 90, { to: 45, glide: 0.2, type: "sine", g: 0.14, a: 0.002, r: 0.3, dry: true });
        hiss(when, { ft: "lowpass", f: 700, g: 0.05, r: 0.15, pan: px(NX[3]) });
        [[0.02, 196], [0.18, 165]].forEach(function (x) { tone(when + x[0], x[1], { type: "square", cut: 900, g: 0.035, h: 0.1, r: 0.12, pan: px(NX[3]) }); });
      } else if (k === "alert") {
        [[0, 659], [0.15, 494]].forEach(function (x) { tone(when + x[0], x[1], { type: "triangle", g: 0.05, h: 0.06, r: 0.18, pan: 0.5 }); fm(when + x[0], x[1] * 2, { ratio: 2, index: 1, g: 0.015, r: 0.2, pan: 0.5 }); });
      } else if (k === "alert2") {
        [0, 0.13].forEach(function (d) { tone(when + d, 880, { type: "triangle", g: 0.045, h: 0.05, r: 0.12, pan: 0.35 }); click(when + d, 5000, 0.015, 0.35); });
      } else if (k === "buzz") {
        // A phone vibrating on a desk: a motor hum in pulses, with a rattle.
        [0, 0.11, 0.22].forEach(function (d) {
          tone(when + d, 158, { type: "sawtooth", cut: 520, g: 0.05, a: 0.005, h: 0.06, r: 0.02, pan: -0.55 });
          hiss(when + d, { ft: "bandpass", f: 2400, q: 4, g: 0.012, a: 0.005, h: 0.06, r: 0.02, pan: -0.55 });
        });
      }
      // The cuts: an impact with a sub drop, a body and a burst of air.
      else if (k === "hit") {
        tone(when, 75, { to: 38, glide: 0.45, type: "sine", g: 0.16, a: 0.004, r: 0.7, dry: true });
        tone(when, 150, { to: 70, glide: 0.2, type: "triangle", g: 0.04, a: 0.002, r: 0.25 });
        hiss(when, { ft: "lowpass", f: 2500, to: 300, glide: 0.3, g: 0.04, a: 0.002, r: 0.35 });
      }
      // The score's own events.
      else if (k === "braam") braam(when, 38, false);
      else if (k === "braamBig") braam(when, 38, true);
      else if (k === "riser") riser(when, 5.6);
      else if (k === "riser2") riser(when, 4);
      else if (k === "cluster") [50, 51, 57].forEach(function (m) { tone(when, mtof(m), { type: "sawtooth", cut: 900, g: 0.022, a: 0.5, h: 1.8, r: 1.2 }); });
      else if (k === "swell") { pad(when, DMAJ, 2.3, 2400, 0.016); fm(when, mtof(81), { ratio: 3.5, index: 2.5, g: 0.035, r: 1.6 }); }
      else if (k === "final") [84, 88, 91, 96].forEach(function (m, j) { fm(when + 0.2 + j * 0.12, mtof(m), { ratio: 3.5, index: 3, g: 0.025, r: 2.2, pan: -0.45 + j * 0.3 }); });
    }
    return {
      on: function () { return enabled; },
      enable: function (on) {
        enabled = !!on && init();
        if (ctx) {
          if (enabled && ctx.state === "suspended") ctx.resume();
          master.gain.setTargetAtTime(enabled ? 0.5 : 0, ctx.currentTime, 0.05);
        }
      },
      reset: function (t, playing) {
        if (!ctx) return;
        newBus();
        until = t;
      },
      tick: function (t, prev, playing) {
        if (!enabled || !ctx || !playing || !bus) return;
        var now = ctx.currentTime;
        if (t > prev && t - prev < 0.3) SFX.forEach(function (e) { if (e[0] >= prev && e[0] < t) sfx(e[1], now + 0.01); });
        var ahead = t + 0.25;
        // Eighth notes counted from the start of the chapter each falls in.
        for (var ci = 0; ci < CHAPTERS.length; ci++) {
          var cs = CHAPTERS[ci][0], ce = ci + 1 < CHAPTERS.length ? CHAPTERS[ci + 1][0] : DUR;
          if (ce <= until || cs >= ahead) continue;
          for (var n = Math.max(0, Math.ceil((until - cs) / EIGHTH - 1e-6)); cs + n * EIGHTH < Math.min(ce, ahead) - 1e-6; n++) {
            var nt = cs + n * EIGHTH;
            if (nt >= t - 0.02) music(n, nt, now + Math.max(0, nt - t) + 0.02);
          }
        }
        until = Math.max(until, ahead);
        if (t > 77.5) bus.gain.setTargetAtTime(0.0001, now, 0.8);
      },
      close: function () { if (ctx) try { ctx.close(); } catch (e) {} },
    };
  }

  // --------------------------------------------------------------------------- build

  function build(el) {
    if (!document.getElementById("ykf-font")) {
      var lk = document.createElement("link");
      lk.id = "ykf-font";
      lk.rel = "stylesheet";
      lk.href = "https://fonts.googleapis.com/css2?family=Inter+Tight:wght@600;650;700&display=swap";
      document.head.appendChild(lk);
    }
    if (!document.getElementById("ykf-css")) {
      var st = document.createElement("style");
      st.id = "ykf-css";
      st.textContent = CSS;
      document.head.appendChild(st);
    }
    var reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    var PLAY = '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M8 5.5v13l11-6.5z"/></svg>';
    var PAUSE = '<svg viewBox="0 0 24 24" fill="currentColor"><rect x="6.5" y="5" width="4" height="14" rx="1.2"/><rect x="13.5" y="5" width="4" height="14" rx="1.2"/></svg>';
    var SND_ON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 9v6h4l5 4V5L8 9z" fill="currentColor"/><path d="M16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12"/></svg>';
    var SND_OFF = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 9v6h4l5 4V5L8 9z" fill="currentColor"/><path d="M17 9.5l5 5M22 9.5l-5 5"/></svg>';
    var REPLAY = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 12a8 8 0 1 0 2.4-5.7"/><path d="M4 4v4h4"/></svg>';

    el.innerHTML =
      '<div class="ykf" role="region" aria-label="Video: what Yukta is, in 80 seconds">' +
      '<div class="ykf-stage">' + svgMarkup() +
      '<div class="ykf-kick" aria-hidden="true"><b></b><div><small>Chapter</small><span></span></div></div>' +
      '<button class="ykf-big" type="button" aria-label="Watch with sound, 80 seconds">' + PLAY + "<span>Watch with sound · 80 s</span></button>" +
      '<div class="ykf-thin" aria-hidden="true"><i></i></div>' +
      '<div class="ykf-ctl"><div class="ykf-bar"><button class="ykf-pp" type="button" aria-label="Play">' + PLAY + "</button>" +
      '<span class="ykf-time">0:00 / ' + fmt(DUR) + "</span>" +
      '<div class="ykf-track"><div class="ykf-rail"></div><div class="ykf-fill"></div>' +
      CHAPTERS.slice(1).map(function (c) { return '<div class="ykf-tick" style="left:' + (c[0] / DUR * 100).toFixed(2) + '%"></div>'; }).join("") +
      '<div class="ykf-knob"></div><input class="ykf-range" type="range" min="0" max="' + DUR + '" step="0.05" value="0" aria-label="Seek"></div>' +
      '<button class="ykf-snd" type="button" aria-pressed="false"></button></div>' +
      '<div class="ykf-chap" aria-label="Chapters">' + CHAPTERS.map(function (c, i) { return '<button type="button" data-c="' + i + '">' + c[1] + "</button>"; }).join("") + "</div></div></div>" +
      '<div class="ykf-cap" aria-live="polite"></div></div>';

    var root = el.firstChild;
    var svg = root.querySelector("svg");
    var capEl = root.querySelector(".ykf-cap");
    var kickEl = root.querySelector(".ykf-kick");
    var sndBtn = root.querySelector(".ykf-snd");
    var big = root.querySelector(".ykf-big");
    var pp = root.querySelector(".ykf-pp");
    var timeEl = root.querySelector(".ykf-time");
    var range = root.querySelector(".ykf-range");
    var fill = root.querySelector(".ykf-fill");
    var knob = root.querySelector(".ykf-knob");
    var thin = root.querySelector(".ykf-thin i");
    var chapBtns = [].slice.call(root.querySelectorAll(".ykf-chap button"));
    var Q = {};
    [].forEach.call(svg.querySelectorAll("[data-k]"), function (n) { Q[n.getAttribute("data-k")] = n; });

    function op(k, v) {
      var n = Q[k];
      v = v < 0.004 ? 0 : v > 1 ? 1 : v;
      n.style.opacity = v;
      n.style.display = v ? "" : "none";
      return v;
    }
    function tr(k, x, y, s, r) {
      Q[k].setAttribute("transform", "translate(" + x.toFixed(1) + " " + y.toFixed(1) + ")" + (s != null && s !== 1 ? " scale(" + s.toFixed(4) + ")" : "") + (r ? " rotate(" + r.toFixed(1) + ")" : ""));
    }
    function txt(k, s) { if (Q[k].textContent !== s) Q[k].textContent = s; }

    // Stroke lengths for line drawing.
    var LEN = {};
    for (var i = 0; i < 9; i++) LEN["lp" + i] = Q["lp" + i].getTotalLength();
    LEN.jump = Q.jump.getTotalLength();
    var TRL = [];
    for (var j = 0; j < 6; j++) TRL.push(Q["tr" + j].getTotalLength());
    function draw(k, u) {
      Q[k].style.strokeDasharray = LEN[k];
      Q[k].style.strokeDashoffset = LEN[k] * (1 - u);
    }
    function pop(k, t0, cx, cy) {
      var s = eb(cl((T - t0) / 0.5));
      Q[k].setAttribute("transform", "translate(" + cx + " " + cy + ") scale(" + Math.max(0.001, s).toFixed(4) + ") translate(" + -cx + " " + -cy + ")");
    }
    function setBadge(i, v) {
      var g = Q["bd" + i];
      if (!v) { op("bd" + i, 0); return; }
      op("bd" + i, 1);
      g.setAttribute("class", v[1]);
      var t = g.querySelector("text");
      if (t.textContent !== v[0]) t.textContent = v[0];
      var w = chipW(v[0]) - 4;
      var r = g.querySelector("rect");
      r.setAttribute("x", -w / 2);
      r.setAttribute("width", w);
    }

    var T = 0;
    var capIdx = -1;
    var audio = makeAudio();
    var playing = false;

    // ------------------------------------------------------------------ render(t)

    function render(t) {
      var prevT = T;
      T = t;
      // A slow push-in through every chapter, and colour that drifts behind everything.
      var ch0 = 0, ch1 = DUR;
      for (var h0 = 0; h0 < CHAPTERS.length; h0++) if (t >= CHAPTERS[h0][0]) { ch0 = CHAPTERS[h0][0]; ch1 = h0 + 1 < CHAPTERS.length ? CHAPTERS[h0 + 1][0] : DUR; }
      var kb = reduce ? 0 : eio(cl((t - ch0) / (ch1 - ch0)));
      var su = (t - 45.6) / 0.5, shake = !reduce && su > 0 && su < 1 ? (1 - su) * 7 : 0;
      Q.cam.setAttribute("transform", "translate(" + (640 + Math.sin(t * 95) * shake).toFixed(2) + " " + (360 + Math.cos(t * 83) * shake * 0.6).toFixed(2) + ") scale(" + (1 + 0.022 * kb).toFixed(4) + ") translate(-640 -" + (360 - 4 * kb).toFixed(2) + ")");
      // Letterbox bars close in on the dramatic beats.
      var lbv = Math.max(win(t, 13.4, 19.3, 0.5), win(t, 44.8, 47.4, 0.4), win(t, 57.4, 59.3, 0.4), win(t, 62, 64.4, 0.4));
      Q.lbT.setAttribute("height", (52 * eio(lbv)).toFixed(1));
      Q.lbB.setAttribute("y", (720 - 52 * eio(lbv)).toFixed(1));
      Q.lbB.setAttribute("height", (52 * eio(lbv)).toFixed(1));
      // Branded wipes cover the cuts between the big scenes.
      var wv = null;
      [19.45, 24.95, 73.45].forEach(function (c) { if (t > c - 0.5 && t < c + 0.5) wv = (t - (c - 0.5)) / 1.0; });
      op("wipe", wv == null ? 0 : 1);
      if (wv != null) Q.wipe.setAttribute("transform", "translate(" + lerp(-1900, 1500, eio(wv)).toFixed(1) + " 0)");
      tr("glowB", 300 + Math.sin(t * 0.13) * 160, 200 + Math.cos(t * 0.11) * 90);
      tr("glowO", 1020 + Math.cos(t * 0.09) * 140, 560 + Math.sin(t * 0.12) * 80);
      var kc = -1;
      for (var h1 = 0; h1 < KICK.length; h1++) if (t >= CHAPTERS[h1][0] + 0.25 && t < CHAPTERS[h1][0] + 3.4) kc = h1;
      kickEl.classList.toggle("on", kc >= 0);
      if (kc >= 0 && kickEl.dataset.c !== String(kc)) {
        kickEl.dataset.c = kc;
        kickEl.querySelector("b").textContent = ("0" + (kc + 1)).slice(-2);
        kickEl.querySelector("span").textContent = KICK[kc];
      }
      audio.tick(t, prevT, playing);
      var dof = Math.max(pr(t, 39, 40.3) - pr(t, 43.5, 44.6), pr(t, 62, 62.9) - pr(t, 64.2, 65.1));
      Q.bgL.style.filter = dof > 0.02 && !reduce ? "blur(" + (5 * dof).toFixed(2) + "px)" : "";
      // Background traces: light runs along them, always.
      for (var j = 0; j < 6; j++) {
        var u = ((t * 0.18 + j * 0.23) % 1.4);
        var pt = Q["tr" + j].getPointAtLength(Math.min(u, 1) * TRL[j]);
        Q["tp" + j].setAttribute("cx", pt.x);
        Q["tp" + j].setAttribute("cy", pt.y);
        Q["tp" + j].style.opacity = u > 1 ? 0 : 0.9;
      }

      // ---------------------------------------------------- scenes A and B
      var aOn = 1 - pr(t, 19, 19.8);
      op("sceneA", aOn);
      op("sceneA2", aOn);
      if (aOn) {
        var zoomOut = "translate(640 360) scale(" + (1 - 0.04 * pr(t, 19, 19.8)) + ") translate(-640 -360)";
        Q.sceneA.setAttribute("transform", zoomOut);
        Q.sceneA2.setAttribute("transform", zoomOut);
        var lv = pr(t, 0.4, 1.2, eb);
        Q.list.setAttribute("transform", "translate(" + (768 + (1 - lv) * 60) + " 184)");
        op("list", pr(t, 0.4, 0.9));
        for (var i = 0; i < 6; i++) {
          op("li" + i, pr(t, 1.1 + i * 0.45, 1.5 + i * 0.45));
          var tick = i === 0 ? 0 : pr(t, 12.5 + i * 0.28, 12.75 + i * 0.28);
          op("tk" + i, tick);
        }
        var sk = pr(t, 12.2, 12.6);
        Q.strike.setAttribute("x2", 60 + 150 * sk);
        op("strike", sk ? 1 : 0);
        op("skipTag", pr(t, 15.2, 15.6));
        // "Every time": ghost copies stack behind the list.
        for (var g = 0; g < 3; g++) {
          var gv = pr(t, 5 + g * 0.6, 5.6 + g * 0.6, eb);
          op("g" + g, Math.min(gv, 1));
          tr("g" + g, 768 + (g + 1) * 16 * gv, 184 - (g + 1) * 38 * gv, 1, (g + 1) * 1.2 * gv);
        }
        // Clock.
        op("clock", pr(t, 4.6, 5.1));
        var ck = Math.max(0, t - 4.6);
        Q.hand1.setAttribute("transform", "rotate(" + (ck * 40).toFixed(1) + ")");
        Q.hand2.setAttribute("transform", "rotate(" + (ck * 480).toFixed(1) + ")");
        op("desk", 1);
        op("cost", pr(t, 15.6, 16.0));
        var cb = pr(t, 15.8, 18.8, function (x) { return x; });
        Q.costBar.setAttribute("width", (10 + 204 * cb).toFixed(1));
        txt("costTxt", "$" + (0.4 + cb * 37.6).toFixed(2));
        op("alert", pr(t, 15.0, 15.4));
        tr("alert", 1040, 186, eb(cl((t - 15) / 0.5)));
        op("huh", win(t, 16.2, 19.2));
        tr("huh", 470, 280 - 10 * pr(t, 16.2, 16.6));
        op("bubA", win(t, 9.6, 12.6));
        tr("bubA", 250, 196, eb(cl((t - 9.6) / 0.45)));
        op("bubDone", win(t, 14, 16.4));
        tr("bubDone", 690, 92, eb(cl((t - 14) / 0.4)));
      }

      // ---------------------------------------------------- the person
      // Figures outside the camera step aside while it is zoomed in (the agent dive, the approval push-in).
      var zoom = Math.max(pr(t, 39, 39.8) * (1 - pr(t, 43.8, 44.6)), pr(t, 62, 62.6) * (1 - pr(t, 64.3, 65)));
      var youOn = Math.max(1 - pr(t, 19, 19.6), win(t, 25, 73.8, 0.6) * (1 - zoom));
      op("you", youOn);
      if (youOn) {
        if (t < 22) tr("you", 430, 680, 1);
        else {
          var yp = kf(t, [[25, 150, 680, 0.58], [25.8, 160, 600, 0.58], [73, 160, 600, 0.58]]);
          tr("you", yp[0], yp[1], yp[2]);
        }
        var bob = Math.sin(t * 1.6) * 1.5;
        Q.headG.setAttribute("transform", "translate(0 " + bob.toFixed(2) + ") rotate(" + (Math.sin(t * 0.7) * 4).toFixed(1) + " 0 -284)");
        Q.eyes.setAttribute("transform", "translate(0 -316) scale(1 " + ((t + 0.7) % 3.3 < 0.13 ? 0.1 : 1) + ") translate(0 316)");
        var atDesk = t < 22;
        op("armsDesk", atDesk ? 1 : 0);
        op("armsSide", atDesk ? 0 : 1);
        if (atDesk) {
          var ty = t < 9 ? Math.sin(t * 18) * 3 : 0, ty2 = t < 9 ? Math.sin(t * 18 + 1.7) * 3 : 0;
          Q.armDL.setAttribute("d", "M-45 -250 Q-70 -206 -56 -184 L" + (-30 + ty).toFixed(1) + " " + (-176 + ty2).toFixed(1));
          Q.armDR.setAttribute("d", "M45 -250 Q70 -206 56 -184 L" + (30 - ty2).toFixed(1) + " " + (-176 + ty).toFixed(1));
        } else {
          // Talking (25.8-28.8) and reaching for the phone (62.2-64): the right hand comes up.
          var lift = Math.max(win(t, 25.8, 28.8, 0.4), win(t, 62.2, 64.2, 0.4));
          Q.armSR.setAttribute("transform", "rotate(" + (-55 * lift + Math.sin(t * 1.3) * 2).toFixed(1) + " 45 -250)");
          Q.armSL.setAttribute("transform", "rotate(" + (Math.sin(t * 1.3 + 1) * 2).toFixed(1) + " -45 -250)");
        }
        var talk = (t > 9.6 && t < 11.8) || (t > 25.8 && t < 28.4);
        Q.mouth.setAttribute("d", talk && Math.sin(t * 16) > 0 ? "M-7 -299 q7 9 14 0 q-7 3 -14 0" : t > 13.4 && t < 19 ? "M-6 -295 q6 -4 12 0" : "M-7 -298 q7 6 14 0");
      }

      // ---------------------------------------------------- the AI
      var aiOn = Math.max(win(t, 9, 19.6, 0.5), win(t, 25, 73.4, 0.6));
      op("ai", aiOn * (1 - zoom));
      if (aiOn) {
        var ap;
        if (t < 22) ap = kf(t, [[9, 1400, 180, 1], [10.2, 640, 250, 1], [12.1, 640, 250, 1], [12.6, 820, 250, 0.7], [13.9, 1080, 440, 0.7], [14.3, 620, 230, 1], [19.6, 620, 230, 1]]);
        else ap = kf(t, [[25, 150, 230, 0.72], [26, 330, 220, 0.72], [80, 330, 220, 0.72]]);
        tr("ai", ap[0], ap[1], ap[2]);
        // It points while it works: ticking the list, sending the workflow, sending Step and Resume.
        var point = Math.max(win(t, 12.3, 14.1, 0.25), win(t, 28.8, 34.4, 0.4), win(t, 49.5, 50.5, 0.2), win(t, 59.1, 60.4, 0.2));
        robo(Q, "ai", t, Math.sin(t * 1.4) * 6, point, false);
      }
      // MCP lights while it builds, the CLI for Step, REST for Resume through the supervisor.
      var viaOn = win(t, 25.6, 73.4, 0.5) * (1 - zoom);
      op("via", viaOn);
      if (viaOn) [[28.6, 34.8], [49.5, 50.6], [59.1, 60.5]].forEach(function (w, i) {
        var lit = win(t, w[0], w[1], 0.2);
        op("via" + i, pr(t, 25.6 + i * 0.25, 26.1 + i * 0.25));
        Q["via" + i].setAttribute("class", lit > 0.5 ? "lit" : "btn");
        Q["viaS" + i].setAttribute("transform", "scale(" + (1 + 0.12 * lit).toFixed(3) + ")");
      });
      var tab = t < 35 || t >= 66 ? 0 : t >= 38.4 && t < 44.8 ? 2 : t >= 61.4 ? 3 : 1;
      for (var tb = 0; tb < 4; tb++) Q["tab" + tb].setAttribute("class", tb === tab ? "info" : "btn");

      // ---------------------------------------------------- scene C: the logo
      var lo = win(t, 19.6, 25, 0.5);
      op("logo", lo);
      if (lo) {
        pop("lgTile", 19.8, 124, 125);
        for (var p = 0; p < 9; p++) draw("lp" + p, pr(t, 20.3 + p * 0.09, 20.9 + p * 0.09));
        var CC = [[54, 49], [82, 120], [165, 77], [165, 163], [206, 204]];
        CC.forEach(function (c, k) { pop("lc" + k, 20.8 + k * 0.18, c[0], c[1]); });
        op("lgT", pr(t, 21.9, 22.4));
        tr("lgT", 0, 16 * (1 - pr(t, 21.9, 22.5)));
        op("lgS", pr(t, 22.5, 23));
      }

      // ---------------------------------------------------- scenes D-H: the workflow
      var wo = win(t, 25, 73.8, 0.6);
      op("world", wo);
      if (wo) world(t);
      if (!wo) { op("bubD", 0); op("bubOn", 0); op("phone", 0); op("beam", 0); op("beamDot", 0); op("cursor", 0); }

      // ---------------------------------------------------- end card
      var eo2 = pr(t, 73.6, 74.4);
      op("end", eo2);
      if (eo2) {
        Q.end.setAttribute("transform", "translate(0 " + (14 * (1 - eo2)).toFixed(1) + ")");
        for (var e = 0; e < 4; e++) {
          op("pill" + e, pr(t, 74.6 + e * 0.35, 75.1 + e * 0.35));
        }
      }

      // ---------------------------------------------------- captions and controls
      var ci = -1;
      for (var c = 0; c < CAPS.length; c++) if (t >= CAPS[c][0] && t < CAPS[c][1]) ci = c;
      if (t >= DUR) ci = CAPS.length - 1;
      if (ci !== capIdx) {
        capIdx = ci;
        var em = 0;
        capEl.innerHTML = ci < 0 ? "" : "<p>" + CAPS[ci][2].split(" ").map(function (w, i) {
          var open = w.charAt(0) === "*" ? 1 : w.charAt(0) === "_" ? 2 : 0;
          if (open) { em = open; w = w.slice(1); }
          var cls = em;
          var close = /[*_]([.,?!:]*)$/.exec(w);
          if (close) { w = w.slice(0, close.index) + close[1]; em = 0; }
          return '<i' + (cls ? ' class="e' + cls + '"' : "") + ' style="animation-delay:' + (i * 55) + 'ms">' + esc(w) + "</i>";
        }).join(" ") + "</p>";
      }
      var f = (t / DUR * 100).toFixed(2) + "%";
      fill.style.width = f;
      knob.style.left = f;
      thin.style.width = f;
      if (document.activeElement !== range) range.value = t;
      timeEl.textContent = fmt(t) + " / " + fmt(DUR);
      var chap = 0;
      for (var h = 0; h < CHAPTERS.length; h++) if (t >= CHAPTERS[h][0]) chap = h;
      chapBtns.forEach(function (b, i) { b.classList.toggle("on", i === chap); });
    }

    function world(t) {
      // Camera: dive into the agent session and back out.
      var z = pr(t, 39, 40.3, eio) - pr(t, 43.5, 44.6, eio);
      var z2 = pr(t, 62.0, 62.9, eio) - pr(t, 64.2, 65.1, eio);
      var s = (1 + 1.55 * z) * (1 + 0.32 * z2), fx = lerp(lerp(640, NX[2], z), NX[4] - 70, z2), fy = lerp(lerp(360, NY, z), 262, z2);
      // The workflow scenes sit a little higher and smaller, to leave the bottom for the captions.
      var lift = 1 - z;
      Q.world.setAttribute("transform", "translate(640 " + (360 - 40 * lift).toFixed(1) + ") scale(" + (s * (1 - 0.07 * lift)).toFixed(4) + ") translate(" + -fx.toFixed(2) + " " + -fy.toFixed(2) + ")");

      op("panel", pr(t, 25.2, 25.8));
      Q.panel.setAttribute("transform", "translate(0 " + (30 * (1 - pr(t, 25.2, 26))).toFixed(1) + ")");
      var vv = pr(t, 34.2, 34.6) * (1 - win(t, 61.8, 64.6, 0.3));
      op("valid", vv);
      Q.valid.setAttribute("class", "ok");

      // Build: chips fly from your AI to each node, and the node pops in.
      // Your AI stands outside the camera; in the workflow's own coordinates it is here. w2s maps a workflow
      // point to the screen while the camera is not zoomed in.
      var aiX = 640 + (330 - 640) / 0.93, aiY = 360 + (220 - 320) / 0.93;
      var w2s = function (x, y) { return [640 + (x - 640) * 0.93, 320 + (y - 360) * 0.93]; };
      var chipK = -1;
      for (var k = 0; k < 6; k++) {
        var t0 = 29 + k * 0.85;
        if (t >= t0 && t < t0 + 0.7) chipK = k;
        var born = t0 + 0.65;
        var ns = t < born ? 0 : eb(cl((t - born) / 0.45));
        Q["n" + k].setAttribute("transform", "translate(" + NX[k] + " " + NY + ") scale(" + Math.max(0.001, ns).toFixed(4) + ")");
        op("n" + k, ns ? 1 : 0);
        var lb = pr(t, born + 0.15, born + 0.5);
        op("nt" + k, lb);
        op("np" + k, lb);
        var ru = (t - born) / 0.8;
        op("rp" + k, ru > 0 && ru < 1 ? 0.7 * (1 - ru) : 0);
        Q["rp" + k].setAttribute("r", (40 + 46 * eo(cl(ru))).toFixed(1));
        if (k < 5) op("e" + k, pr(t, 29 + (k + 1) * 0.85 + 0.65, 29 + (k + 1) * 0.85 + 1.0));
      }
      txt("np0", t > 67.6 && t < 73.4 ? "WEBHOOK_TRIGGER" : "API_TRIGGER");
      if (chipK >= 0) {
        var cu = eio(cl((t - (29 + chipK * 0.85)) / 0.7));
        var cx = lerp(aiX + 40, NX[chipK], cu), cy = lerp(aiY - 40, NY - 64, cu) - Math.sin(cu * Math.PI) * 60;
        txt("chipT", CHIPS[chipK]);
        var w = chipW(CHIPS[chipK]);
        Q.chipR.setAttribute("width", w);
        Q.chip.setAttribute("transform", "translate(" + (cx - w / 2).toFixed(1) + " " + cy.toFixed(1) + ")");
        op("chip", Math.min(1, cu * 6, (1 - cu) * 8));
      } else op("chip", 0);
      var mo = win(t, 28.6, 34.8, 0.4);
      op("mcp", mo);
      Q.mcp.style.strokeDashoffset = (-t * 40).toFixed(1);

      // Bubbles.
      op("bubD", win(t, 25.8, 28.8));
      tr("bubD", 14, 282, eb(cl((t - 25.8) / 0.45)));
      op("bubOn", win(t, 28.4, 30.2));
      tr("bubOn", 384, 92, eb(cl((t - 28.4) / 0.4)));

      // Token.
      var ti = kf(t, TOK)[0];
      var tokOn = !inAny(t, TOK_HIDE);
      op("tok", tokOn ? 1 - pr(t, 39.6, 40.0) + pr(t, 43.4, 43.8) : 0);
      if (tokOn) {
        var a = Math.floor(ti), b = Math.min(5, a + 1), fr = ti - a;
        tr("tok", lerp(NX[a], NX[b], fr), NY);
        var held = inAny(t, HOLD) || (t > 61.9 && t < 63.7) || (t > 50.9 && t < 51.66);
        Q.tokHalo.setAttribute("r", (22 + (held ? 8 * (0.5 + 0.5 * Math.sin(t * 6)) : 3 * Math.sin(t * 4))).toFixed(1));
        Q.tokHalo.setAttribute("class", held ? "f-orange" : "f-blue2");
      }
      var hv = inAny(t, HOLD) ? 1 : 0;
      op("hold", hv);
      if (hv) {
        var hx = t < 52 ? NX[3] : NX[2];
        tr("hold", hx, NY + 150);
        txt("holdT", t < 52 ? "paused · by you" : "paused · supervisor");
        Q.hold.querySelector("rect").setAttribute("x", t < 52 ? -66 : -82);
        Q.hold.querySelector("rect").setAttribute("width", t < 52 ? 132 : 164);
      }

      // Badges.
      for (var n = 0; n < 6; n++) setBadge(n, badgeAt(n, t));
      var focus = t > 44.8 && t < 47.3 ? [2, 3, 5] : t > 57.4 && t < 59.2 ? [2] : t > 62 && t < 64.3 ? [4] : null;
      var fk = focus ? Math.min(win(t, 44.8, 47.3, 0.35) + win(t, 57.4, 59.2, 0.35) + win(t, 62, 64.3, 0.35), 1) : 0;
      for (var m = 0; m < 6; m++) {
        var dim = focus && focus.indexOf(m) < 0 ? 1 - 0.7 * fk : 1;
        ["n" + m, "nt" + m, "np" + m].forEach(function (key) { if (Q[key].style.display !== "none") Q[key].style.opacity = (parseFloat(Q[key].style.opacity || 1) * dim).toFixed(3); });
        if (m < 5) { var ek = "e" + m; if (Q[ek].style.display !== "none") Q[ek].style.opacity = (parseFloat(Q[ek].style.opacity || 1) * (focus ? 1 - 0.6 * fk : 1)).toFixed(3); }
      }

      // Inside the agent session.
      var io = z > 0.6 ? pr(t, 39.9, 40.4) * (1 - pr(t, 43.3, 43.6)) : 0;
      op("inside", io);
      if (io) {
        var tp = cl((t - 40.3) / 2.8) * TERM.length;
        for (var l = 0; l < TERM.length; l++) {
          var full = TERM[l];
          txt("tl" + l, l < Math.floor(tp) ? full : l === Math.floor(tp) ? full.slice(0, Math.floor((tp % 1) * full.length)) : "");
        }
        robo(Q, "ai2", t * 1.6, Math.sin(t * 2) * 5, 0.5 + 0.5 * Math.sin(t * 5), false);
      }
      var bud = t < 51.7 ? 0.7 * pr(t, 40.2, 46, function (x) { return x; }) : t < 66 ? 0.8 * pr(t, 55.6, 57.5, function (x) { return x; }) : 0;
      op("budget", bud > 0.005 ? 1 : 0);
      Q.budBar.setAttribute("width", (Math.max(8, 120 * bud)).toFixed(1));
      Q.budBar.setAttribute("class", bud >= 0.795 ? "f-orange" : "f-blue");
      txt("budTxt", "token budget " + Math.round(bud * 100) + "%");

      // The skip attempt.
      var ju = pr(t, 45, 45.7);
      op("jump", win(t, 45, 46.8, 0.25));
      Q.jump.style.strokeDasharray = "9 9";
      Q.jump.style.strokeDashoffset = 0;
      Q.jump.setAttribute("stroke-dasharray", LEN.jump);
      Q.jump.style.strokeDasharray = (LEN.jump * 0.5 * ju).toFixed(1) + " " + LEN.jump;
      op("wall", win(t, 45.55, 47.2, 0.25));
      tr("wall", (NX[2] + NX[5]) / 2 - 20, NY - 118, eb(cl((t - 45.55) / 0.4)));

      // Control bar, the cursor and your AI's commands.
      op("ctl", win(t, 47.2, 66.2, 0.4));
      var presses = [[48.4, 0], [50.2, 1], [51.6, 3], [60.2, 2]];
      for (var bI = 0; bI < 4; bI++) {
        var pressed = 0;
        presses.forEach(function (q) { if (q[1] === bI) pressed = Math.max(pressed, win(t, q[0] - 0.05, q[0] + 0.35, 0.1)); });
        Q["b" + bI].setAttribute("class", "btn" + (pressed > 0.5 ? " on" : ""));
        Q["bi" + bI].setAttribute("transform", "scale(" + (1 - 0.06 * pressed).toFixed(3) + ")");
      }
      var who = t < 48.4 ? null : t < 50.2 ? ["you", "Pause · you · web UI"] : t < 51.6 ? ["ai", "Step · your AI · CLI"] : t < 52.4 ? ["you", "Restart · you · web UI"] :
        t > 59.4 && t < 61.5 ? ["ai", "Resume · via supervisor"] : null;
      op("who", who ? 1 : 0);
      if (who) {
        txt("whoT", who[1]);
        Q.who.setAttribute("class", who[0] === "you" ? "wait" : "info");
        var ww = chipW(who[1]);
        Q.who.querySelector("rect").setAttribute("x", -ww / 2);
        Q.who.querySelector("rect").setAttribute("width", ww);
      }
      var cur = kf(t, [[47.4, 1300, 760], [48.3, 616, 556], [48.8, 616, 556], [51.0, 1012, 556], [51.5, 1012, 556], [52.4, 1300, 760],
        [62.4, 1300, 760], [63.5, 978, 246], [64.4, 978, 246], [65, 1300, 760]]);
      var curOn = win(t, 47.4, 52.4, 0.3) + win(t, 62.4, 65, 0.3);
      op("cursor", curOn);
      if (curOn) tr("cursor", cur[0], cur[1]);
      var clk = Math.max(win(t, 48.35, 48.75, 0.12), win(t, 51.55, 51.95, 0.12), win(t, 63.6, 64.0, 0.12));
      op("click", clk);
      Q.click.setAttribute("r", (6 + 22 * (1 - clk)).toFixed(1));

      // Beams from your AI: Step at 50, then Resume through the supervisor at 59.5.
      var beamOn = 0, bp = null;
      if (t > 49.6 && t < 50.4) { bp = [[360, 222], w2s(746, 546), cl((t - 49.6) / 0.6)]; beamOn = win(t, 49.6, 50.4, 0.15); }
      else if (t > 59.2 && t < 60.3) {
        var u2 = cl((t - 59.2) / 1.0);
        bp = u2 < 0.5 ? [[360, 222], w2s(825, 196), u2 * 2] : [w2s(825, 196), w2s(NX[2], NY - 50), (u2 - 0.5) * 2];
        beamOn = win(t, 59.2, 60.3, 0.15);
      }
      op("beam", beamOn * 0.8);
      op("beamDot", beamOn);
      if (bp) {
        var A = bp[0], B = bp[1], mx = (A[0] + B[0]) / 2, my = Math.min(A[1], B[1]) - 80;
        Q.beam.setAttribute("d", "M" + A[0] + " " + A[1] + " Q " + mx + " " + my + " " + B[0] + " " + B[1]);
        var uu = eio(bp[2]), qx = (1 - uu) * (1 - uu) * A[0] + 2 * (1 - uu) * uu * mx + uu * uu * B[0], qy = (1 - uu) * (1 - uu) * A[1] + 2 * (1 - uu) * uu * my + uu * uu * B[1];
        Q.beamDot.setAttribute("cx", qx.toFixed(1));
        Q.beamDot.setAttribute("cy", qy.toFixed(1));
        Q.beam.style.strokeDashoffset = (-t * 60).toFixed(1);
      }

      // Supervisor.
      var so = pr(t, 52.6, 53.8);
      op("sup", so * (1 - pr(t, 72.8, 73.4)));
      var flash = win(t, 57.5, 58.4, 0.15);
      Q.bcc.setAttribute("class", flash > 0.3 || (t > 59.6 && t < 60.0) ? "f-orange" : "f-blue2");
      Q.bcc.setAttribute("r", (7 + 4 * flash).toFixed(1));
      var spu = cl((t - 57.6) / 0.6);
      op("supPulse", t > 57.6 && t < 58.25 ? 1 : 0);
      Q.supPulse.setAttribute("cx", lerp(825, NX[2], eio(spu)).toFixed(1));
      Q.supPulse.setAttribute("cy", lerp(214, NY - 46, eio(spu)).toFixed(1));

      // Ask, and the phone that buzzes for you.
      op("ask", win(t, 62.1, 64.2, 0.3));
      tr("ask", NX[4], 140, eb(cl((t - 62.1) / 0.45)));
      var ap2 = Q.approve;
      ap2.setAttribute("transform", "translate(-58 100) scale(" + (1 - 0.06 * win(t, 63.6, 64.0, 0.1)).toFixed(3) + ")");
      var ph = win(t, 61.4, 62.4, 0.25);
      op("phone", ph);
      if (ph) tr("phone", 250 + Math.sin(t * 60) * 2.5 * (t < 63.4 ? 1 : 0), 500, 1, Math.sin(t * 60) * 3 * (t < 63.4 ? 1 : 0));

      // The release goes out: a burst at Deploy.
      [64.9, 71.2].forEach(function (t0, j) {
        var bu = (t - t0) / 1.0;
        op("bst" + j, bu > 0 && bu < 1 ? 1 : 0);
        if (bu > 0 && bu < 1) for (var i = 0; i < 12; i++) {
          var a = i / 12 * Math.PI * 2 + j, rr2 = 30 + (i % 2 ? 60 : 84) * eo(bu);
          var d = Q["bd" + j + "_" + i];
          d.setAttribute("cx", (Math.cos(a) * rr2).toFixed(1));
          d.setAttribute("cy", (Math.sin(a) * rr2 - 30 * bu * bu).toFixed(1));
          d.style.opacity = (1 - bu).toFixed(2);
        }
      });

      // Anything can start a run; this time a webhook does, and the trigger step says so.
      op("trig", win(t, 66.3, 73.4, 0.4));
      for (var g = 0; g < 6; g++) {
        var gp = eb(cl((t - 66.5 - g * 0.15) / 0.4));
        Q["tg" + g].setAttribute("transform", "translate(" + TGX[g] + " 546) scale(" + Math.max(0.001, gp).toFixed(3) + ")");
        Q["tg" + g].setAttribute("class", g === 2 && t > 67.4 ? "lit" : "btn");
      }
      var du = cl((t - 67.45) / 0.45);
      op("tgDot", t > 67.45 && t < 67.9 ? 1 : 0);
      if (du > 0 && du < 1) {
        var de = eio(du), dx0 = TGX[2], dmx = (TGX[2] + NX[0]) / 2, dmy = 470;
        Q.tgDot.setAttribute("cx", ((1 - de) * (1 - de) * dx0 + 2 * (1 - de) * de * dmx + de * de * NX[0]).toFixed(1));
        Q.tgDot.setAttribute("cy", ((1 - de) * (1 - de) * 528 + 2 * (1 - de) * de * dmy + de * de * (NY + 40)).toFixed(1));
      }
    }

    function badgeAt(n, t) {
      if (n === 1) {
        if ((t >= 37.6 && t < 51.8) || (t >= 54.9 && t < 66) || (t >= 68.6 && t < 73.2)) return ["2 problems", "bad"];
      }
      if (n === 2) {
        if ((t >= 46.4 && t < 51.8) || (t >= 60.4 && t < 66) || (t >= 69.1 && t < 73.2)) return ["fixed", "ok"];
        if ((t >= 38.4 && t < 46.4) || (t >= 55.5 && t < 58.1)) return ["working", "info"];
        if (t >= 58.1 && t < 60.4) return ["paused at 80%", "wait"];
      }
      if (n === 3 && ((t >= 47.4 && t < 51.8) || (t >= 61.3 && t < 66) || (t >= 69.6 && t < 73.2))) return ["passed", "ok"];
      if (n === 4) {
        if ((t >= 50.9 && t < 51.7) || (t >= 61.9 && t < 63.7)) return ["waiting for you", "wait"];
        if ((t >= 63.7 && t < 66) || (t >= 70.2 && t < 73.2)) return ["approved · you", "ok"];
      }
      if (n === 5 && ((t >= 64.9 && t < 66) || (t >= 71.2 && t < 73.2))) return ["sent", "ok"];
      return null;
    }

    // ------------------------------------------------------------------ playback

    var raf = 0, last = 0, userPaused = false, started = false;
    function setBig() {
      // The big button only invites the first play; pause and replay are the bar's button.
      big.hidden = playing || started;
      pp.innerHTML = playing ? PAUSE : T >= DUR - 0.01 ? REPLAY : PLAY;
      pp.setAttribute("aria-label", playing ? "Pause" : T >= DUR - 0.01 ? "Replay" : "Play");
      wake();
    }
    // While it plays, the controls fade out 2.5 s after the pointer stops (and soon after it leaves);
    // paused, ended or focused (CSS :focus-within) they stay.
    var idleT = 0;
    function wake(ms) {
      clearTimeout(idleT);
      root.classList.remove("idle");
      if (playing) idleT = setTimeout(function () { if (playing) root.classList.add("idle"); }, ms || 2500);
    }
    function loop(now) {
      if (!el.isConnected) { cancelAnimationFrame(raf); audio.close(); return; }
      if (!playing) return;
      var dt = Math.min(0.1, (now - last) / 1000);
      last = now;
      var t = T + dt;
      if (t >= DUR) { t = DUR; playing = false; audio.reset(t, false); }
      render(t);
      if (playing) raf = requestAnimationFrame(loop);
      else setBig();
    }
    function play() {
      if (T >= DUR - 0.01) render(0);
      audio.reset(T, true);
      playing = true;
      started = true;
      last = performance.now();
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(loop);
      setBig();
    }
    function pause() {
      playing = false;
      audio.reset(T, false);
      cancelAnimationFrame(raf);
      setBig();
    }
    function seek(t) {
      render(Math.max(0, Math.min(DUR, t)));
      audio.reset(T, playing);
      started = true;
      setBig();
    }

    big.addEventListener("click", function (e) { e.stopPropagation(); userPaused = false; if (!started) setSound(true); play(); });
    function setSound(on) {
      audio.enable(on);
      try { localStorage.setItem("ykf-sound", on ? "1" : "0"); } catch (x) {}
      sndBtn.setAttribute("aria-pressed", on ? "true" : "false");
      sndBtn.innerHTML = (on ? SND_ON : SND_OFF) + "<span>" + (on ? "Sound on" : "Sound off") + "</span>";
      sndBtn.setAttribute("aria-label", on ? "Turn sound off" : "Turn sound on");
      if (on) audio.reset(T, playing);
    }
    sndBtn.addEventListener("click", function (e) { e.stopPropagation(); setSound(!audio.on()); });
    sndBtn.innerHTML = SND_OFF + "<span>Sound off</span>";
    sndBtn.setAttribute("aria-label", "Turn sound on");
    pp.addEventListener("click", function () { if (playing) { userPaused = true; pause(); } else { userPaused = false; play(); } });
    root.querySelector(".ykf-stage").addEventListener("click", function () { if (playing) { userPaused = true; pause(); } else { userPaused = false; play(); } });
    range.addEventListener("input", function () { seek(parseFloat(range.value)); });
    root.addEventListener("pointermove", function () { wake(); });
    root.addEventListener("pointerdown", function () { wake(); });
    root.addEventListener("pointerleave", function () { wake(600); });
    root.querySelector(".ykf-ctl").addEventListener("click", function (e) { e.stopPropagation(); });
    chapBtns.forEach(function (b) {
      b.addEventListener("click", function () { seek(CHAPTERS[+b.dataset.c][0] + 0.01); if (!playing && !userPaused) play(); });
    });
    root.addEventListener("keydown", function (e) {
      if (e.target === range) return;
      if (e.key === " " || e.key === "k") { e.preventDefault(); pp.click(); }
      if (e.key === "ArrowRight") seek(T + 5);
      if (e.key === "ArrowLeft") seek(T - 5);
    });

    // Play when the film is on screen, unless the reader paused it or asked for reduced motion.
    try {
      new IntersectionObserver(function (es) {
        es.forEach(function (en) {
          if (en.intersectionRatio >= 0.5 && !playing && !userPaused && !reduce && T < DUR - 0.01) play();
          if (en.intersectionRatio < 0.2 && playing) pause();
        });
      }, { threshold: [0, 0.2, 0.5, 1] }).observe(root);
    } catch (e) {}

    render(reduce ? 34.9 : 0);
    if (reduce) started = false;
    setBig();
  }

  // Mount last, once everything above is defined.
  try {
    new MutationObserver(check).observe(document.documentElement, { childList: true, subtree: true });
  } catch (e) {}
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", check);
  else check();
})();
