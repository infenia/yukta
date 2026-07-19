# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Layout Components

This module contains the main layout components for the Yukta application, including a fixed header with logo, dynamic breadcrumb navigation, and theme toggle.

## AppHeader

Fixed header component with logo, breadcrumb navigation, and theme toggle.

### Overview
The AppHeader is a fixed-position component that spans the full width of the viewport and serves as the primary navigation and identity area for the Yukta application. It uses a two-row layout:
- **Top row (h-12)**: Logo on the left, theme toggle on the right
- **Bottom row (h-8)**: Dynamic breadcrumb navigation

### Props
None — this is a self-contained component.

### Usage
```typescript
import AppHeader from '@/components/layout/AppHeader';

export default function App() {
  return (
    <div className="min-h-screen flex flex-col">
      <AppHeader />
      <main className="flex-1 mt-20">
        {/* Your routes and content */}
      </main>
    </div>
  );
}
```

### Features
- **Fixed positioning**: Stays at the top while scrolling (z-50)
- **Design system integration**: Uses Tailwind `@theme` tokens for surface colors and borders
- **Dark mode support**: Automatically adapts to light and dark themes via Tailwind's class-based dark mode
- **Responsive**: Sharp corners, no shadows, and clean Material Design 3 aesthetic
- **Accessibility**: Semantic HTML structure with proper ARIA labels

### Integration Notes
- The main content area should have `mt-20` to account for the fixed header (h-12 + h-8 + border)
- The AppHeader internally composes HeaderLogo, BreadcrumbNav, and ThemeToggle
- All child components share the same design system tokens

---

## HeaderLogo

Logo button linking to the home page with icon and text.

### Overview
HeaderLogo is a button component that serves as the primary branding element in the header. It displays a clock icon (placeholder) and "Yukta" text, and clicking it navigates to the home page (`/`).

### Props
None — this is a standalone component.

### Usage
```typescript
import HeaderLogo from '@/components/layout/HeaderLogo';

<HeaderLogo />
```

### Features
- **Icon + text**: Combines a Material Symbols icon with the "Yukta" label
- **Clickable link**: Uses React Router's `<Link>` to navigate to `/` without full page reload
- **Ghost variant**: Uses shadcn/ui Button's ghost variant for minimal styling
- **Design system**: Uses Tailwind `@theme` tokens for text color (`on-surface`) and hover state (`surface-container-low`)
- **Typography**: IBM Plex Sans font with `font-headline` and `font-semibold` for emphasis
- **Icon placeholder**: Currently displays a simple SVG clock icon; replace with actual Yukta logo later

### Props Details
- No props — this component is stateless and self-contained

### Customization
To replace the placeholder icon with the actual Yukta logo:
1. Update the SVG in the component or import an icon component
2. Maintain the same dimensions (h-6 w-6) for consistency
3. Ensure the icon uses `currentColor` for proper dark mode support

---

## BreadcrumbNav

Dynamic breadcrumb navigation based on the current route.

### Overview
BreadcrumbNav renders a breadcrumb trail that automatically updates as the user navigates between routes. It parses the current pathname and generates a hierarchy of navigation links, with the current page shown as bold and non-clickable.

### Props
None — this component derives its state from React Router's `useLocation()` hook.

### Usage
```typescript
import BreadcrumbNav from '@/components/layout/BreadcrumbNav';

<BreadcrumbNav />
```

### Features
- **Automatic path parsing**: Extracts route segments and converts them to breadcrumb items
- **Smart labeling**: Maps known routes (e.g., "sessions") to friendly labels; capitalizes unknown segments
- **ID folding**: Numeric segments (IDs like 123, 456) are treated as detail parameters and folded into their parent breadcrumb
- **Current page styling**: Bold, non-clickable styling for the current page
- **Navigation links**: All parent breadcrumbs are clickable, allowing users to navigate backward
- **Material Symbols icons**: Uses `chevron_right` separator between breadcrumb items
- **Responsive styling**: Uses design system spacing and typography tokens
- **Accessibility**: Proper semantic HTML with ARIA labels for screen readers

### Route Parsing Examples

| Route | Breadcrumbs |
|-------|-------------|
| `/` | Sessions (current) |
| `/sessions` | Sessions (current) |
| `/sessions/123` | Sessions > Session 123 (current) |
| `/sessions/123/workflows` | Sessions > Workflows (current) |
| `/sessions/123/workflows/456` | Sessions > Workflows > Workflow 456 (current) |

### Customizing Route Labels

The route-to-label mapping is defined in `src/lib/breadcrumb-utils.ts`:

```typescript
const ROUTE_LABEL_MAP: RouteSegmentMap = {
  sessions: 'Sessions',
  workflows: 'Workflows',
  // Add new route mappings here
};
```

To add a new route label, update this map and re-export the utility function.

### Technical Details

- **Hook dependency**: Uses React Router's `useLocation()` to detect navigation changes
- **Utility function**: Leverages `parseBreadcrumbsFromPath()` from `src/lib/breadcrumb-utils.ts` for path parsing logic
- **shadcn/ui primitives**: Uses Breadcrumb, BreadcrumbItem, BreadcrumbList, and BreadcrumbSeparator from shadcn/ui
- **Type safety**: Breadcrumb items are typed via `BreadcrumbItem` interface from `src/types/breadcrumb.ts`

---

## Support for Future Maintainers

### File Structure
- `AppHeader.tsx` — Main fixed header component (composed of three sub-components)
- `HeaderLogo.tsx` — Logo button component
- `BreadcrumbNav.tsx` — Breadcrumb navigation component
- `ThemeToggle.tsx` — Theme toggle button (separate concern, not documented in this file)

### Modifying Breadcrumb Logic
If you need to change how breadcrumbs are generated:
1. Edit `src/lib/breadcrumb-utils.ts` (path parsing logic)
2. Update `src/types/breadcrumb.ts` if adding new properties to `BreadcrumbItem`
3. Re-run type checks: `pnpm run build`

### Styling Updates
All components use Tailwind CSS `@theme` tokens defined in `src/index.css`. To update colors or spacing:
1. Modify the `@theme` block in `src/index.css`
2. Components will automatically use the updated values
3. Run `pnpm run build` to verify no TypeScript errors

### Dark Mode
Dark mode is automatically handled by Tailwind's class-based dark mode. Components use:
- `dark:text-on-surface` for dark-mode-specific colors
- `@media (prefers-color-scheme: dark)` in the CSS layer for system preference detection
- No additional JavaScript needed for theme switching

### Adding New Routes
To add a new route and update breadcrumbs:
1. Add the route to your React Router configuration
2. Update `ROUTE_LABEL_MAP` in `src/lib/breadcrumb-utils.ts` with the new route label
3. BreadcrumbNav will automatically generate the correct breadcrumb trail
4. No changes needed to BreadcrumbNav component itself

### Testing
To verify the layout components work correctly:
1. Run `pnpm dev` to start the development server
2. Navigate to `http://localhost:5173`
3. Check that:
   - Header is fixed at the top (doesn't scroll with content)
   - Logo is clickable and navigates to `/`
   - Theme toggle appears on the right
   - Breadcrumbs appear in the second row
   - Breadcrumbs update as you navigate between routes

---

## Imports & Dependencies

### Internal Imports
- `@/components/ui/button` — shadcn Button primitive
- `@/components/ui/breadcrumb` — shadcn Breadcrumb primitives (Breadcrumb, BreadcrumbItem, BreadcrumbList, BreadcrumbSeparator)
- `@/lib/breadcrumb-utils` — Route parsing utility (`parseBreadcrumbsFromPath`)
- `@/types/breadcrumb` — Type definitions (`BreadcrumbItem`, `RouteSegmentMap`)
- `@/components/layout/ThemeToggle` — Theme toggle component (used by AppHeader)

### External Dependencies
- `react-router` — For `useLocation()` and `Link` navigation
- Tailwind CSS — For styling via `@theme` tokens and utility classes

---

## License
All files in this directory include SPDX license headers:
```
// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
```
