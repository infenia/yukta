// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';

vi.mock('@/features/overview/OverviewPage', () => ({
  OverviewPage: () => 'the overview',
}));
import router, { AppRouter } from '../router';

describe('router', () => {
  it('should be a valid router instance', () => {
    expect(router).toBeDefined();
  });

  it('should have a routes array', () => {
    expect(router.routes).toBeDefined();
    expect(Array.isArray(router.routes)).toBe(true);
  });

  it('should have root path configuration', () => {
    const rootRoute = router.routes[0];
    expect(rootRoute).toBeDefined();
    expect(rootRoute.path).toBe('/');
    expect(rootRoute.element).toBeDefined();
  });

  it('should have child routes under root', () => {
    const rootRoute = router.routes[0];
    expect(rootRoute.children).toBeDefined();
    expect(Array.isArray(rootRoute.children)).toBe(true);
    expect(rootRoute.children?.length).toBeGreaterThan(0);
  });

  describe('route configuration', () => {
    it('should have index route for home', () => {
      const rootRoute = router.routes[0];
      const indexRoute = rootRoute.children?.find((r) => r.index === true);
      expect(indexRoute).toBeDefined();
      expect(indexRoute?.element).toBeDefined();
    });

    it('should have session detail route', () => {
      const rootRoute = router.routes[0];
      const sessionRoute = rootRoute.children?.find(
        (r) => r.path === 'sessions/:sessionId'
      );
      expect(sessionRoute).toBeDefined();
      expect(sessionRoute?.lazy).toBeDefined();
    });

    it('should have workflow route', () => {
      const rootRoute = router.routes[0];
      const workflowRoute = rootRoute.children?.find(
        (r) => r.path === 'sessions/:sessionId/workflow/:workflowId'
      );
      expect(workflowRoute).toBeDefined();
      expect(workflowRoute?.lazy).toBeDefined();
    });

    it('should have control bus route', () => {
      const rootRoute = router.routes[0];
      const controlRoute = rootRoute.children?.find(
        (r) => r.path === 'control'
      );
      expect(controlRoute).toBeDefined();
      expect(controlRoute?.lazy).toBeDefined();
    });

    it('should have runs route, and history redirects to it', () => {
      const rootRoute = router.routes[0];
      const runsRoute = rootRoute.children?.find((r) => r.path === 'runs');
      expect(runsRoute?.lazy).toBeDefined();
      const historyRoute = rootRoute.children?.find(
        (r) => r.path === 'history'
      );
      expect(historyRoute?.element).toBeDefined();
    });
  });

  describe('route paths', () => {
    it('should have 12 child routes', () => {
      const rootRoute = router.routes[0];
      expect(rootRoute.children?.length).toBe(12);
    });

    it('should have correct number of parameterized routes', () => {
      const rootRoute = router.routes[0];
      const parameterizedRoutes = rootRoute.children?.filter((r) =>
        r.path?.includes(':')
      );
      expect(parameterizedRoutes?.length).toBe(4);
    });

    it('should have correct session ID parameter', () => {
      const rootRoute = router.routes[0];
      const sessionRoute = rootRoute.children?.find(
        (r) => r.path === 'sessions/:sessionId'
      );
      expect(sessionRoute?.path).toContain(':sessionId');
    });

    it('should have correct workflow ID parameter', () => {
      const rootRoute = router.routes[0];
      const workflowRoute = rootRoute.children?.find(
        (r) => r.path === 'sessions/:sessionId/workflow/:workflowId'
      );
      expect(workflowRoute?.path).toContain(':sessionId');
      expect(workflowRoute?.path).toContain(':workflowId');
    });
  });

  describe('route elements', () => {
    it('should have JSX element for index route', () => {
      const rootRoute = router.routes[0];
      const indexRoute = rootRoute.children?.find((r) => r.index === true);
      expect(indexRoute?.element).not.toBeNull();
    });

    it('should redirect /sessions to the list at the root', () => {
      const rootRoute = router.routes[0];
      const route = rootRoute.children?.find((r) => r.path === 'sessions');
      expect(route?.element).toBeDefined();
    });

    it('should lazy-load the detail pages', async () => {
      const rootRoute = router.routes[0];
      const load = async (path: string) => {
        const route = rootRoute.children?.find((r) => r.path === path);
        const lazyRoute = await (
          route?.lazy as () => Promise<{ Component: { name: string } }>
        )();
        return lazyRoute.Component.name;
      };
      expect(await load('sessions/:sessionId/workflow/:workflowId')).toBe(
        'WorkflowDetailsPage'
      );
      expect(await load('sessions/:sessionId')).toBe('SessionDetailsPage');
      expect(await load('sessions/:sessionId/workflow/:workflowId/edit')).toBe(
        'WorkflowEditorPage'
      );
      expect(await load('sessions/new')).toBe('SessionFormPage');
      expect(await load('sessions/import')).toBe('SessionImportPage');
      expect(await load('sessions/:sessionId/edit')).toBe('SessionFormPage');
      expect(await load('plugins')).toBe('PluginsPage');
      expect(await load('control')).toBe('ControlPage');
      expect(await load('runs')).toBe('RunsPage');
    });
  });

  describe('router structure', () => {
    it('should export router as default', () => {
      expect(router).toBeDefined();
    });

    it('should have exactly one root route', () => {
      expect(router.routes.length).toBe(1);
    });

    it('should have root route with path /', () => {
      expect(router.routes[0].path).toBe('/');
    });

    it('should have all child routes under root', () => {
      const rootRoute = router.routes[0];
      const childPaths = rootRoute.children?.map((r) => r.path || 'index');
      expect(childPaths).toContain('sessions/:sessionId');
      expect(childPaths).toContain('control');
      expect(childPaths).toContain('history');
    });
  });
});

describe('home route', () => {
  it('shows the Overview, and sends old session-list queries to /sessions', async () => {
    const { HomeRoute } = await import('../router');
    const { MemoryRouter, Routes, Route } = await import('react-router');
    const { render, screen } = await import('@testing-library/react');
    const { createElement } = await import('react');
    const where = () => createElement('p', null, 'sessions list');
    const at = (url: string) =>
      render(
        createElement(
          MemoryRouter,
          { initialEntries: [url] },
          createElement(
            Routes,
            null,
            createElement(Route, { path: '/sessions', element: where() }),
            createElement(Route, {
              path: '/',
              element: createElement(HomeRoute),
            })
          )
        )
      );
    const first = at('/?q=abc');
    expect(screen.getByText('sessions list')).toBeInTheDocument();
    first.unmount();
    at('/');
    expect(screen.getByText('the overview')).toBeInTheDocument();
  });
});

describe('AppRouter', () => {
  it('renders the app at the current URL', async () => {
    const { render, screen } = await import('@testing-library/react');
    const { createElement } = await import('react');
    const { QueryClientProvider } = await import('@tanstack/react-query');
    const { createTestQueryClient } = await import('@/test/utils/testUtils');
    render(
      createElement(
        QueryClientProvider,
        { client: createTestQueryClient() },
        createElement(AppRouter)
      )
    );
    expect(await screen.findByText('the overview')).toBeInTheDocument();
  });
});
