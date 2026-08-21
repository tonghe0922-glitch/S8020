import { expect, test } from '@playwright/test'

for (const port of ['work', 'tech'] as const) {
  test(`${port} port renders the compact login shell`, async ({ page }) => {
    await page.goto(`/${port}.html`)
    await expect(page.getByRole('heading', { name: '登录' })).toBeVisible()
    const card = page.locator('.platform-login-layout .sgj-card')
    await expect(card).toBeVisible()
    const width = await card.evaluate((element) => element.getBoundingClientRect().width)
    expect(width).toBeLessThanOrEqual(441)
  })
}
