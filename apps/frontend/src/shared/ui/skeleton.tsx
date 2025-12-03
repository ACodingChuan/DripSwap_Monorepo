import { cn } from '@/shared/utils';

type SkeletonProps = React.HTMLAttributes<HTMLDivElement>;

export function Skeleton({ className, ...props }: SkeletonProps) {
  return (
    <div
      className={cn(
        'animate-pulse rounded-[calc(var(--radius-control)/1.2)] bg-muted/70',
        className
      )}
      aria-hidden="true"
      {...props}
    />
  );
}
