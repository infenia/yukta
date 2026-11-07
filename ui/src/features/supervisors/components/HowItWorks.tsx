// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';

const KEY = 'yukta.supervisors.how-it-works.hidden';

const read = () => {
  try {
    return localStorage.getItem(KEY) === '1';
  } catch {
    return false;
  }
};

const FLOW = [
  ['search', 'Watches', 'the events of your runs'],
  ['rule', 'Decides', 'by rules, a model or a workflow'],
  ['send', 'Acts', 'pause, stop, skip, answer or escalate'],
  ['person', 'Asks you', 'when a risky step needs a person'],
] as const;

/** A short, dismissible explanation of what supervision is. */
export function HowItWorks() {
  const [hidden, setHidden] = useState(read);
  const hide = (value: boolean) => {
    setHidden(value);
    try {
      localStorage.setItem(KEY, value ? '1' : '0');
    } catch {
      // the choice just isn't remembered
    }
  };
  if (hidden) {
    return (
      <button
        type="button"
        onClick={() => hide(false)}
        className="text-label-md text-primary self-start"
      >
        How supervision works
      </button>
    );
  }
  return (
    <section
      aria-label="How supervision works"
      className="border-border bg-card flex flex-col gap-3 rounded-xl border p-4"
    >
      <div className="flex items-start justify-between gap-3">
        <p className="text-body-md text-on-surface max-w-prose">
          A <strong>supervisor</strong> is an automatic operator for your
          workflows. You attach it to a <strong>scope</strong>, and from then on
          it watches that scope and steps in. Workflows run the same without
          one.
        </p>
        <button
          type="button"
          aria-label="Hide this explanation"
          onClick={() => hide(true)}
          className="text-on-surface-variant hover:text-on-surface"
        >
          <span aria-hidden="true" className="material-symbols-outlined">
            close
          </span>
        </button>
      </div>
      <ol className="grid gap-2 sm:grid-cols-4">
        {FLOW.map(([icon, title, text]) => (
          <li
            key={title}
            className="bg-muted/50 flex items-start gap-2 rounded-lg p-3"
          >
            <span
              aria-hidden="true"
              className="material-symbols-outlined text-primary"
            >
              {icon}
            </span>
            <span className="text-body-sm">
              <span className="text-label-md text-on-surface block">
                {title}
              </span>
              {text}
            </span>
          </li>
        ))}
      </ol>
      <p className="text-body-sm text-on-surface-variant">
        <strong>Scope</strong> nests: Everything &gt; a session &gt; a workflow
        &gt; some of its nodes. A supervisor only acts inside its own scope, and
        a wider one also watches everything inside it.
      </p>
    </section>
  );
}
