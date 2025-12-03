import * as React from 'react';

import { cn } from '@/shared/utils';

type BadgeVariant = 'default' | 'outline' | 'success' | 'warning';

const VARIANT_CLASSES: Record<BadgeVariant, string> = {
  default: 'border border-primary/25 bg-primary/12 text-primary',
  outline: 'border border-border/70 bg-transparent text-muted-foreground',
  success: 'border border-success/25 bg-success/15 text-success',
  warning: 'border border-warning/25 bg-warning/15 text-warning',
};

export type BadgeProps = React.HTMLAttributes<HTMLSpanElement> & {
  variant?: BadgeVariant;
};

const Badge = React.forwardRef<HTMLSpanElement, BadgeProps>(
  ({ className, variant = 'default', ...props }, ref) => (
    <span
      ref={ref}
      className={cn(
        'inline-flex items-center rounded-[var(--radius-pill)] px-[var(--space-sm)] py-[2px] text-xs font-semibold uppercase tracking-wide',
        VARIANT_CLASSES[variant],
        className
      )}
      {...props}
    />
  )
);

Badge.displayName = 'Badge';

export { Badge };
