---
name: playwright
description: Write E2E tests for Yukta UI using Playwright and MSW mocking. Tests run against mocked APIs with no backend calls needed.
allowed-tools: Read, Write, Bash, Glob, Edit
context: fork
---

# Playwright E2E Testing Skill

Write comprehensive E2E tests for the Yukta UI application using Playwright and MSW mocking.

## Quick Reference

### Test Data Available
```typescript
// From fixtures
mockSessions: [
  { sessionId: 'session-1', name: 'Build Pipeline', ... },
  { sessionId: 'session-2', name: 'Code Review', ... },
  { sessionId: 'session-3', name: 'Data Pipeline', ... },
]
```

### Essential Utilities
```typescript
import { test, expect } from '../fixtures';
import {
  waitForApiResponse,
  fillForm,
  waitForLoadingComplete,
  getTableRow,
  countTableRows,
  assertTableColumns,
  clickAndWait,
  getTextContent,
} from '../utils';
```

### Common Commands
```bash
pnpm test:e2e                        # All tests
pnpm test:e2e tests/file.spec.ts     # Specific file
pnpm test:e2e --grep "pattern"       # Match pattern
pnpm test:e2e --headed               # See browser
pnpm test:e2e --debug                # Interactive debug
```

## Core Patterns

### List Page Test
```typescript
test('should display sessions', async ({ page, mockSessions }) => {
  await page.goto('/');
  await waitForLoadingComplete(page);
  
  const rowCount = await countTableRows(page);
  expect(rowCount).toBe(mockSessions.length);
});
```

### API Error Test
```typescript
test('should handle error', async ({ page }) => {
  // Register waiter BEFORE navigation
  const responsePromise = waitForApiResponse(page, { url: '/api/sessions/summaries' });
  
  // Then navigate
  await page.goto('/?error=true');
  
  // Await the response
  const response = await responsePromise;
  expect(response.status()).toBe(500);
});
```

### Form Test
```typescript
test('should submit form', async ({ page }) => {
  await fillForm(page, { 'Name': 'Session', 'Description': 'Test' });
  await clickAndWait(page, 'button[type="submit"]', {
    waitForResponse: '/api/sessions',
  });
});
```

### Navigation Test
```typescript
test('should navigate to details', async ({ page }) => {
  const row = await getTableRow(page, 'Build Pipeline');
  await clickAndWait(page, row.locator('a'), {
    waitForUrl: '/sessions/**',
  });
});
```

## Selector Priority

1. **data-testid** (most stable)
   ```typescript
   page.locator('[data-testid="submit"]')
   ```

2. **Accessible roles**
   ```typescript
   page.getByRole('button', { name: 'Submit' })
   ```

3. **Labels**
   ```typescript
   page.getByLabel('Email')
   ```

4. **Text** (last resort)
   ```typescript
   page.getByText('Sessions')
   ```

## Critical Rules

❌ **NEVER**
- Use `page.waitForTimeout()` — use semantic waits instead
- Hardcode test data — use `mockSessions` fixture
- Depend on test order — each test must be independent
- Use fragile selectors — prefer data-testid

✅ **ALWAYS**
- Use semantic waits: `waitForLoadingComplete()`, `waitForApiResponse()`
- Use fixture data: `mockSessions`
- Setup in `beforeEach()` — avoid repetition
- Test user-visible behavior, not implementation

## File Structure

```
e2e/
├── fixtures.ts                 # Test data & imports
├── utils.ts                    # 15+ helper functions
├── mocks/handlers.ts           # API mocks (extensible)
└── tests/
    ├── session-list.spec.ts    # Reference: 26 tests
    ├── error-handling.spec.ts  # Reference: 8 tests
    └── theme.spec.ts           # Reference: 6 tests
```

## Workflow

### Writing New Tests
1. Create file: `e2e/tests/feature-name.spec.ts`
2. Copy template from `e2e/tests/session-list.spec.ts`
3. Follow patterns from **Core Patterns** section above
4. Run: `pnpm test:e2e tests/feature-name.spec.ts --headed`
5. Debug with `--debug` if failing

### Adding API Endpoints
1. Open: `e2e/mocks/handlers.ts`
2. Add handler following existing pattern (timestamp, status, data)
3. Export in handlers array (automatic)
4. Use in tests: `await waitForApiResponse(page, { url: '/api/new' })`

### Application Routes
- `/` — Session list (main page)
- `/sessions/:id` — Session details
- `/sessions/:id/workflow/:wid` — Workflow view
- `/control` — Control bus
- `/history` — History

## Test Checklist

- [ ] Test name describes expected behavior
- [ ] Setup in `beforeEach()`, not repeated
- [ ] Uses `mockSessions` fixture, not hardcoded
- [ ] Uses semantic waits (no `waitForTimeout`)
- [ ] Uses stable selectors (data-testid preferred)
- [ ] Specific assertions (not generic checks)
- [ ] Test is independent (no dependencies)
- [ ] Error scenarios covered (if applicable)
- [ ] Runs in < 5 seconds
- [ ] Passes locally: `pnpm test:e2e --grep "name"`

## Screenshot Tests

Visual regression testing included in `e2e/tests/screenshots.spec.ts`:

```bash
# Run only screenshot tests
pnpm test:e2e screenshots.spec.ts

# Update baseline screenshots
UPDATE_SNAPSHOTS=true pnpm test:e2e screenshots.spec.ts

# View screenshot diffs
pnpm exec playwright show-report
```

**Covers:**
- Full page layout (desktop, tablet, mobile)
- Dark/light mode themes
- Component states (hover, error, empty)
- Responsive breakpoints
- UI component details

Stored in: `e2e/__snapshots__/`

## CI Integration

Tests run automatically when PR changes `ui/` module:
- Triggered by GitHub Actions on PR
- Fails if screenshots don't match baseline (use `UPDATE_SNAPSHOTS=true` to update)
- Results posted to PR comments
- Reports uploaded as artifacts

## Reference Files

- Guide: `ui/.claude/playwright-testing-guide.md` — Detailed patterns
- Examples: `e2e/tests/session-list.spec.ts` — 26 reference tests
- Screenshots: `e2e/tests/screenshots.spec.ts` — Visual regression tests
- Utilities: `e2e/utils.ts` — All helper functions with docs
- Handlers: `e2e/mocks/handlers.ts` — API mocks (extensible)
- Fixtures: `e2e/fixtures.ts` — Test data setup
- Config: `playwright.config.ts` — Framework settings

## Session Model

```typescript
interface SessionListItem {
  sessionId: string;
  name: string;
  description: string;
  initiator: string;
  tags: string[];
  projectPath: string;
  workflowCount: number;
}
```

## API Response Format

All mocked responses:
```typescript
{
  timestamp: string,       // ISO format
  status: number,          // 200, 201, 500, etc
  message: string,         // Human readable
  path: string,            // Request path
  data: T,                 // Actual response data ← use this
  error?: string,
  errors?: Record<string, string[]>,
}
```

## Debugging

```bash
# Interactive step-through
pnpm test:e2e --debug

# See browser + slow motion
pnpm test:e2e --headed --slow-mo=1000

# View HTML report
pnpm exec playwright show-report

# Specific test only
pnpm test:e2e --grep "exact test name"
```