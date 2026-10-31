// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import EditionSourceLink from '@/features/edition/components/EditionSourceLink';

export default function AppFooter() {
  return (
    <footer className="border-t border-border bg-background text-body-sm text-on-surface-variant">
      <div className="p-spacing-md text-center sm:px-6">
        <p>&copy; 2026 Infenia Private Limited. All rights reserved.</p>
        <EditionSourceLink />
      </div>
    </footer>
  );
}
