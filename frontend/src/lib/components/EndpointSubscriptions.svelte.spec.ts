import { afterEach, expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import { render } from 'vitest-browser-svelte';
import EndpointSubscriptions from './EndpointSubscriptions.svelte';
import { api, ENDPOINT, TENANT } from '#lib/testing/api.ts';

afterEach(() => vi.unstubAllGlobals());

it('loads subscriptions only when expanded and gives viewers no creation control', async () => {
	const fetch = api(() => Response.json([{ id: 'sub-id', endpointId: ENDPOINT, eventType: 'order.created' }]));
	await render(EndpointSubscriptions, { tenantId: TENANT, endpointId: ENDPOINT, canManage: false });
	await expect.element(page.getByText('Event subscriptions', { exact: true })).toBeVisible();
	expect(fetch).not.toHaveBeenCalled();
	await page.getByText('Event subscriptions', { exact: true }).click();
	await expect.element(page.getByText('order.created', { exact: true })).toBeVisible();
	await expect.element(page.getByRole('button', { name: 'Subscribe', exact: true })).not.toBeInTheDocument();
	expect(new Headers(fetch.mock.calls[0][1]?.headers).get('X-Tenant-ID')).toBe(TENANT);
});

it('refreshes the visible subscription list after an administrator creates one', async () => {
	let created = false;
	api((_url, init) => {
		const subscription = { id: 'sub-id', endpointId: ENDPOINT, eventType: 'order.created' };
		if (init.method === 'POST') { created = true; return Response.json(subscription, { status: 201 }); }
		return Response.json(created ? [subscription] : []);
	});
	await render(EndpointSubscriptions, { tenantId: TENANT, endpointId: ENDPOINT, canManage: true });
	await page.getByText('Event subscriptions', { exact: true }).click();
	await expect.element(page.getByText('No event types subscribed yet.')).toBeVisible();
	await page.getByLabelText('Subscribe to an event type').fill('order.created');
	await page.getByRole('button', { name: 'Subscribe', exact: true }).click();
	await expect.element(page.getByText('order.created', { exact: true })).toBeVisible();
});

it('allows retrying a failed list request', async () => {
	let failed = true;
	api(() => failed ? new Response(null, { status: 401 }) : Response.json([]));
	await render(EndpointSubscriptions, { tenantId: TENANT, endpointId: ENDPOINT, canManage: false });
	await page.getByText('Event subscriptions', { exact: true }).click();
	await expect.element(page.getByText('Please log in again to view subscriptions.')).toBeVisible();
	failed = false;
	await page.getByRole('button', { name: 'Refresh subscriptions' }).click();
	await expect.element(page.getByText('No event types subscribed yet.')).toBeVisible();
});
