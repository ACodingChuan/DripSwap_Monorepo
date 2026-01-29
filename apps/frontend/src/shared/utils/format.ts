export function shortenHex(
  value: string | null | undefined,
  startChars = 6,
  endChars = 4
): string {
  if (!value) return '—';
  const v = value.trim();
  if (v.length <= startChars + endChars + 2) return v;
  return `${v.slice(0, startChars)}…${v.slice(-endChars)}`;
}

