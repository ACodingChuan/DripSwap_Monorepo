import * as SelectPrimitive from '@radix-ui/react-select';
import { Check, ChevronDown, ChevronUp } from 'lucide-react';
import * as React from 'react';

import { cn } from '@/shared/utils';

const Select = SelectPrimitive.Root;

const SelectTrigger = React.forwardRef<
  React.ElementRef<typeof SelectPrimitive.Trigger>,
  React.ComponentPropsWithoutRef<typeof SelectPrimitive.Trigger>
>(({ className, children, ...props }, ref) => (
  <SelectPrimitive.Trigger
    ref={ref}
    className={cn(
      'flex h-11 w-full items-center justify-between rounded-[var(--radius-control)] border border-input bg-background px-[var(--space-md)] text-sm text-foreground shadow-sm transition-all duration-200',
      'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:cursor-not-allowed disabled:opacity-50',
      className
    )}
    {...props}
  >
    {children}
    <SelectPrimitive.Icon asChild>
      <ChevronDown className="ml-2 size-4 opacity-70" aria-hidden="true" />
    </SelectPrimitive.Icon>
  </SelectPrimitive.Trigger>
));
SelectTrigger.displayName = SelectPrimitive.Trigger.displayName;

const SelectValue = SelectPrimitive.Value;

const SelectContent = React.forwardRef<
  React.ElementRef<typeof SelectPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof SelectPrimitive.Content>
>(({ className, children, position = 'popper', ...props }, ref) => (
  <SelectPrimitive.Portal>
    <SelectPrimitive.Content
      ref={ref}
      className={cn(
        'z-50 min-w-[12rem] overflow-hidden rounded-[var(--radius-control)] border border-border bg-background text-foreground shadow-lg',
        'data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out data-[state=open]:fade-in',
        className
      )}
      position={position}
      {...props}
    >
      <SelectPrimitive.ScrollUpButton className="flex items-center justify-center bg-muted py-2 text-sm text-muted-foreground">
        <ChevronUp className="size-4" aria-hidden="true" />
      </SelectPrimitive.ScrollUpButton>
      <SelectPrimitive.Viewport className="p-[var(--space-xs)]">
        {children}
      </SelectPrimitive.Viewport>
      <SelectPrimitive.ScrollDownButton className="flex items-center justify-center bg-muted py-2 text-sm text-muted-foreground">
        <ChevronDown className="size-4" aria-hidden="true" />
      </SelectPrimitive.ScrollDownButton>
    </SelectPrimitive.Content>
  </SelectPrimitive.Portal>
));
SelectContent.displayName = SelectPrimitive.Content.displayName;

const SelectLabel = ({
  className,
  ...props
}: React.ComponentProps<typeof SelectPrimitive.Label>) => (
  <SelectPrimitive.Label
    className={cn(
      'px-[var(--space-sm)] py-[var(--space-xs)] text-xs font-semibold uppercase text-muted-foreground',
      className
    )}
    {...props}
  />
);

const SelectItem = React.forwardRef<
  React.ElementRef<typeof SelectPrimitive.Item>,
  React.ComponentPropsWithoutRef<typeof SelectPrimitive.Item>
>(({ className, children, ...props }, ref) => (
  <SelectPrimitive.Item
    ref={ref}
    className={cn(
      'relative flex w-full cursor-default select-none items-center rounded-[calc(var(--radius-control)/2)] px-[var(--space-xl)] py-[var(--space-xs)] text-sm text-foreground outline-none transition',
      'focus:bg-primary/10 focus:text-foreground data-[disabled]:pointer-events-none data-[disabled]:opacity-50',
      className
    )}
    {...props}
  >
    <span className="absolute left-[var(--space-sm)] flex items-center justify-center">
      <SelectPrimitive.ItemIndicator>
        <Check className="size-4" aria-hidden="true" />
      </SelectPrimitive.ItemIndicator>
    </span>
    <SelectPrimitive.ItemText>{children}</SelectPrimitive.ItemText>
  </SelectPrimitive.Item>
));
SelectItem.displayName = SelectPrimitive.Item.displayName;

const SelectSeparator = ({
  className,
  ...props
}: React.ComponentProps<typeof SelectPrimitive.Separator>) => (
  <SelectPrimitive.Separator
    className={cn('my-[var(--space-xs)] h-px bg-border/70', className)}
    {...props}
  />
);

export {
  Select,
  SelectTrigger,
  SelectValue,
  SelectContent,
  SelectItem,
  SelectLabel,
  SelectSeparator,
};
