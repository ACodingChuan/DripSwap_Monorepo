import path from 'node:path';

import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactPlugin from 'eslint-plugin-react';
import hooksPlugin from 'eslint-plugin-react-hooks';
import jsxA11yPlugin from 'eslint-plugin-jsx-a11y';
import importPlugin from 'eslint-plugin-import';
import prettierConfig from 'eslint-config-prettier';

const [tsRecommended, tsStylistic] = tseslint.configs.recommendedTypeChecked;

const FRONTEND_DIR = path.resolve('apps/frontend');

function withProject(config) {
  return {
    ...config,
    languageOptions: {
      ...config.languageOptions,
      parserOptions: {
        ...config.languageOptions?.parserOptions,
        project: [
          path.join(FRONTEND_DIR, 'tsconfig.json'),
          path.join(FRONTEND_DIR, 'tsconfig.e2e.json'),
        ],
        tsconfigRootDir: FRONTEND_DIR,
      },
    },
  };
}

export default tseslint.config(
  {
    ignores: [
      'dist',
      'node_modules',
      '**/*.config.{js,ts}',
      '.husky',
      'apps/frontend/verify-pair.js',
      'apps/frontend/test-results/**',
      'apps/frontend/dist',
    ],
  },
  js.configs.recommended,
  withProject(tsRecommended),
  withProject(tsStylistic),
  {
    files: ['apps/frontend/**/*.{ts,tsx,js,jsx}'],
    plugins: {
      react: reactPlugin,
      'react-hooks': hooksPlugin,
      'jsx-a11y': jsxA11yPlugin,
      import: importPlugin,
    },
    settings: {
      react: {
        version: 'detect',
      },
    },
    rules: {
      'react/react-in-jsx-scope': 'off',
      'react/jsx-uses-react': 'off',
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'warn',
      'jsx-a11y/anchor-is-valid': 'off',
      'import/order': 'off',
      'no-unused-vars': 'off',
      '@typescript-eslint/require-await': 'off',
      '@typescript-eslint/no-unsafe-assignment': 'off',
      '@typescript-eslint/only-throw-error': 'off',
      '@typescript-eslint/no-unused-vars': [
        'warn',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrors: 'none' },
      ],
    },
  },
  prettierConfig,
);
