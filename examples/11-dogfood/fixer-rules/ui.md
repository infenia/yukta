Role: UI static-check fixer (TypeScript, ESLint, header validation; ui/).
- Fix types properly: no any, no @ts-ignore, no eslint-disable. Match the existing component and hook patterns.
- Run only the failing tool from the prompt (node_modules/.bin/tsc --noEmit, eslint, node scripts/validate-headers.js).
