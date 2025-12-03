import { useMemo } from 'react';
import { Toaster as SonnerToaster, toast as sonnerToast } from 'sonner';

import { useUiStore } from '@/app/store/ui-store';

function Toaster() {
  const resolvedTheme = useUiStore((state) => state.resolvedTheme);

  const theme = useMemo(() => (resolvedTheme === 'dark' ? 'dark' : 'light'), [resolvedTheme]);

  return (
    <SonnerToaster
      position="top-right"
      theme={theme}
      richColors
      toastOptions={{ duration: 2000 }}
      visibleToasts={4}
    />
  );
}

const toast = sonnerToast;

export { Toaster, toast };
