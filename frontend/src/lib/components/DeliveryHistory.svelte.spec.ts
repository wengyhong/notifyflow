import { afterEach, expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import { render } from 'vitest-browser-svelte';
import { tick } from 'svelte';
import DeliveryHistory from './DeliveryHistory.svelte';
import { api, deferred, DELIVERY, delivery, OTHER_TENANT, pageOf, TENANT } from '#lib/testing/api.ts';

afterEach(() => vi.unstubAllGlobals());

it('filters status, resets to the first page, and navigates delivery pages', async () => {
	const fetch = api((url) => Response.json(pageOf([delivery({ status: url.searchParams.get('status') === 'FAILED' ? 'FAILED' : 'DELIVERED' })], Number(url.searchParams.get('page') ?? 0), 20, 21)));
	await render(DeliveryHistory, { tenantId: TENANT, canReplay: false });
	await expect.element(page.getByRole('table').getByText('delivered', { exact: true })).toBeVisible();
	await page.getByRole('button', { name: 'Next', exact: true }).click();
	await expect.element(page.getByText('Page 2 of 2', { exact: true })).toBeVisible();
	await page.getByLabelText('FILTER BY STATUS').selectOptions('FAILED');
	await expect.element(page.getByRole('table').getByText('failed', { exact: true })).toBeVisible();
	await expect.element(page.getByText('Page 1 of 2', { exact: true })).toBeVisible();
	expect(fetch.mock.calls.some(([url]) => String(url).includes('page=0&size=20&status=FAILED'))).toBe(true);
	expect(fetch.mock.calls.every(([, init]) => new Headers(init?.headers).get('X-Tenant-ID') === TENANT)).toBe(true);
});

it('opens details and clears them when the status filter changes', async () => {
	api((url) => url.pathname.endsWith('/attempts') ? Response.json(pageOf([]))
		: url.pathname === `/api/deliveries/${DELIVERY}` ? Response.json(delivery()) : Response.json(pageOf([delivery()])));
	await render(DeliveryHistory, { tenantId: TENANT, canReplay: false });
	await page.getByRole('button', { name: 'View details' }).click();
	await expect.element(page.getByRole('heading', { name: 'Delivery details', exact: true })).toBeVisible();
	await page.getByLabelText('FILTER BY STATUS').selectOptions('FAILED');
	await expect.element(page.getByRole('heading', { name: 'Delivery details', exact: true })).not.toBeInTheDocument();
});

it('shows an empty history without offering pagination or replay', async () => {
	api(() => Response.json(pageOf([])));
	await render(DeliveryHistory, { tenantId: TENANT, canReplay: true });
	await expect.element(page.getByText('No deliveries match this filter. Your events will appear here as they are processed.')).toBeVisible();
	await expect.element(page.getByRole('button', { name: 'Next', exact: true })).not.toBeInTheDocument();
});

it('ignores a slow response from the previously selected workspace', async () => {
	const old = deferred<Response>();
	api((_url, init) => new Headers(init.headers).get('X-Tenant-ID') === TENANT
		? old.promise : Response.json(pageOf([delivery({ id: 'new-delivery-id', status: 'DELIVERED' })])));
	const screen = await render(DeliveryHistory, { tenantId: TENANT, canReplay: false });
	await expect.element(page.getByText('Loading deliveries...', { exact: true })).toBeVisible();
	await screen.rerender({ tenantId: OTHER_TENANT });
	await expect.element(page.getByRole('table').getByText('delivered', { exact: true })).toBeVisible();
	old.resolve(Response.json(pageOf([delivery({ status: 'FAILED' })])));
	await old.promise;
	await tick();
	await expect.element(page.getByRole('table').getByText('delivered', { exact: true })).toBeVisible();
	await expect.element(page.getByRole('table').getByText('failed', { exact: true })).not.toBeInTheDocument();
});
