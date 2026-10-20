// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useEdition } from '../hooks/useEdition';

/** "Source code" link of a build whose licence asks for it (AGPL section 13); nothing otherwise. */
export default function EditionSourceLink() {
  const { data } = useEdition();
  if (!data?.source) {
    return null;
  }
  return (
    <p>
      <a href={data.source} className="underline" rel="noreferrer">
        Source code
      </a>{' '}
      ({data.license})
    </p>
  );
}
