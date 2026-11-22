import { afterEach, expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import { render } from 'vitest-browser-svelte';
import { tick } from 'svelte';
import Dashboard from './+page.svelte';
import type { CurrentUser } from '#lib/types/current-user.ts';
import { api, deferred, endpoint, ENDPOINT, key, KEY, NOW, OTHER_TENANT, pageOf, TENANT } from '#lib/testing/api.ts';

vi.mock('$app/navigation', () => ({ goto: vi.fn() }));
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); });

function user(role: 'ADMIN' | 'VIEWER' = 'ADMIN'): CurrentUser {
	return { id: 'test-user', email: 'admin@example.com', workspaces: [
		{ tenantId: TENANT, name: 'Orders workspace', role },
		{ tenantId: OTHER_TENANT, name: 'Other workspace', role }
	] };
}

it('selects the first membership and scopes endpoint, delivery, and key reads to it', async () => {
	const fetch = api((url) => {
		if (url.pathname === '/api/auth/me') return Response.json(user());
		if (url.pathname === '/api/endpoints') return Response.json(pageOf([endpoint()]));
		return Response.json(pageOf([]));
	});
	await render(Dashboard);
	await expect.element(page.getByRole('heading', { level: 1, name: 'Orders workspace' })).toBeVisible();
	await expect.element(page.getByRole('heading', { name: 'Orders receiver', exact: true })).toBeVisible();
	await expect.element(page.getByLabelText('Current workspace')).toHaveValue(TENANT);
	await expect.element(page.getByRole('heading', { name: /Producer API keys/ })).toBeVisible();
	for (const path of ['/api/endpoints', '/api/deliveries', '/api/key']) {
		const call = fetch.mock.calls.find(([url]) => String(url).startsWith(path));
		expect(new Headers(call?.[1]?.headers).get('X-Tenant-ID')).toBe(TENANT);
	}
});

it('lets viewers read endpoints and history without mounting admin management controls', async () => {
	const fetch = api((url) => url.pathname === '/api/auth/me' ? Response.json(user('VIEWER'))
		: Response.json(pageOf(url.pathname === '/api/endpoints' ? [endpoint()] : [])));
	await render(Dashboard);
	await expect.element(page.getByRole('heading', { name: 'Orders receiver', exact: true })).toBeVisible();
	await expect.element(page.getByRole('heading', { name: /Delivery history/ })).toBeVisible();
	await expect.element(page.getByRole('button', { name: 'Disable endpoint' })).not.toBeInTheDocument();
	await expect.element(page.getByRole('button', { name: '+ Create endpoint', exact: true })).not.toBeInTheDocument();
	await expect.element(page.getByRole('heading', { name: /Producer API keys/ })).not.toBeInTheDocument();
	expect(fetch.mock.calls.some(([url]) => String(url).startsWith('/api/key'))).toBe(false);
});

it('clears both newly created endpoint secrets and raw producer keys when switching workspace', async () => {
	let endpointCreated = false;
	let keyCreated = false;
	api((url, init) => {
		if (url.pathname === '/api/auth/me') return Response.json(user());
		const other = new Headers(init.headers).get('X-Tenant-ID') === OTHER_TENANT;
		if (url.pathname === '/api/endpoints') {
			if (init.method === 'POST') {
				endpointCreated = true;
				return Response.json({ ...endpoint({ name: 'Created receiver' }), signingSecret: 'workspace-a-signing-secret' }, { status: 201 });
			}
			return Response.json(pageOf([other ? endpoint({ id: '10000000-0000-0000-0000-000000000002', name: 'Other receiver' })
				: endpoint({ name: endpointCreated ? 'Created receiver' : 'Orders receiver' })]));
		}
		if (url.pathname === '/api/key') {
			if (init.method === 'POST') {
				keyCreated = true;
				return Response.json({ id: KEY, name: 'Order producer', createdAt: NOW, rawKey: 'nf_workspace-a-raw-key' }, { status: 201 });
			}
			return Response.json(pageOf(!other && keyCreated ? [key()] : []));
		}
		return Response.json(pageOf([]));
	});
	await render(Dashboard);
	await page.getByLabelText('Endpoint name').fill('Created receiver');
	await page.getByLabelText('Webhook URL').fill('https://example.com/webhook');
	await page.getByRole('button', { name: '+ Create endpoint', exact: true }).click();
	await expect.element(page.getByText('workspace-a-signing-secret', { exact: true })).toBeVisible();
	await page.getByLabelText('Create a producer key').fill('Order producer');
	await page.getByRole('button', { name: 'Create key', exact: true }).click();
	await expect.element(page.getByText('nf_workspace-a-raw-key', { exact: true })).toBeVisible();
	await page.getByLabelText('Current workspace').selectOptions(OTHER_TENANT);
	await expect.element(page.getByRole('heading', { level: 1, name: 'Other workspace' })).toBeVisible();
	await expect.element(page.getByRole('heading', { name: 'Other receiver', exact: true })).toBeVisible();
	await expect.element(page.getByText('workspace-a-signing-secret', { exact: true })).not.toBeInTheDocument();
	await expect.element(page.getByText('nf_workspace-a-raw-key', { exact: true })).not.toBeInTheDocument();
	await expect.element(page.getByLabelText('Endpoint name')).toHaveValue('');
	await expect.element(page.getByLabelText('Create a producer key')).toHaveValue('');
});

it('does not replace a new workspace endpoint list with a delayed old response', async () => {
	const old = deferred<Response>();
	api((url, init) => {
		if (url.pathname === '/api/auth/me') return Response.json(user());
		if (url.pathname === '/api/endpoints') {
			return new Headers(init.headers).get('X-Tenant-ID') === TENANT ? old.promise
				: Response.json(pageOf([endpoint({ id: 'other-endpoint', name: 'Other receiver' })]));
		}
		return Response.json(pageOf([]));
	});
	await render(Dashboard);
	await expect.element(page.getByLabelText('Current workspace')).toHaveValue(TENANT);
	await page.getByLabelText('Current workspace').selectOptions(OTHER_TENANT);
	await expect.element(page.getByRole('heading', { name: 'Other receiver', exact: true })).toBeVisible();
	old.resolve(Response.json(pageOf([endpoint({ id: ENDPOINT, name: 'Orders receiver' })])));
	await old.promise;
	await tick();
	await expect.element(page.getByRole('heading', { name: 'Other receiver', exact: true })).toBeVisible();
	await expect.element(page.getByRole('heading', { name: 'Orders receiver', exact: true })).not.toBeInTheDocument();
});

it('shows a login link when the session is missing without querying workspace resources', async () => {
	const fetch = api(() => new Response(null, { status: 401 }));
	await render(Dashboard);
	await expect.element(page.getByText('Please log in to view your dashboard.')).toBeVisible();
	await expect.element(page.getByRole('link', { name: 'Go to login' })).toBeVisible();
	expect(fetch).toHaveBeenCalledTimes(1);
});

it('handles an account without memberships without making unscoped resource requests', async () => {
	const fetch = api(() => Response.json({ ...user(), workspaces: [] }));
	await render(Dashboard);
	await expect.element(page.getByText("You don't belong to any workspaces yet.")).toBeVisible();
	expect(fetch).toHaveBeenCalledTimes(1);
});
