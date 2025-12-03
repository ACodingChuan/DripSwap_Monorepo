import * as React from 'react';

import { cn } from '@/shared/utils';

export type InputProps = React.InputHTMLAttributes<HTMLInputElement>;

const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ className, type = 'text', ...props }, ref) => {
    return (
      <input
        type={type}
        ref={ref}
        className={cn(
          'flex h-11 w-full rounded-[var(--radius-control)] border border-input bg-background px-[var(--space-md)] text-sm text-foreground shadow-sm',
          'placeholder:text-muted-foreground transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
          'focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:cursor-not-allowed disabled:opacity-50',
          className
        )}
        {...props}
      />
    );
  }
);

Input.displayName = 'Input';

export { Input };
