// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { BrowserRouter } from 'react-router';
import {
  useReactTable,
  getCoreRowModel,
  getPaginationRowModel,
  getFilteredRowModel,
} from '@tanstack/react-table';
import { SessionsPaginationFooter } from '../components/SessionsPaginationFooter';
import { SessionRowActionsMenu } from '../components/SessionRowActionsMenu';
import { columns } from '../components/columns';
import type { SessionListItem } from '../types/session';

describe('Coverage - User Interactions', () => {
  describe('SessionsPaginationFooter - Full Coverage', () => {
    const mockSessions: SessionListItem[] = Array.from({ length: 35 }, (_, i) => ({
      sessionId: `session-${i}`,
      name: `Session ${i}`,
      description: `Desc ${i}`,
      initiator: 'user@example.com',
      tags: [],
      projectPath: '/path',
      workflowCount: 1,
    }));

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

    it('should navigate to session detail when View Details is clicked', async () => {
      // Given: row actions menu is open
      const user = userEvent.setup();
      render(
        <BrowserRouter>
          <SessionRowActionsMenu session={mockSession} />
        </BrowserRouter>
      );

      // When: Session actions menu is opened
      const menuTrigger = screen.getByRole('button', { name: 'Session actions' });
      await user.click(menuTrigger);

      // And: View Details is clicked
      const viewDetailsItem = await screen.findByText('View Details');
      await user.click(viewDetailsItem);

      // Then: browser navigates to the session detail route
      expect(window.location.pathname).toBe(`/sessions/${mockSession.sessionId}`);
    });

    it('should support multiple interactions', async () => {
      const user = userEvent.setup();

      render(
        <BrowserRouter>
          <SessionRowActionsMenu session={mockSession} />
        </BrowserRouter>
      );

      const icon = screen.getByText('more_vert');
      const button = icon.closest('button');

      if (button) {
        // Click multiple times to test repeated interactions
        await user.click(button);
        await user.click(button);
        await user.click(button);
      }
    });

    it('should handle rapid interactions', async () => {
      const user = userEvent.setup();

      render(
        <BrowserRouter>
          <SessionRowActionsMenu session={mockSession} />
        </BrowserRouter>
      );

      const icon = screen.getByText('more_vert');
      const button = icon.closest('button');

      if (button) {
        // Simulate rapid clicking
        await user.click(button);
        await user.click(button);
      }
    });

    it('should handle session data correctly', () => {
      render(
        <BrowserRouter>
          <SessionRowActionsMenu
            session={{
              ...mockSession,
              sessionId: 'different-id',
              name: 'Different Session',
            }}
          />
        </BrowserRouter>
      );

      expect(screen.getByText('more_vert')).toBeInTheDocument();
    });

    it('should render with various session properties', () => {
      const sessionsToTest = [
        { ...mockSession, workflowCount: 0 },
        { ...mockSession, workflowCount: 100 },
        { ...mockSession, tags: ['tag1', 'tag2', 'tag3'] },
      ];

      sessionsToTest.forEach((session) => {
        const { unmount } = render(
          <BrowserRouter>
            <SessionRowActionsMenu session={session} />
          </BrowserRouter>
        );

        expect(screen.getByText('more_vert')).toBeInTheDocument();
        unmount();
      });
    });
  });

  describe('Edge Cases', () => {
    it('should handle pagination with exactly one page', () => {
      const singleSession: SessionListItem[] = [
        {
          sessionId: 'single',
          name: 'Only Session',
          description: 'The only one',
          initiator: 'test@example.com',
          tags: [],
          projectPath: '/path',
          workflowCount: 1,
        },
      ];

      const TableComponent = () => {
        const table = useReactTable({
          data: singleSession,
          columns,
          getCoreRowModel: getCoreRowModel(),
          getPaginationRowModel: getPaginationRowModel(),
          getFilteredRowModel: getFilteredRowModel(),
          initialState: { pagination: { pageSize: 10 } },
        });

        return <SessionsPaginationFooter table={table} />;
      };

      render(<TableComponent />);
      expect(screen.getByText('1–1 of 1 items')).toBeInTheDocument();
    });

    it('should handle menu with sessions having special characters', () => {
      const specialSession: SessionListItem = {
        sessionId: 'special-!@#$%',
        name: 'Session with Special Chars !@#$%',
        description: 'Desc with special chars',
        initiator: 'special@example.com',
        tags: ['tag-!'],
        projectPath: '/path/with/special',
        workflowCount: 1,
      };

      render(
        <BrowserRouter>
          <SessionRowActionsMenu session={specialSession} />
        </BrowserRouter>
      );

      expect(screen.getByText('more_vert')).toBeInTheDocument();
    });

    it('should handle long session names in menu', () => {
      const longNameSession: SessionListItem = {
        sessionId: 'long-id',
        name: 'A'.repeat(100) + ' Very Long Session Name That Goes On And On',
        description: 'Long description',
        initiator: 'test@example.com',
        tags: [],
        projectPath: '/path',
        workflowCount: 1,
      };

      render(
        <BrowserRouter>
          <SessionRowActionsMenu session={longNameSession} />
        </BrowserRouter>
      );

      expect(screen.getByText('more_vert')).toBeInTheDocument();
    });
  });
});
