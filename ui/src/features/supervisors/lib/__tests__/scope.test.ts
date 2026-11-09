// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { buildScope, parseSelectors } from '../scope';

describe('the scope helpers', () => {
  it('builds a scope and refuses an incomplete one', () => {
    expect(buildScope('global', '', '')).toBe('global');
    expect(buildScope('session', 's', '')).toBe('session:s');
    expect(buildScope('session', '', '')).toBeNull();
    expect(buildScope('workflow', '', 'w')).toBe('workflow:w');
    expect(buildScope('workflow', 's', 'w')).toBe('workflow:s/w');
    expect(buildScope('workflow', 's', '')).toBeNull();
    expect(buildScope('section', 's', 'w', ' build.* ,deploy ')).toBe(
      'workflow:s/w//build.*,deploy'
    );
    expect(buildScope('section', '', 'w', 'a b')).toBe('workflow:w//a,b');
    expect(buildScope('section', 's', 'w', ' , ')).toBeNull();
    expect(buildScope('section', 's', 'w')).toBeNull();
  });

  it('splits selectors on commas and blanks', () => {
    expect(parseSelectors('a, b  c,,')).toEqual(['a', 'b', 'c']);
  });
});
