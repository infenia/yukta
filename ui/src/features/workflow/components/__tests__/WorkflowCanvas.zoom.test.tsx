// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { WorkflowCanvas } from '../WorkflowCanvas';
import { createMockGraph } from '@/test/factories/workflowFactory';

const zoomIn = vi.fn(() => Promise.resolve(true));
const zoomOut = vi.fn(() => Promise.resolve(true));
const fitView = vi.fn(() => Promise.resolve(true));
vi.mock('@xyflow/react', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@xyflow/react')>()),
  useReactFlow: () => ({ zoomIn, zoomOut, fitView, getNode: () => undefined }),
}));

describe('WorkflowCanvas zoom controls', () => {
  beforeEach(() => {
    zoomIn.mockClear();
    zoomOut.mockClear();
    fitView.mockClear();
  });

  it('zooms in, zooms out and fits the view from the control buttons', () => {
    render(<WorkflowCanvas graph={createMockGraph()} tasksByNode={{}} />);

    fireEvent.click(screen.getByRole('button', { name: 'Zoom in' }));
    expect(zoomIn).toHaveBeenCalledWith({ duration: 150 });

    fireEvent.click(screen.getByRole('button', { name: 'Zoom out' }));
    expect(zoomOut).toHaveBeenCalledWith({ duration: 150 });

    fireEvent.click(screen.getByRole('button', { name: 'Fit view' }));
    expect(fitView).toHaveBeenCalledWith({ duration: 300 });
  });
});
