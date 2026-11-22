import { afterEach, expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import { render } from 'vitest-browser-svelte';
import CreateSubscriptionForm from './CreateSubscriptionForm.svelte';
import { api, ENDPOINT, mutation, TENANT } from '#lib/testing/api.ts';

afterEach(() => vi.unstubAllGlobals());

it('subscribes the supplied endpoint in the selected workspace and refreshes its list', async () => {
	const onCreated = vi.fn().mockResolvedValue(undefined);
	const fetch = api(() => Response.json({ id: 'subscription-id', endpointId: ENDPOINT, eventType: 'order.created' }, { status: 201 }));
	await render(CreateSubscriptionForm, { tenantId: TENANT, endpointId: ENDPOINT, onCreated });
	await page.getByLabelText('Subscribe to an event type').fill('order.created');
	await page.getByRole('button', { name: 'Subscribe', exact: true }).click();
	await expect.element(page.getByText('Subscribed to order.created.', { exact: true })).toBeVisible();
	await expect.poll(() => onCreated.mock.calls.length).toBe(1);
	expect(JSON.parse(String(mutation(fetch, `/api/subscriptions/${ENDPOINT}`, 'POST', TENANT).body)))
		.toEqual({ eventType: 'order.created' });
	await expect.element(page.getByLabelText('Subscribe to an event type')).toHaveValue('');
});

it.each([
	[400, 'Enter an event type containing 1 to 100 characters.'],
	[401, 'Your session has expired. Please log in again.'],
	[403, 'Subscription was not authorized. Check your workspace access and try again.'],
	[404, 'Endpoint not found in this workspace.'],
	[409, 'This endpoint is already subscribed to that event type.']
] as const)('shows actionable feedback for HTTP %i', async (status, message) => {
	const onCreated = vi.fn().mockResolvedValue(undefined);
	api(() => new Response(null, { status }));
	await render(CreateSubscriptionForm, { tenantId: TENANT, endpointId: ENDPOINT, onCreated });
	await page.getByLabelText('Subscribe to an event type').fill('order.created');
	await page.getByRole('button', { name: 'Subscribe', exact: true }).click();
	await expect.element(page.getByText(message, { exact: true })).toBeVisible();
	expect(onCreated).not.toHaveBeenCalled();
});

it('rejects whitespace-only event types without making requests', async () => {
	const fetch = api(() => { throw new Error('No subscription expected'); });
	await render(CreateSubscriptionForm, { tenantId: TENANT, endpointId: ENDPOINT, onCreated: vi.fn().mockResolvedValue(undefined) });
	await page.getByLabelText('Subscribe to an event type').fill('   ');
	await page.getByRole('button', { name: 'Subscribe', exact: true }).click();
	await expect.element(page.getByText('Enter an event type.', { exact: true })).toBeVisible();
	expect(fetch).not.toHaveBeenCalled();
});
