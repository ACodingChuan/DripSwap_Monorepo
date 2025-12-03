import { useEffect, useRef } from 'react';

type Focusable = HTMLElement | null;

type Options = {
  autoFocus?: boolean;
};

export function usePageFocus<T extends Focusable>({ autoFocus = true }: Options = {}) {
  const ref = useRef<T>(null);

  useEffect(() => {
    if (!autoFocus) {
      return;
    }

    ref.current?.focus();
  }, [autoFocus]);

  return ref;
}
