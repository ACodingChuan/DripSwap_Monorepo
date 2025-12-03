import { useEffect, type PropsWithChildren } from 'react';

import { useUiStore } from '@/app/store/ui-store';

const COLOR_SCHEME_QUERY = '(prefers-color-scheme: dark)';

export function ThemeProvider({ children }: PropsWithChildren) {
  const theme = useUiStore((state) => state.theme);
  const setResolvedTheme = useUiStore((state) => state.setResolvedTheme);

  useEffect(() => {
    if (typeof document === 'undefined' || typeof window === 'undefined') {
      return;
    }

    const root = document.documentElement;
    const mediaQuery = window.matchMedia(COLOR_SCHEME_QUERY);

    const applyTheme = () => {
      const shouldUseDark = theme === 'dark' || (theme === 'system' && mediaQuery.matches);
      const resolved = shouldUseDark ? 'dark' : 'light';

      root.classList.remove('light', 'dark');
      root.classList.add(resolved);
      root.style.colorScheme = resolved;
      root.dataset.theme = resolved;
      setResolvedTheme(resolved);
    };

    applyTheme();

    if (theme === 'system') {
      mediaQuery.addEventListener('change', applyTheme);
      return () => mediaQuery.removeEventListener('change', applyTheme);
    }

    return undefined;
  }, [theme, setResolvedTheme]);

  return <>{children}</>;
}
