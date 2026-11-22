import { afterEach, expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import { render } from 'vitest-browser-svelte';
import CreateEndpointForm from './CreateEndpointForm.svelte';
import { api, deferred, endpoint, mutation, TENANT } from '#lib/testing/api.ts';

afterEach(() => vi.unstubAllGlobals());
async function fillEndpoint() {
	await page.getByLabelText('Endpoint name').fill('Orders receiver');
	await page.getByLabelText('Webhook URL').fill('https://example.com/webhook');
}

it('creates in the selected workspace, refreshes the parent, and hides the returned secret on demand', async () => {
	const onCreated = vi.fn().mockResolvedValue(undefined);
	const fetch = api(() => Response.json({ ...endpoint(), signingSecret: 'test-endpoint-secret' }, { status: 201 }));
	await render(CreateEndpointForm, { tenantId: TENANT, onCreated });
	await fillEndpoint();
	await page.getByRole('button', { name: '+ Create endpoint', exact: true }).click();
	await expect.element(page.getByText('test-endpoint-secret', { exact: true })).toBeVisible();
	await expect.poll(() => onCreated.mock.calls.length).toBe(1);
	const request = mutation(fetch, '/api/endpoints', 'POST', TENANT);
	expect(JSON.parse(String(request.body))).toEqual({ name: 'Orders receiver', url: 'https://example.com/webhook' });
	await expect.element(page.getByLabelText('Endpoint name')).toHaveValue('');
	await page.getByRole('button', { name: 'Hide secret' }).click();
	await expect.element(page.getByText('test-endpoint-secret', { exact: true })).not.toBeInTheDocument();
});

it.each([400, 403])('keeps the form and does not refresh on HTTP %i', async (status) => {
	const onCreated = vi.fn().mockResolvedValue(undefined);
	api(() => new Response(null, { status }));
	await render(CreateEndpointForm, { tenantId: TENANT, onCreated });
	await fillEndpoint();
	await page.getByRole('button', { name: '+ Create endpoint', exact: true }).click();
	await expect.element(page.getByText(status === 400
		? 'Check the endpoint name and URL. The URL must be allowed by the server.'
		: 'Unable to create endpoint. Please try again.')).toBeVisible();
	expect(onCreated).not.toHaveBeenCalled();
	await expect.element(page.getByLabelText('Endpoint name')).toHaveValue('Orders receiver');
});

it('blocks duplicate submissions while creation is pending', async () => {
	const pending = deferred<Response>();
	const fetch = api(() => pending.promise);
	await render(CreateEndpointForm, { tenantId: TENANT, onCreated: vi.fn().mockResolvedValue(undefined) });
	await fillEndpoint();
	await page.getByRole('button', { name: '+ Create endpoint', exact: true }).click();
	await expect.element(page.getByRole('button', { name: 'Creating endpoint...' })).toBeDisabled();
	pending.resolve(new Response(null, { status: 400 }));
	await expect.element(page.getByRole('button', { name: '+ Create endpoint', exact: true })).toBeEnabled();
	expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
});

it('does not create an endpoint without a CSRF token', async () => {
	const fetch = api(() => { throw new Error('No creation expected'); }, 403);
	await render(CreateEndpointForm, { tenantId: TENANT, onCreated: vi.fn().mockResolvedValue(undefined) });
	await fillEndpoint();
	await page.getByRole('button', { name: '+ Create endpoint', exact: true }).click();
	await expect.element(page.getByText('Unable to prepare endpoint creation. Please try again.')).toBeVisible();
	expect(fetch).toHaveBeenCalledTimes(1);
});
