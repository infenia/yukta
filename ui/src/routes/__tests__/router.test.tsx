// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import router from '../router';

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
      expect(sessionRoute?.element).toBeDefined();
    });

    it('should have workflow route', () => {
      const rootRoute = router.routes[0];
      const workflowRoute = rootRoute.children?.find(
        (r) => r.path === 'sessions/:sessionId/workflow/:workflowId'
      );
      expect(workflowRoute).toBeDefined();
      expect(workflowRoute?.element).toBeDefined();
    });

    it('should have control bus route', () => {
      const rootRoute = router.routes[0];
      const controlRoute = rootRoute.children?.find((r) => r.path === 'control');
      expect(controlRoute).toBeDefined();
      expect(controlRoute?.element).toBeDefined();
    });

    it('should have history route', () => {
      const rootRoute = router.routes[0];
      const historyRoute = rootRoute.children?.find((r) => r.path === 'history');
      expect(historyRoute).toBeDefined();
      expect(historyRoute?.element).toBeDefined();
    });
  });

  describe('route paths', () => {
    it('should have 5 child routes', () => {
      const rootRoute = router.routes[0];
      expect(rootRoute.children?.length).toBe(5);
    });

    it('should have correct number of parameterized routes', () => {
      const rootRoute = router.routes[0];
      const parameterizedRoutes = rootRoute.children?.filter((r) =>
        r.path?.includes(':')
      );
      expect(parameterizedRoutes?.length).toBe(2);
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

    it('should have Coming Soon pages for unimplemented routes', () => {
      const rootRoute = router.routes[0];
      const comingSoonRoutes = ['control', 'history'];

      comingSoonRoutes.forEach((path) => {
        const route = rootRoute.children?.find((r) => r.path === path);
        expect(route?.element).toBeDefined();
      });
    });

    it('should render the workflow details page for the workflow route', () => {
      const rootRoute = router.routes[0];
      const workflowRoute = rootRoute.children?.find(
        (r) => r.path === 'sessions/:sessionId/workflow/:workflowId'
      );
      expect(workflowRoute?.element).toBeDefined();
      const element = workflowRoute?.element as { type: { name?: string } };
      expect(element.type.name).toBe('WorkflowDetailsPage');
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
