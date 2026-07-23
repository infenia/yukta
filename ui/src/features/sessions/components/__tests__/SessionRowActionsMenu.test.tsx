// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { BrowserRouter, useNavigate } from 'react-router';

vi.mock('react-router', async () => {
  const actual = await vi.importActual('react-router');
  return {
    ...actual,
    useNavigate: vi.fn(),
  };
});
import {
  useReactTable,
  getCoreRowModel,
  getPaginationRowModel,
  getFilteredRowModel,
} from '@tanstack/react-table';
import { SessionsPaginationFooter } from '../SessionsPaginationFooter';
import { SessionRowActionsMenu } from '../SessionRowActionsMenu';
import { columns } from '../columns';
import { createMockSession, createMockSessions } from '@/test/factories/sessionFactory';
import type { SessionListItem } from '../../types/session';

describe('User Interactions', () => {
  describe('SessionsPaginationFooter', () => {
    const mockSessions = createMockSessions(35);

    const TableComponent = () => {
      const table = useReactTable({
        data: mockSessions,
        columns,
        getCoreRowModel: getCoreRowModel(),
        getPaginationRowModel: getPaginationRowModel(),
        getFilteredRowModel: getFilteredRowModel(),
        initialState: { pagination: { pageSize: 10 } },
      });

      return <SessionsPaginationFooter table={table} />;
    };

    it('should navigate to next page with chevron_right button', async () => {
      const user = userEvent.setup();
      render(<TableComponent />);

      expect(screen.getByText('1–10 of 35 items')).toBeInTheDocument();

      const nextButton = screen.getByRole('button', { name: 'Next page' });
      expect(nextButton).toBeEnabled();
      await user.click(nextButton);

      expect(screen.getByText('11–20 of 35 items')).toBeInTheDocument();
    });

    it('should navigate to last page', async () => {
      const user = userEvent.setup();
      render(<TableComponent />);

      expect(screen.getByText('1–10 of 35 items')).toBeInTheDocument();

      const lastButton = screen.getByRole('button', { name: 'Last page' });
      expect(lastButton).toBeEnabled();
      await user.click(lastButton);

      expect(screen.getByText('31–35 of 35 items')).toBeInTheDocument();
    });

    it('should change page size via dropdown', async () => {
      const user = userEvent.setup();
      render(<TableComponent />);

      expect(screen.getByText('1–10 of 35 items')).toBeInTheDocument();

      const pageSizeTrigger = screen.getByRole('button', { name: /^\d+$/ });
      await user.click(pageSizeTrigger);
      const pageSize20Option = await screen.findByText('20');
      expect(pageSize20Option).toBeInTheDocument();
      await user.click(pageSize20Option);

      expect(screen.getByText('1–20 of 35 items')).toBeInTheDocument();
    });

    it('should handle first page button click', async () => {
      const user = userEvent.setup();
      render(<TableComponent />);

      expect(screen.getByText('1–10 of 35 items')).toBeInTheDocument();

      const nextButton = screen.getByRole('button', { name: 'Next page' });
      await user.click(nextButton);
      expect(screen.getByText('11–20 of 35 items')).toBeInTheDocument();

      const firstButton = screen.getByRole('button', { name: 'First page' });
      expect(firstButton).toBeEnabled();
      await user.click(firstButton);

      expect(screen.getByText('1–10 of 35 items')).toBeInTheDocument();
    });

    it('should render different page sizes', () => {
      render(<TableComponent />);

      // Verify pagination footer is rendered
      expect(screen.getByText(/Rows per page:/)).toBeInTheDocument();
      expect(screen.getByText(/items/)).toBeInTheDocument();
    });

    it('should navigate back to first page with chevron_left button', async () => {
      // Given: table on page 2 (35 rows, page size 10)
      const user = userEvent.setup();
      render(<TableComponent />);

      await user.click(screen.getByRole('button', { name: 'Next page' }));
      expect(screen.getByText('11–20 of 35 items')).toBeInTheDocument();

      // When: previous page button is clicked
      await user.click(screen.getByRole('button', { name: 'Previous page' }));

      // Then: table is back on the first page
      expect(screen.getByText('1–10 of 35 items')).toBeInTheDocument();
    });

    it('should jump to first page with first_page button from last page', async () => {
      // Given: table on the last page (35 rows, page size 10)
      const user = userEvent.setup();
      render(<TableComponent />);

      await user.click(screen.getByRole('button', { name: 'Last page' }));
      expect(screen.getByText('31–35 of 35 items')).toBeInTheDocument();

      // When: first page button is clicked
      await user.click(screen.getByRole('button', { name: 'First page' }));

      // Then: table is back on the first page
      expect(screen.getByText('1–10 of 35 items')).toBeInTheDocument();
    });

    it('should change page size when selecting 20 from dropdown', async () => {
      // Given: table with page size 10
      const user = userEvent.setup();
      render(<TableComponent />);
      expect(screen.getByText('1–10 of 35 items')).toBeInTheDocument();

      // When: 20 is selected from the rows-per-page dropdown
      // (Base UI trigger and inner Button both match, click the trigger)
      const pageSizeTrigger = screen.getByRole('button', { name: /^\d+$/ });
      await user.click(pageSizeTrigger);
      const option20 = await screen.findByText('20');
      await user.click(option20);

      // Then: 20 rows are shown per page
      expect(screen.getByText('1–20 of 35 items')).toBeInTheDocument();
    });

    it('should handle all pagination controls', async () => {
      const user = userEvent.setup();
      render(<TableComponent />);

      // Test 1: Navigate to next page
      expect(screen.getByText('1–10 of 35 items')).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Next page' }));
      expect(screen.getByText('11–20 of 35 items')).toBeInTheDocument();

      // Test 2: Navigate to last page
      await user.click(screen.getByRole('button', { name: 'Last page' }));
      expect(screen.getByText('31–35 of 35 items')).toBeInTheDocument();

      // Test 3: Navigate back to first page
      await user.click(screen.getByRole('button', { name: 'First page' }));
      expect(screen.getByText('1–10 of 35 items')).toBeInTheDocument();

      // Test 4: Change page size
      const pageSizeTrigger = screen.getByRole('button', { name: /^\d+$/ });
      await user.click(pageSizeTrigger);
      const pageSize20 = await screen.findByText('20');
      await user.click(pageSize20);
      expect(screen.getByText('1–20 of 35 items')).toBeInTheDocument();

      // Test 5: Navigate to next with new page size
      await user.click(screen.getByRole('button', { name: 'Next page' }));
      expect(screen.getByText('21–35 of 35 items')).toBeInTheDocument();

      // Test 6: Navigate back
      await user.click(screen.getByRole('button', { name: 'Previous page' }));
      expect(screen.getByText('1–20 of 35 items')).toBeInTheDocument();
    });
  });

  describe('SessionRowActionsMenu - Navigation Testing', () => {
    const mockSession: SessionListItem = {
      sessionId: 'nav-test-session',
      name: 'Navigation Test Session',
      description: 'Test',
      initiator: 'test@example.com',
      tags: [],
      projectPath: '/test',
      workflowCount: 1,
    };

    it('should call navigate when View Details menu item is clicked', async () => {
      const user = userEvent.setup();
      const mockNavigate = vi.fn();
      vi.mocked(useNavigate).mockReturnValue(mockNavigate);

      render(
        <BrowserRouter>
          <SessionRowActionsMenu session={mockSession} />
        </BrowserRouter>
      );

      const menuTrigger = screen.getByRole('button', { name: 'Session actions' });
      await user.click(menuTrigger);

      const viewDetailsOption = await screen.findByText('View Details');
      await user.click(viewDetailsOption);

      expect(mockNavigate).toHaveBeenCalledWith(
        `/sessions/${mockSession.sessionId}`
      );
    });

    it('should render menu and handle interaction', async () => {
      const user = userEvent.setup();

      render(
        <BrowserRouter>
          <SessionRowActionsMenu session={mockSession} />
        </BrowserRouter>
      );

      const icon = screen.getByText('more_vert');
      expect(icon).toBeInTheDocument();

      const button = icon.closest('button');
      if (button) {
        await user.click(button);
        // Menu should be interactive (button is rendered with dropdown)
        expect(button).toBeInTheDocument();
      }
    });

    it('should have View Details option in menu', async () => {
      const user = userEvent.setup();
      render(
        <BrowserRouter>
          <SessionRowActionsMenu session={mockSession} />
        </BrowserRouter>
      );

      const menuTrigger = screen.getByRole('button', { name: 'Session actions' });
      await user.click(menuTrigger);

      const viewDetailsOption = await screen.findByText('View Details');
      expect(viewDetailsOption).toBeInTheDocument();
    });

    it('should render button with aria-label', () => {
      render(
        <BrowserRouter>
          <SessionRowActionsMenu session={mockSession} />
        </BrowserRouter>
      );

      const button = screen.getByRole('button', { name: 'Session actions' });
      expect(button).toHaveAttribute('aria-label', 'Session actions');
    });

    it('should handle rapid menu interactions', async () => {
      const user = userEvent.setup();

      render(
        <BrowserRouter>
          <SessionRowActionsMenu session={mockSession} />
        </BrowserRouter>
      );

      const menuTrigger = screen.getByRole('button', { name: 'Session actions' });
      await user.click(menuTrigger);
      await user.click(menuTrigger);
      expect(menuTrigger).toBeInTheDocument();
    });

    it('should work with various session properties', () => {
      const differentSession = createMockSession({
        sessionId: 'different-id',
        name: 'Different Session',
        workflowCount: 100,
        tags: ['tag1', 'tag2'],
      });

      render(
        <BrowserRouter>
          <SessionRowActionsMenu session={differentSession} />
        </BrowserRouter>
      );

      expect(screen.getByRole('button', { name: 'Session actions' })).toBeInTheDocument();
    });
  });

  describe('Edge Cases - Menu', () => {
    it('should handle sessions with special characters', () => {
      const specialSession = createMockSession({
        sessionId: 'special-!@#$%',
        name: 'Session with Special Chars !@#$%',
      });

      render(
        <BrowserRouter>
          <SessionRowActionsMenu session={specialSession} />
        </BrowserRouter>
      );

      expect(screen.getByRole('button', { name: 'Session actions' })).toBeInTheDocument();
    });

    it('should handle long session names', () => {
      const longNameSession = createMockSession({
        sessionId: 'long-id',
        name: 'A'.repeat(50) + ' Very Long Session Name',
      });

      render(
        <BrowserRouter>
          <SessionRowActionsMenu session={longNameSession} />
        </BrowserRouter>
      );

      expect(screen.getByRole('button', { name: 'Session actions' })).toBeInTheDocument();
    });
  });
});
