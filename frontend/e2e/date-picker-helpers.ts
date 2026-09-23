import type { Page } from '@playwright/test';

export function periodTrigger(page: Page) {
  return page.getByRole('button', { name: /^기간 설정:/ });
}

export async function chooseDateBasis(page: Page, label: string) {
  const dialog = page.getByRole('dialog', { name: '기간 설정', exact: true });
  const basis = dialog.getByRole('button', { name: '기준 선택', exact: true });
  if ((await basis.textContent()) !== label) {
    await basis.click();
    await dialog.getByRole('option', { name: label, exact: true }).click();
  }
  return dialog;
}

export async function openDatePicker(page: Page, label = '방송일') {
  await periodTrigger(page).click();
  return chooseDateBasis(page, label);
}
