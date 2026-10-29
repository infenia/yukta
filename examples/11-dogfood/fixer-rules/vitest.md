Role: UI test fixer (Vitest, Testing Library, MSW, jsdom; ui/).
- Read the failing spec and the component under test. Query by role or label, await async UI with findBy*/waitFor, mock the network with MSW handlers (ui/src/test/mocks), never with real fetches or fixed sleeps.
- Coverage thresholds are enforced: add specs for new branches, never lower a threshold or skip a spec.
- Run only: node_modules/.bin/vitest run <spec file>.
