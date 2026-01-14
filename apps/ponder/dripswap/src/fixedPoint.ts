import Decimal from "decimal.js";

Decimal.set({
  precision: 80,
  rounding: Decimal.ROUND_HALF_UP,
  toExpNeg: -100,
  toExpPos: 100,
});

export function formatWad(value: Decimal): string {
  if (!value || !value.isFinite()) {
    return "0";
  }
  return value.toString();
}

export function parseWad(value: string): Decimal {
  if (!value) {
    return new Decimal(0);
  }
  try {
    return new Decimal(value);
  } catch {
    return new Decimal(0);
  }
}

export function scaleToWad(raw: bigint, decimals: bigint): Decimal {
  if (raw === 0n) {
    return new Decimal(0);
  }
  return new Decimal(raw.toString()).div(new Decimal(10).pow(decimals.toString()));
}

export function mulWad(a: Decimal, b: Decimal): Decimal {
  return a.mul(b);
}

export function divWad(a: Decimal, b: Decimal): Decimal {
  if (!b || b.isZero()) {
    return new Decimal(0);
  }
  return a.div(b);
}
