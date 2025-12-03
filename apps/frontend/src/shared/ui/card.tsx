import * as React from 'react';

import { cn } from '@/shared/utils';

const Card = React.forwardRef<HTMLDivElement, React.ComponentProps<'div'>>(
  ({ className, ...props }, ref) => (
    <div
      ref={ref}
      className={cn(
        'rounded-[var(--radius-card)] border border-border/80 bg-card text-card-foreground shadow-[0_18px_40px_-28px_rgba(15,23,42,0.45)]',
        'transition-all duration-200 hover:shadow-[0_24px_60px_-32px_rgba(30,102,245,0.35)]',
        className
      )}
      {...props}
    />
  )
);
Card.displayName = 'Card';

const CardHeader = ({ className, ...props }: React.ComponentProps<'div'>) => (
  <div
    className={cn('flex flex-col gap-[var(--space-sm)] p-[var(--space-xl)]', className)}
    {...props}
  />
);

const CardTitle = ({ className, ...props }: React.ComponentProps<'h3'>) => (
  <h3 className={cn('text-lg font-semibold leading-tight text-foreground', className)} {...props} />
);

const CardDescription = ({ className, ...props }: React.ComponentProps<'p'>) => (
  <p className={cn('text-sm text-muted-foreground', className)} {...props} />
);

const CardContent = ({ className, ...props }: React.ComponentProps<'div'>) => (
  <div className={cn('p-[var(--space-xl)] pt-0', className)} {...props} />
);

const CardFooter = ({ className, ...props }: React.ComponentProps<'div'>) => (
  <div
    className={cn('flex items-center gap-[var(--space-sm)] p-[var(--space-xl)] pt-0', className)}
    {...props}
  />
);

export { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter };
