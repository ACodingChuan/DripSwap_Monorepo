import { createContext, useContext, useMemo, useState, type PropsWithChildren } from 'react';

import { en } from './locales/en';
import { zh } from './locales/zh';

export type SupportedLocale = 'en' | 'zh';

const dictionaries = {
  en,
  zh,
} satisfies Record<SupportedLocale, Record<string, unknown>>;

type I18nContextValue = {
  locale: SupportedLocale;
  setLocale: (locale: SupportedLocale) => void;
  t: (key: string) => string;
};

const I18nContext = createContext<I18nContextValue | null>(null);

export function I18nProvider({
  children,
  defaultLocale = 'en',
}: PropsWithChildren<{ defaultLocale?: SupportedLocale }>) {
  const [locale, setLocale] = useState<SupportedLocale>(defaultLocale);

  const value = useMemo<I18nContextValue>(() => {
    return {
      locale,
      setLocale,
      t: (key: string) => lookup(dictionaries[locale], key),
    };
  }, [locale]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n() {
  const context = useContext(I18nContext);

  if (!context) {
    throw new Error('useI18n must be used within I18nProvider');
  }

  return context;
}

function lookup(dictionary: Record<string, unknown>, key: string) {
  return key.split('.').reduce<string | Record<string, unknown>>((acc, segment) => {
    if (typeof acc === 'string') {
      return acc;
    }

    const next = acc?.[segment];

    if (typeof next === 'string') {
      return next;
    }

    if (typeof next === 'object' && next) {
      return next as Record<string, unknown>;
    }

    return key;
  }, dictionary) as string;
}
