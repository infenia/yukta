// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/*
 * MiniRun: a five-step workflow that runs in the browser (a simulation, nothing talks to a server).
 * The agent tries to ship without the tests; the graph has no such edge, so the engine refuses,
 * runs the tests and waits for the reader's approval. Styles are the ykm- rules in style.css.
 * Mintlify evaluates only the exported component, so everything lives inside it; the hooks are
 * provided by the Mintlify runtime.
 */
export const MiniRun = () => {
  const NODES = [
    { type: "API_TRIGGER", name: "New ticket" },
    { type: "AGENT_SESSION", name: "Agent writes the fix" },
    { type: "PROCESS_EXECUTOR", name: "Run the tests" },
    { type: "ASK", name: "You approve" },
    { type: "OUTCOME_TERMINAL", name: "Ship it" },
  ];
  const LABEL = { idle: "queued", running: "running", ok: "succeeded", wait: "waiting", fail: "rejected", skip: "skipped" };
  const idle = () => NODES.map(() => "idle");

  const [st, setSt] = useState(idle);
  const [log, setLog] = useState([]);
  const [phase, setPhase] = useState("ready"); // ready | running | asking | done | stopped
  const [skip, setSkip] = useState(0); // 0 none, 1 the agent tries, 2 refused
  const timers = useRef([]);
  const box = useRef(null);

  const clear = () => {
    timers.current.forEach(clearTimeout);
    timers.current = [];
  };

  const slow = () =>
    typeof window === "undefined" || !window.matchMedia || !window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const at = (ms, f) => timers.current.push(setTimeout(f, slow() ? ms : ms * 0.3));
  const set = (i, s) => setSt((p) => p.map((x, j) => (j === i ? s : x)));
  const say = (who, text, kind) => setLog((l) => [...l, { who, text, kind, n: l.length }].slice(-6));

  const run = () => {
    clear();
    setSt(idle());
    setLog([]);
    setSkip(0);
    setPhase("running");
    say("engine", "run started for ticket #4182", "info");
    at(150, () => set(0, "running"));
    at(800, () => {
      set(0, "ok");
      set(1, "running");
      say("agent", "reading the ticket and writing a patch", "info");
    });
    at(2300, () => {
      setSkip(1);
      say("agent", "patch ready; the tests look fine, shipping it now", "warn");
    });
    at(3600, () => {
      setSkip(2);
      say("engine", "refused: no edge leads from the agent to Ship it", "fail");
    });
    at(4900, () => {
      setSkip(0);
      set(1, "ok");
      set(2, "running");
      say("engine", "next step in the graph: Run the tests", "info");
    });
    at(6300, () => {
      set(2, "ok");
      set(3, "wait");
      say("tests", "142 passed", "ok");
      say("engine", "waiting for your approval", "wait");
      setPhase("asking");
    });
  };

  // Plays once by itself when half of it is on screen, up to the approval: the reader then answers.
  // Not with reduced motion, where it waits for Run it.
  useEffect(() => {
    const el = box.current;
    if (!el || !slow() || typeof IntersectionObserver === "undefined") {
      return clear;
    }
    const seen = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting) {
          seen.disconnect();
          run();
        }
      },
      { threshold: 0.5 }
    );
    seen.observe(el);
    return () => {
      seen.disconnect();
      clear();
    };
  }, []);

  const answer = (yes) => {
    if (yes) {
      setPhase("running");
      set(3, "ok");
      set(4, "running");
      say("you", "approved", "ok");
      at(800, () => {
        set(4, "ok");
        say("engine", "run succeeded, every step in order", "ok");
        setPhase("done");
      });
    } else {
      set(3, "fail");
      set(4, "skip");
      say("you", "rejected", "fail");
      say("engine", "run stopped before Ship it", "fail");
      setPhase("stopped");
    }
  };

  return (
    <div ref={box} className="not-prose ykm" role="group" aria-label="A small workflow that runs in your browser">
      <div className={"ykm-graph ykm-skip-" + skip}>
        <svg className="ykm-arc" viewBox="0 0 100 40" preserveAspectRatio="none" aria-hidden="true">
          <path d="M30 40 C 42 2, 78 2, 90 40" vectorEffect="non-scaling-stroke" />
        </svg>
        <span className="ykm-arc-label" aria-hidden="true">
          {skip === 2 ? "✕ no such edge" : "skip the tests?"}
        </span>
        {NODES.map((n, i) => (
          <div key={n.type} className={"ykm-node is-" + st[i] + (i < NODES.length - 1 && st[i + 1] !== "idle" ? " edge-on" : "")}>
            <span className="ykm-type">{n.type}</span>
            <span className="ykm-name">{n.name}</span>
            <span className="ykm-badge">{LABEL[st[i]]}</span>
          </div>
        ))}
      </div>

      <div className="ykm-bar">
        {phase === "asking" ? (
          <>
            <button type="button" className="ykm-btn ykm-primary" onClick={() => answer(true)}>Approve</button>
            <button type="button" className="ykm-btn" onClick={() => answer(false)}>Reject</button>
            <span className="ykm-hint">The run waits for you.</span>
          </>
        ) : (
          <button type="button" className="ykm-btn ykm-primary" onClick={run} disabled={phase === "running"}>
            {phase === "running" ? "Running…" : phase === "ready" ? "▶ Run it" : "↻ Run again"}
          </button>
        )}
        <a className="ykm-more" href="/learn/playground">Build your own in the playground →</a>
      </div>

      <ol className="ykm-log" aria-live="polite">
        {log.length === 0 ? <li className="ykm-empty">Press Run it to start the run.</li> : null}
        {log.map((l) => (
          <li key={l.n} className={"k-" + l.kind}>
            <b>{l.who}</b>
            <span>{l.text}</span>
          </li>
        ))}
      </ol>
    </div>
  );
};
