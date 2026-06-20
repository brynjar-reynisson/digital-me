import { test, expect } from '@playwright/test';

test('screenshot capture sends two distinct captures while browsing Quora', async ({ page }) => {
  // Snapshot the names already in Digital Me so we only count new captures from this run
  const beforeRes = await page.request.get('http://localhost:8080/search?keywords=Quora');
  const beforeData = await beforeRes.json() as { results: { source: string; name: string }[] };
  const existingNames = new Set(
    beforeData.results
      .filter(r => r.name.startsWith('screenshot_quora_'))
      .map(r => r.name)
  );

  await page.goto('https://www.quora.com', { waitUntil: 'domcontentloaded' });

  // Click the first visible (more) link
  const moreLinks = page.getByText('(more)');
  await moreLinks.first().waitFor({ state: 'visible', timeout: 20_000 });
  await moreLinks.first().click();

  // Wait 10 s — one full screenshot cycle fires here
  await page.waitForTimeout(10_000);

  // Click the second (more) link; scroll into view if needed
  const allMore = page.getByText('(more)');
  const count = await allMore.count();
  if (count >= 2) {
    await allMore.nth(1).scrollIntoViewIfNeeded();
    await allMore.nth(1).click();
  } else {
    await page.evaluate(() => window.scrollBy(0, 800));
    await page.waitForTimeout(1_000);
    await page.getByText('(more)').first().scrollIntoViewIfNeeded();
    await page.getByText('(more)').first().click();
  }

  // Wait another 10 s — second screenshot cycle fires here
  await page.waitForTimeout(10_000);

  // Assert at least two new distinct captures were sent during this test run
  const afterRes = await page.request.get('http://localhost:8080/search?keywords=Quora');
  const afterData = await afterRes.json() as { results: { source: string; name: string }[] };
  const newNames = afterData.results
    .filter(r => r.name.startsWith('screenshot_quora_') && !existingNames.has(r.name))
    .map(r => r.name);

  expect(
    newNames.length,
    `Expected ≥2 new screenshot_quora_ captures, got: ${JSON.stringify(newNames)}`
  ).toBeGreaterThanOrEqual(2);
});
