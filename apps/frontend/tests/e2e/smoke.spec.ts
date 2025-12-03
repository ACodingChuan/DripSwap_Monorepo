import { expect, test } from '@playwright/test';

test('home to swap navigation shows mocked quote details', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('link', { name: 'Swap' }).click();
  await expect(page.getByRole('heading', { name: 'Swap assets' })).toBeVisible();
  await expect(page.getByText('Quote placeholder')).toBeVisible();
});
