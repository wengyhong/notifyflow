import { afterEach, expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import { render } from 'vitest-browser-svelte';
import DeliveryDetails from './DeliveryDetails.svelte';
import type { DeliveryStatus } from '#lib/types/delivery.ts';
import { api, attempt, DELIVERY, delivery, mutation, NOW, pageOf, TENANT } from '#lib/testing/api.ts';

afterEach(() => vi.unstubAllGlobals());
const props = () => ({ tenantId: TENANT, deliveryId: DELIVERY, canReplay: true, onReplayed: vi.fn().mockResolvedValue(undefined) });

it('displays HTTP, network-failure, and unfinished outcomes and paginates attempts', async () => {
	const fetch = api((url) => {
		if (!url.pathname.endsWith('/attempts')) return Response.json(delivery());
		const current = Number(url.searchParams.get('page') ?? 0);
		return Response.json(pageOf(current === 0 ? [
			attempt({ id: 'third', attemptNumber: 3, completedAt: null, responseStatus: null }),
			attempt({ id: 'second', attemptNumber: 2, responseStatus: null, errorMessage: 'Connection timed out' })
		] : [attempt({ responseStatus: 400 })], current, 20, 21));
	});
	await render(DeliveryDetails, props());
	await expect.element(page.getByText('Attempt 3', { exact: true })).toBeVisible();
	await expect.element(page.getByText('Connection timed out', { exact: true })).toBeVisible();
	await expect.element(page.getByText('Retry cycle starts at attempt', { exact: true })).toBeVisible();
	await page.getByRole('button', { name: 'Next', exact: true }).click();
	await expect.element(page.getByText('HTTP 400', { exact: true })).toBeVisible();
	await expect.element(page.getByText('Attempt 1', { exact: true })).toBeVisible();
	expect(fetch.mock.calls.some(([url]) => String(url).includes('/attempts?page=1'))).toBe(true);
});

it('requests replay only with CSRF and refreshes after acceptance', async () => {
	let replayed = false;
	const fetch = api((url, init) => {
		if (init.method === 'POST') { replayed = true; return Response.json({ id: DELIVERY, status: 'PENDING' }, { status: 202 }); }
		if (url.pathname.endsWith('/attempts')) return Response.json(pageOf([attempt()]));
		return Response.json(delivery(replayed ? { status: 'PENDING', retryCycleStartAttempt: 2 } : {}));
	});
	const options = props();
	await render(DeliveryDetails, options);
	await page.getByRole('button', { name: 'Replay failed delivery' }).click();
	await expect.element(page.getByText('Replay accepted. Refresh to see processing progress and new attempts.')).toBeVisible();
	await expect.element(page.getByText('pending', { exact: true })).toBeVisible();
	await expect.poll(() => options.onReplayed.mock.calls.length).toBe(1);
	mutation(fetch, `/api/deliveries/${DELIVERY}/replay`, 'POST', TENANT);
	await expect.element(page.getByRole('button', { name: 'Replay failed delivery' })).not.toBeInTheDocument();
});

it.each([
	[false, 'FAILED'], [true, 'PENDING'], [true, 'PROCESSING'], [true, 'RETRY_SCHEDULED'], [true, 'DELIVERED']
] as const)('offers no replay when permission is %s and status is %s', async (canReplay, status: DeliveryStatus) => {
	api((url) => url.pathname.endsWith('/attempts') ? Response.json(pageOf([])) : Response.json(delivery({ status })));
	await render(DeliveryDetails, { ...props(), canReplay });
	await expect.element(page.getByText(status.toLowerCase().replaceAll('_', ' '), { exact: true })).toBeVisible();
	await expect.element(page.getByRole('button', { name: 'Replay failed delivery' })).not.toBeInTheDocument();
});

it('refreshes a changed delivery after a replay conflict instead of pretending replay succeeded', async () => {
	let conflicted = false;
	api((url, init) => {
		if (init.method === 'POST') { conflicted = true; return new Response(null, { status: 409 }); }
		return url.pathname.endsWith('/attempts') ? Response.json(pageOf([])) : Response.json(delivery({ status: conflicted ? 'DELIVERED' : 'FAILED' }));
	});
	const options = props();
	await render(DeliveryDetails, options);
	await page.getByRole('button', { name: 'Replay failed delivery' }).click();
	await expect.element(page.getByText('Replay is only allowed for failed deliveries. The delivery status may have changed.')).toBeVisible();
	await expect.element(page.getByText('delivered', { exact: true })).toBeVisible();
	await expect.poll(() => options.onReplayed.mock.calls.length).toBe(1);
});

it('does not refresh as a success when replay is forbidden', async () => {
	api((url, init) => init.method === 'POST' ? new Response(null, { status: 403 })
		: url.pathname.endsWith('/attempts') ? Response.json(pageOf([])) : Response.json(delivery()));
	const options = props();
	await render(DeliveryDetails, options);
	await page.getByRole('button', { name: 'Replay failed delivery' }).click();
	await expect.element(page.getByText('Replay was not accepted. Check your session and workspace admin access.')).toBeVisible();
	expect(options.onReplayed).not.toHaveBeenCalled();
});

it('reports foreign or missing delivery details without showing attempt controls', async () => {
	api(() => new Response(null, { status: 404 }));
	await render(DeliveryDetails, props());
	await expect.element(page.getByText('Delivery not found in this workspace.')).toBeVisible();
	await expect.element(page.getByRole('button', { name: 'Replay failed delivery' })).not.toBeInTheDocument();
});
