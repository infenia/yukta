// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

interface ComingSoonPageProps {
  title: string;
  description?: string;
}

export default function ComingSoonPage({
  title,
  description,
}: ComingSoonPageProps) {
  return (
    <div className="px-spacing-md py-spacing-lg flex flex-1 items-center justify-center">
      <div className="max-w-md text-center">
        <div className="mb-spacing-lg">
          <span
            aria-hidden="true"
            className="material-symbols-outlined text-6xl text-outline"
          >
            construction
          </span>
        </div>
        <h1 className="mb-spacing-md text-headline-lg">{title}</h1>
        <p className="text-body-md text-on-surface-variant">
          {description || 'This page is coming soon.'}
        </p>
      </div>
    </div>
  );
}
