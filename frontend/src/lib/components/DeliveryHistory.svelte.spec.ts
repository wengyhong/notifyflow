import { afterEach, expect, it, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { render } from 'vitest-browser-svelte';
import { tick } from 'svelte';
import DeliveryHistory from './DeliveryHistory.svelte';
import { api, attempt, deferred, DELIVERY, delivery, mutation, OTHER_TENANT, pageOf, TENANT } from '#lib/testing/api.ts';

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
	const select = document.querySelector<HTMLSelectElement>('#delivery-status')!;
	select.value = 'FAILED';
	select.dispatchEvent(new Event('change', { bubbles: true }));
	await expect.element(page.getByRole('heading', { name: 'Delivery details', exact: true })).not.toBeInTheDocument();
});

it('opens attempt history in a dialog and restores focus after Escape', async () => {
	const fetch = api(url => url.pathname.endsWith('/attempts') ? Response.json(pageOf([attempt({ responseStatus: 503 })]))
		: url.pathname === `/api/deliveries/${DELIVERY}` ? Response.json(delivery()) : Response.json(pageOf([delivery()])));
	await render(DeliveryHistory, { tenantId: TENANT, canReplay: false });
	const trigger = page.getByRole('button', { name: 'View details' });
	await trigger.click();
	const panel = page.getByRole('dialog', { name: 'Delivery details' });
	await expect.element(panel).toBeVisible();
	await expect.element(panel.getByText('HTTP 503', { exact: true })).toBeVisible();
	await expect.element(panel.getByRole('button', { name: 'Replay failed delivery' })).not.toBeInTheDocument();
	expect(fetch.mock.calls.every(([, init]) => new Headers(init?.headers).get('X-Tenant-ID') === TENANT)).toBe(true);
	await userEvent.keyboard('{Escape}');
	await expect.element(panel).not.toBeInTheDocument();
	await expect.element(trigger).toHaveFocus();
});

it('keeps the panel open after replay while updating details and the delivery list', async () => {
	let replayed = false;
	const fetch = api((url, init) => {
		if (init.method === 'POST') { replayed = true; return Response.json({ id: DELIVERY, status: 'PENDING' }, { status: 202 }); }
		if (url.pathname.endsWith('/attempts')) return Response.json(pageOf([attempt()]));
		const current = delivery({ status: replayed ? 'PENDING' : 'FAILED' });
		return Response.json(url.pathname === `/api/deliveries/${DELIVERY}` ? current : pageOf([current]));
	});
	await render(DeliveryHistory, { tenantId: TENANT, canReplay: true });
	await page.getByRole('button', { name: 'View details' }).click();
	const panel = page.getByRole('dialog', { name: 'Delivery details' });
	await panel.getByRole('button', { name: 'Replay failed delivery' }).click();
	await expect.element(panel.getByText('Replay accepted. Refresh to see processing progress and new attempts.')).toBeVisible();
	await expect.element(panel.getByText('pending', { exact: true })).toBeVisible();
	mutation(fetch, `/api/deliveries/${DELIVERY}/replay`, 'POST', TENANT);
	await page.getByRole('button', { name: 'Close delivery panel' }).click();
	await expect.element(page.getByRole('table', { name: 'Delivery history' }).getByText('pending', { exact: true })).toBeVisible();
});

it('paginates attempts inside the panel and reopens on a different delivery', async () => {
	const secondId = 'second-delivery-id';
	const fetch = api(url => {
		if (url.pathname.endsWith('/attempts')) {
			const currentPage = Number(url.searchParams.get('page') ?? 0);
			return Response.json(pageOf([attempt({ attemptNumber: currentPage ? 1 : 2, responseStatus: currentPage ? 400 : 204 })], currentPage, 20, 21));
		}
		if (url.pathname === `/api/deliveries/${DELIVERY}`) return Response.json(delivery());
		if (url.pathname === `/api/deliveries/${secondId}`) return Response.json(delivery({ id: secondId, status: 'DELIVERED' }));
		return Response.json(pageOf([delivery(), delivery({ id: secondId, status: 'DELIVERED' })]));
	});
	await render(DeliveryHistory, { tenantId: TENANT, canReplay: false });
	await page.getByRole('button', { name: 'View details' }).nth(0).click();
	const panel = page.getByRole('dialog', { name: 'Delivery details' });
	await panel.getByRole('navigation', { name: 'Delivery attempt pagination' }).getByRole('button', { name: 'Next', exact: true }).click();
	await expect.element(panel.getByText('Attempt 1', { exact: true })).toBeVisible();
	expect(fetch.mock.calls.some(([url]) => String(url).includes(`${DELIVERY}/attempts?page=1`))).toBe(true);
	await page.getByRole('button', { name: 'Close delivery panel' }).click();
	await page.getByRole('button', { name: 'View details' }).nth(1).click();
	await expect.element(panel.getByText(secondId, { exact: true })).toBeVisible();
	await expect.element(panel.getByText('Attempt 2', { exact: true })).toBeVisible();
	await expect.element(panel.getByText('Attempt 1', { exact: true })).not.toBeInTheDocument();
});

it('discards an open panel and delayed old details after a workspace change', async () => {
	const old = deferred<Response>();
	api((url, init) => {
		const other = new Headers(init.headers).get('X-Tenant-ID') === OTHER_TENANT;
		if (url.pathname.endsWith('/attempts')) return Response.json(pageOf([]));
		if (url.pathname === `/api/deliveries/${DELIVERY}`) return old.promise;
		return Response.json(pageOf([delivery(other ? { id: 'other-delivery', status: 'DELIVERED' } : {})]));
	});
	const screen = await render(DeliveryHistory, { tenantId: TENANT, canReplay: false });
	await page.getByRole('button', { name: 'View details' }).click();
	await expect.element(page.getByRole('dialog')).toBeVisible();
	await screen.rerender({ tenantId: OTHER_TENANT });
	await expect.element(page.getByRole('dialog')).not.toBeInTheDocument();
	old.resolve(Response.json(delivery()));
	await old.promise;
	await tick();
	await expect.element(page.getByRole('dialog')).not.toBeInTheDocument();
	await expect.element(page.getByRole('table').getByText('delivered', { exact: true })).toBeVisible();
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
