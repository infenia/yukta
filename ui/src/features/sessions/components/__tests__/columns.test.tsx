// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { columns } from '../columns';

describe('columns', () => {
  it('should export columns array', () => {
    expect(columns).toBeDefined();
    expect(Array.isArray(columns)).toBe(true);
  });

  it('should have correct number of columns', () => {
    expect(columns).toHaveLength(8);
  });

  it('should have name column', () => {
    const nameColumn = columns.find((col) => (col as any).accessorKey === 'name');
    expect(nameColumn).toBeDefined();
    expect((nameColumn as any).header).toBe('Name');
  });

  it('should have sessionId column', () => {
    const idColumn = columns.find((col) => (col as any).accessorKey === 'sessionId');
    expect(idColumn).toBeDefined();
    expect((idColumn as any).header).toBe('Session ID');
  });

  it('should have description column', () => {
    const descColumn = columns.find((col) => (col as any).accessorKey === 'description');
    expect(descColumn).toBeDefined();
    expect((descColumn as any).header).toBe('Description');
  });

  it('should have initiator column', () => {
    const initiatorColumn = columns.find((col) => (col as any).accessorKey === 'initiator');
    expect(initiatorColumn).toBeDefined();
    expect((initiatorColumn as any).header).toBe('Initiator');
  });

  it('should have tags column', () => {
    const tagsColumn = columns.find((col) => (col as any).accessorKey === 'tags');
    expect(tagsColumn).toBeDefined();
    expect((tagsColumn as any).header).toBe('Tags');
  });

  it('should have projectPath column', () => {
    const pathColumn = columns.find((col) => (col as any).accessorKey === 'projectPath');
    expect(pathColumn).toBeDefined();
    expect((pathColumn as any).header).toBe('Project Path');
  });

  it('should have workflowCount column', () => {
    const countColumn = columns.find((col) => (col as any).accessorKey === 'workflowCount');
    expect(countColumn).toBeDefined();
    expect((countColumn as any).header).toBe('Workflows');
  });

  it('should have actions column', () => {
    const actionsColumn = columns.find((col) => (col as any).id === 'actions');
    expect(actionsColumn).toBeDefined();
    expect((actionsColumn as any).id).toBe('actions');
  });

  it('should have cell renderers for custom columns', () => {
    const nameColumn = columns.find((col) => (col as any).accessorKey === 'name');
    const tagsColumn = columns.find((col) => (col as any).accessorKey === 'tags');
    const actionsColumn = columns.find((col) => (col as any).id === 'actions');

    expect(nameColumn?.cell).toBeDefined();
    expect(tagsColumn?.cell).toBeDefined();
    expect(actionsColumn?.cell).toBeDefined();
  });

  it('should include all required session fields as columns', () => {
    const accessorKeys = columns
      .map((col: any) => col.accessorKey)
      .filter(Boolean);

    expect(accessorKeys).toContain('name');
    expect(accessorKeys).toContain('sessionId');
    expect(accessorKeys).toContain('description');
    expect(accessorKeys).toContain('initiator');
    expect(accessorKeys).toContain('tags');
    expect(accessorKeys).toContain('projectPath');
    expect(accessorKeys).toContain('workflowCount');
  });
});
