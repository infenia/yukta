// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { cn } from '../utils';

describe('cn', () => {
  it('should merge class names with clsx', () => {
    const result = cn('px-2', 'py-1');
    expect(result).toContain('px-2');
    expect(result).toContain('py-1');
  });

  it('should handle conditional classes', () => {
    const isActive = true;
    const result = cn('base', isActive && 'active');
    expect(result).toContain('base');
    expect(result).toContain('active');
  });

  it('should handle falsy values', () => {
    const result = cn('base', false && 'active', null, undefined);
    expect(result).toBe('base');
  });

  it('should merge conflicting tailwind classes', () => {
    const result = cn('px-2', 'px-4');
    expect(result).toContain('px-4');
    expect(result).not.toContain('px-2');
  });

  it('should merge text color classes', () => {
    const result = cn('text-red-500', 'text-blue-500');
    expect(result).toContain('text-blue-500');
    expect(result).not.toContain('text-red-500');
  });

  it('should handle arrays of classes', () => {
    const result = cn(['px-2', 'py-1'], 'rounded');
    expect(result).toContain('px-2');
    expect(result).toContain('py-1');
    expect(result).toContain('rounded');
  });

  it('should handle objects for conditional classes', () => {
    const result = cn({
      'px-2': true,
      'py-1': false,
      rounded: true,
    });
    expect(result).toContain('px-2');
    expect(result).toContain('rounded');
    expect(result).not.toContain('py-1');
  });

  it('should handle complex nested structures', () => {
    const isDisabled = false;
    const result = cn(
      'px-4 py-2',
      ['rounded', 'border'],
      {
        'bg-gray-100': !isDisabled,
        'opacity-50': isDisabled,
      }
    );
    expect(result).toContain('px-4');
    expect(result).toContain('py-2');
    expect(result).toContain('rounded');
    expect(result).toContain('border');
    expect(result).toContain('bg-gray-100');
    expect(result).not.toContain('opacity-50');
  });

  it('should handle width priority classes', () => {
    const result = cn('w-full', 'w-1/2');
    expect(result).toContain('w-1/2');
    expect(result).not.toContain('w-full');
  });

  it('should handle background color conflicts', () => {
    const result = cn('bg-white', 'dark:bg-black');
    expect(result).toContain('bg-white');
    expect(result).toContain('dark:bg-black');
  });

  it('should handle responsive classes', () => {
    const result = cn('flex', 'md:grid', 'lg:flex');
    expect(result).toContain('flex');
    expect(result).toContain('md:grid');
    expect(result).toContain('lg:flex');
  });

  it('should return empty string for empty input', () => {
    const result = cn('', false, null, undefined);
    expect(result).toBe('');
  });

  it('should handle single class name', () => {
    const result = cn('px-2');
    expect(result).toBe('px-2');
  });

  it('should handle space-separated classes', () => {
    const result = cn('px-2 py-1 rounded');
    expect(result).toContain('px-2');
    expect(result).toContain('py-1');
    expect(result).toContain('rounded');
  });

  it('should deduplicate classes', () => {
    const result = cn('px-2', 'px-2');
    const classList = result.split(' ').filter(Boolean);
    const pxCount = classList.filter((c) => c === 'px-2').length;
    expect(pxCount).toBeLessThanOrEqual(1);
  });

  it('should handle display property conflicts', () => {
    const result = cn('block', 'inline-block');
    expect(result).toBe('inline-block');
  });

  it('should handle shadow classes', () => {
    const result = cn('shadow-sm', 'shadow-lg');
    expect(result).toContain('shadow-lg');
    expect(result).not.toContain('shadow-sm');
  });
});
