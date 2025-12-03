import { Monitor, MoonStar, Sun } from 'lucide-react';
import { useMemo } from 'react';

import { useUiStore, type ThemePreference } from '@/app/store/ui-store';

import { Badge } from './badge';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from './select';

export function ThemeToggle() {
  const theme = useUiStore((state) => state.theme);
  const resolved = useUiStore((state) => state.resolvedTheme);
  const setTheme = useUiStore((state) => state.setTheme);

  const icon = useMemo(() => {
    if (theme === 'system') {
      return <Monitor className="size-4" aria-hidden="true" />;
    }

    return theme === 'dark' ? (
      <MoonStar className="size-4" aria-hidden="true" />
    ) : (
      <Sun className="size-4" aria-hidden="true" />
    );
  }, [theme]);

  const handleChange = (value: ThemePreference) => {
    setTheme(value);
  };

  return (
    <div className="flex flex-col gap-[var(--space-sm)]">
      <div className="flex items-center justify-between gap-[var(--space-sm)]">
        <div className="flex items-center gap-[var(--space-xs)] text-sm font-medium text-muted-foreground">
          {icon}
          <span>Theme</span>
        </div>
        <Badge variant="outline">{resolved}</Badge>
      </div>
      <Select value={theme} onValueChange={(value) => handleChange(value as ThemePreference)}>
        <SelectTrigger aria-label="Select theme">
          <SelectValue placeholder="Choose theme" />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="light">
            <div className="flex items-center gap-[var(--space-xs)]">
              <Sun className="size-4" aria-hidden="true" />
              <span>Light</span>
            </div>
          </SelectItem>
          <SelectItem value="dark">
            <div className="flex items-center gap-[var(--space-xs)]">
              <MoonStar className="size-4" aria-hidden="true" />
              <span>Dark</span>
            </div>
          </SelectItem>
          <SelectItem value="system">
            <div className="flex items-center gap-[var(--space-xs)]">
              <Monitor className="size-4" aria-hidden="true" />
              <span>System</span>
            </div>
          </SelectItem>
        </SelectContent>
      </Select>
    </div>
  );
}
