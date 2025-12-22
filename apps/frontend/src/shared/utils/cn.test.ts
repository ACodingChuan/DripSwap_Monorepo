import { describe, expect, it } from 'vitest';

import { cn } from './cn';

describe('cn', () => {
  it('merges conditional class names', () => {
    const shouldHide = Math.random() > 1;
    const result = cn('px-4', undefined, shouldHide && 'hidden', 'text-base');
    expect(result).toBe('px-4 text-base');
  });

  it('resolves Tailwind conflicts by keeping the latest value', () => {
    const result = cn('px-2', 'px-4', 'text-sm', 'text-base');
    expect(result).toBe('px-4 text-base');
  });
});
