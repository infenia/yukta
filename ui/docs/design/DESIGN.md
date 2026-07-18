---
name: High-Density Enterprise System
colors:
  surface: '#fcf9f8'
  surface-dim: '#dcd9d9'
  surface-bright: '#fcf9f8'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f6f3f2'
  surface-container: '#f0edec'
  surface-container-high: '#eae7e7'
  surface-container-highest: '#e5e2e1'
  on-surface: '#1c1b1b'
  on-surface-variant: '#424656'
  inverse-surface: '#313030'
  inverse-on-surface: '#f3f0ef'
  outline: '#737687'
  outline-variant: '#c3c6d8'
  surface-tint: '#0052dd'
  primary: '#004ccd'
  on-primary: '#ffffff'
  primary-container: '#0f62fe'
  on-primary-container: '#f3f3ff'
  inverse-primary: '#b4c5ff'
  secondary: '#5f5e5e'
  on-secondary: '#ffffff'
  secondary-container: '#e4e2e1'
  on-secondary-container: '#656464'
  tertiary: '#9e3100'
  on-tertiary: '#ffffff'
  tertiary-container: '#c84000'
  on-tertiary-container: '#fff1ed'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#dbe1ff'
  primary-fixed-dim: '#b4c5ff'
  on-primary-fixed: '#00174c'
  on-primary-fixed-variant: '#003da9'
  secondary-fixed: '#e4e2e1'
  secondary-fixed-dim: '#c8c6c6'
  on-secondary-fixed: '#1b1c1c'
  on-secondary-fixed-variant: '#474747'
  tertiary-fixed: '#ffdbd0'
  tertiary-fixed-dim: '#ffb59d'
  on-tertiary-fixed: '#390c00'
  on-tertiary-fixed-variant: '#832700'
  background: '#fcf9f8'
  on-background: '#1c1b1b'
  surface-variant: '#e5e2e1'
typography:
  display-01:
    fontFamily: IBM Plex Sans
    fontSize: 42px
    fontWeight: '300'
    lineHeight: 48px
    letterSpacing: 0px
  headline-lg:
    fontFamily: IBM Plex Sans
    fontSize: 32px
    fontWeight: '400'
    lineHeight: 40px
    letterSpacing: 0px
  headline-lg-mobile:
    fontFamily: IBM Plex Sans
    fontSize: 28px
    fontWeight: '400'
    lineHeight: 34px
    letterSpacing: 0px
  headline-md:
    fontFamily: IBM Plex Sans
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 26px
    letterSpacing: 0px
  body-md:
    fontFamily: IBM Plex Sans
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 18px
    letterSpacing: 0.16px
  body-sm:
    fontFamily: IBM Plex Sans
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
    letterSpacing: 0.16px
  label-md:
    fontFamily: IBM Plex Sans
    fontSize: 14px
    fontWeight: '600'
    lineHeight: 18px
    letterSpacing: 0.16px
  label-sm:
    fontFamily: IBM Plex Sans
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.16px
  code-sm:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
    letterSpacing: 0px
spacing:
  unit: 4px
  xs: 4px
  sm: 8px
  md: 16px
  lg: 24px
  xl: 32px
  gutter: 1px
  margin-compact: 16px
---

## Brand & Style
The design system is a high-density, utility-focused framework engineered for enterprise-grade applications where data throughput and systematic efficiency are paramount. The personality is professional, clean, and technical, prioritizing functional clarity over decorative flair.

Drawing from **Corporate / Modern** and **Minimalist** influences, the style emphasizes a rigid "monolithic" structure. It uses a crisp light-mode palette to ensure high legibility and a classic professional feel during daylight operations. The aesthetic is defined by sharp edges, clear architectural boundaries, and a "dashboard-first" mentality that favors information density over negative space.

## Colors
The color palette is strictly functional, utilizing the clean neutrals of a professional enterprise environment to establish a clear hierarchy of surfaces.

- **Primary Background (#ffffff):** The base layer for all application screens.
- **Surface/Container (#f4f4f4):** Used for cards, sidebars, and nested UI regions to create depth without relying on shadows.
- **Primary Accent (#0f62fe):** Reserved for high-priority actions, active states, and focus indicators.
- **Borders (#e0e0e0):** The primary tool for structural separation, providing definition between dense data clusters.
- **Text Hierarchy:** Deep charcoal (#161616) is reserved for headings and critical labels, while medium gray (#525252) handles secondary body content and captions.

## Typography
The system utilizes **IBM Plex Sans** for all UI elements to maintain a technical, engineered feel. To achieve high density, the line heights are tightened compared to consumer-facing apps, allowing more rows of data to be visible.

For specialized technical views, **JetBrains Mono** is introduced for monospaced data values and code snippets. Weight is used strategically: SemiBold (600) is used for labels and headers to ensure legibility against the light background, while Regular (400) is used for standard data input and body text.

## Layout & Spacing
The layout operates on a **4px baseline grid**. This design system employs a **Fluid Grid** model with a preference for "1px gutter" dividers rather than wide whitespace gaps, maximizing the screen real estate for data.

- **Desktop (1200px+):** 12-column grid, 16px margins. Containers often use `1px` borders to separate content zones.
- **Tablet (768px - 1199px):** 8-column grid, 16px margins.
- **Mobile (Up to 767px):** 4-column grid, 12px margins.

Spacing is compressed; `md` (16px) is the standard for container padding, but `sm` (8px) is frequently used within complex components like data tables and toolbars.

## Elevation & Depth
In this system, depth is communicated through **Tonal Layers** and **Low-contrast Outlines** rather than shadows. 

1. **Level 0 (Base):** #ffffff (Background).
2. **Level 1 (Layer):** #f4f4f4 (Cards, Modals, Sidebars).
3. **Level 2 (Interaction):** #e0e0e0 (Hover states, active button backgrounds).

Shadows are avoided entirely to keep the UI feeling "flat" and performant. Instead, 1px borders (#e0e0e0) are used to define the boundaries of interactive elements and containers. This creates a blueprint-like precision across the interface.

## Shapes
The shape language is strictly **Sharp (0px)**. All containers, buttons, inputs, and tabs use square corners. This reinforces the systematic, enterprise nature of the design system and ensures that elements align perfectly with the pixel grid, preventing any blurriness in high-density layouts.

## Components
- **Buttons:** Sharp corners. Primary buttons use #0f62fe background with white text. Ghost buttons use #e0e0e0 borders. Height is capped at 32px for standard density.
- **Data Tables:** The core of the system. Rows are 32px high. Use #e0e0e0 horizontal borders only. Alternate row striping is not used; instead, use #f4f4f4 on hover.
- **Input Fields:** Background #ffffff with a bottom border of #393939. On focus, the bottom border changes to #0f62fe (2px thick).
- **Chips/Tags:** Small (18px high), rectangular, using a #e0e0e0 background with `label-sm` typography.
- **Lists:** High-density vertical stacks. Items are separated by 1px lines. No internal padding on the left/right of the list item text to ensure alignment with headers.
- **Cards:** #f4f4f4 background, no shadow, 1px border (#e0e0e0). Padding should be a consistent 16px.
- **Tabs:** Underline style. Active tab has a 2px #0f62fe bottom border. Inactive tabs use #525252 text color.