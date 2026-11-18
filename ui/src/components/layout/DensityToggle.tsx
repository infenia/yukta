// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useDensity } from '@/lib/density';
import { Tip } from '@/components/ui/tooltip';

/** Switches between the roomy layout and a compact one that fits more on a page. */
export default function DensityToggle() {
  const { density, setDensity } = useDensity();
  const compact = density === 'compact';
  return (
    <Tip label={compact ? 'Compact layout' : 'Comfortable layout'}>
      <button
        type="button"
        onClick={() => setDensity(compact ? 'comfortable' : 'compact')}
        aria-pressed={compact}
        aria-label="Compact layout"
        className="text-muted-foreground transition-standard hover:bg-muted hover:text-foreground rounded-lg p-2"
      >
        <span aria-hidden="true" className="material-symbols-outlined scale-80">
          {compact ? 'density_small' : 'density_medium'}
        </span>
      </button>
    </Tip>
  );
}
