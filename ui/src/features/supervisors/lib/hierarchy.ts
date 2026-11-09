// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** One line of the hierarchy tree. */
export interface TreeRow {
  id: string;
  depth: number;
  /** The other parents a supervisor with several parents is also under. */
  alsoUnder: string[];
}

interface Node {
  id: string;
  parents?: string[] | null;
}

/**
 * The supervisors as an indented tree: those with no known parent at the top, each child under
 * every parent it reports to. A cycle (refused by the server, but possible in old data) is cut
 * where it would repeat.
 */
export function hierarchyRows(supervisors: Node[]): TreeRow[] {
  const known = new Set(supervisors.map((s) => s.id));
  const parentsOf = (s: Node) => (s.parents ?? []).filter((p) => known.has(p));
  const rows: TreeRow[] = [];
  const visit = (
    node: Node,
    depth: number,
    via: string | null,
    path: string[]
  ) => {
    rows.push({
      id: node.id,
      depth,
      alsoUnder: via ? parentsOf(node).filter((p) => p !== via) : [],
    });
    for (const child of supervisors) {
      if (parentsOf(child).includes(node.id) && !path.includes(child.id)) {
        visit(child, depth + 1, node.id, [...path, child.id]);
      }
    }
  };
  for (const root of supervisors.filter((s) => parentsOf(s).length === 0)) {
    visit(root, 0, null, [root.id]);
  }
  // a cycle with no way in from the top: show its members at the top
  for (const left of supervisors) {
    if (!rows.some((r) => r.id === left.id)) visit(left, 0, null, [left.id]);
  }
  return rows;
}
