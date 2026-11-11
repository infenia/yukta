// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/*
 * Reader-state block shown at the top of every tutorial, guide and concept page: answers
 * "is this for me, how long, what do I need, what do I get". Everything lives inside the
 * component (Mintlify snippets expose only the exported component).
 * Props: learn, time, need, end (strings). Any may be omitted.
 */
export const ReaderState = ({ learn, time, need, end }) => {
  const rows = [
    ["You'll learn", learn],
    ["Time", time],
    ["You'll need", need],
    ["You'll end with", end],
  ].filter((r) => r[1]);
  return (
    <div className="not-prose my-6 rounded-xl border border-zinc-950/10 dark:border-white/10 bg-zinc-950/[0.03] dark:bg-white/[0.04] px-4 py-3 text-sm">
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 m-0">
        {rows.map(([k, v]) => (
          <React.Fragment key={k}>
            <dt className="font-semibold text-zinc-950 dark:text-white">{k}</dt>
            <dd className="m-0 text-zinc-700 dark:text-zinc-300">{v}</dd>
          </React.Fragment>
        ))}
      </dl>
    </div>
  );
};
