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
    <div className="flex-1 flex items-center justify-center px-spacing-md py-spacing-lg">
      <div className="text-center max-w-md">
        <div className="mb-spacing-lg">
          <span aria-hidden="true" className="material-symbols-outlined text-6xl text-outline">
            construction
          </span>
        </div>
        <h1 className="text-headline-lg mb-spacing-md">{title}</h1>
        <p className="text-body-md text-on-surface-variant">
          {description || 'This page is coming soon.'}
        </p>
      </div>
    </div>
  );
}
