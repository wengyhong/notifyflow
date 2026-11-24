import { afterEach, expect, it, vi } from 'vitest';
import { page, userEvent } from 'vitest/browser';
import { render } from 'vitest-browser-svelte';
import { tick } from 'svelte';
import Dashboard from './+page.svelte';
import type { CurrentUser } from '#lib/types/current-user.ts';
import { api, deferred, endpoint, ENDPOINT, key, KEY, NOW, mutation, OTHER_TENANT, pageOf, TENANT } from '#lib/testing/api.ts';

vi.mock('$app/navigation', () => ({ goto: vi.fn() }));
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

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
	await expect.element(page.getByRole('table', { name: 'Webhook endpoints' }).getByText('Orders receiver', { exact: true })).toBeVisible();
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
	await expect.element(page.getByRole('table', { name: 'Webhook endpoints' }).getByText('Orders receiver', { exact: true })).toBeVisible();
	await expect.element(page.getByRole('heading', { name: /Delivery history/ })).toBeVisible();
	await expect.element(page.getByRole('button', { name: 'Disable endpoint' })).not.toBeInTheDocument();
	await expect.element(page.getByRole('button', { name: '+ Create endpoint', exact: true })).not.toBeInTheDocument();
	await expect.element(page.getByRole('heading', { name: /Producer API keys/ })).not.toBeInTheDocument();
	await expect.element(page.getByRole('button', { name: 'Send test event', exact: true })).not.toBeInTheDocument();
	expect(fetch.mock.calls.some(([url]) => String(url).startsWith('/api/key'))).toBe(false);
});

it('opens test publishing in a panel, refreshes history after acceptance, and navigates to it', async () => {
	let submitted = false;
	const fetch = api((url, init) => {
		if (url.pathname === '/api/auth/me') return Response.json(user());
		if (url.pathname === '/api/events') { submitted = true; return Response.json({ id: 'demo-event', type: 'hosted.receiver.demo', createdAt: NOW }, { status: 202 }); }
		return Response.json(pageOf([]));
	});
	await render(Dashboard);
	await page.getByRole('button', { name: 'Send test event', exact: true }).click();
	const panel = page.getByRole('dialog', { name: 'Send test event' });
	await expect.element(panel).toBeVisible();
	await panel.getByLabelText('Event type', { exact: true }).fill('hosted.receiver.demo');
	await panel.getByRole('button', { name: 'Send event', exact: true }).click();
	await expect.element(panel.getByText('demo-event', { exact: true })).toBeVisible();
	expect(submitted).toBe(true);
	mutation(fetch, '/api/events', 'POST', TENANT);
	await expect.poll(() => fetch.mock.calls.filter(([url]) => String(url).startsWith('/api/deliveries?')).length).toBe(2);
	await panel.getByRole('button', { name: 'View delivery history' }).click();
	await expect.element(page.getByRole('dialog')).not.toBeInTheDocument();
	await expect.element(page.getByRole('heading', { name: /Delivery history/ })).toHaveFocus();
});

it('discards test-event form state when the workspace changes', async () => {
	api(url => url.pathname === '/api/auth/me' ? Response.json(user()) : Response.json(pageOf([])));
	await render(Dashboard);
	await page.getByRole('button', { name: 'Send test event', exact: true }).click();
	await page.getByLabelText('Event type', { exact: true }).fill('private.old-workspace');
	const select = document.querySelector<HTMLSelectElement>('#workspace')!;
	select.value = OTHER_TENANT;
	select.dispatchEvent(new Event('change', { bubbles: true }));
	await expect.element(page.getByRole('dialog')).not.toBeInTheDocument();
	await page.getByRole('button', { name: 'Send test event', exact: true }).click();
	await expect.element(page.getByLabelText('Event type', { exact: true })).toHaveValue('');
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
	await page.getByRole('button', { name: 'New endpoint', exact: true }).click();
	await page.getByLabelText('Endpoint name').fill('Created receiver');
	await page.getByLabelText('Webhook URL').fill('https://example.com/webhook');
	await page.getByRole('button', { name: '+ Create endpoint', exact: true }).click();
	await expect.element(page.getByText('workspace-a-signing-secret', { exact: true })).toBeVisible();
	await page.getByRole('button', { name: 'Close endpoint panel' }).click();
	await page.getByLabelText('Create a producer key').fill('Order producer');
	await page.getByRole('button', { name: 'Create key', exact: true }).click();
	await expect.element(page.getByText('nf_workspace-a-raw-key', { exact: true })).toBeVisible();
	await page.getByLabelText('Current workspace').selectOptions(OTHER_TENANT);
	await expect.element(page.getByRole('heading', { level: 1, name: 'Other workspace' })).toBeVisible();
	await expect.element(page.getByRole('table', { name: 'Webhook endpoints' }).getByText('Other receiver', { exact: true })).toBeVisible();
	await expect.element(page.getByText('workspace-a-signing-secret', { exact: true })).not.toBeInTheDocument();
	await expect.element(page.getByText('nf_workspace-a-raw-key', { exact: true })).not.toBeInTheDocument();
	await expect.element(page.getByLabelText('Endpoint name')).not.toBeInTheDocument();
	await page.getByRole('button', { name: 'New endpoint', exact: true }).click();
	await expect.element(page.getByLabelText('Endpoint name')).toHaveValue('');
	await page.getByRole('button', { name: 'Close endpoint panel' }).click();
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
	await expect.element(page.getByRole('table', { name: 'Webhook endpoints' }).getByText('Other receiver', { exact: true })).toBeVisible();
	old.resolve(Response.json(pageOf([endpoint({ id: ENDPOINT, name: 'Orders receiver' })])));
	await old.promise;
	await tick();
	await expect.element(page.getByRole('table', { name: 'Webhook endpoints' }).getByText('Other receiver', { exact: true })).toBeVisible();
	await expect.element(page.getByRole('table', { name: 'Webhook endpoints' }).getByText('Orders receiver', { exact: true })).not.toBeInTheDocument();
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

it('shows compact rows and loads only the selected endpoint subscriptions without accordion clicks', async () => {
	const fetch = api(url => {
		if (url.pathname === '/api/auth/me') return Response.json(user());
		if (url.pathname === '/api/endpoints') return Response.json(pageOf([endpoint(), endpoint({ id: 'second-id', name: 'Billing receiver' })]));
		if (url.pathname.startsWith('/api/subscriptions/')) return Response.json([{ id: 'subscription', eventType: 'order.created' }]);
		return Response.json(pageOf([]));
	});
	await render(Dashboard);
	await expect.element(page.getByRole('table', { name: 'Webhook endpoints' })).toBeVisible();
	await expect.element(page.getByLabelText('Endpoint name')).not.toBeInTheDocument();
	expect(fetch.mock.calls.some(([url]) => String(url).startsWith('/api/subscriptions/'))).toBe(false);
	await page.getByRole('button', { name: 'Manage Orders receiver' }).click();
	await expect.element(page.getByRole('dialog', { name: 'Orders receiver' })).toBeVisible();
	await expect.element(page.getByLabelText('Destination URL')).toHaveValue('https://example.com/webhook');
	await expect.element(page.getByLabelText('Endpoint ID', { exact: true })).toHaveValue(ENDPOINT);
	await expect.element(page.getByText('order.created', { exact: true })).toBeVisible();
	await expect.element(page.getByRole('button', { name: 'Rotate signing secret', exact: true })).toBeVisible();
	expect(fetch.mock.calls.filter(([url]) => String(url).startsWith('/api/subscriptions/')).map(([url]) => String(url))).toEqual([`/api/subscriptions/${ENDPOINT}`]);
	await page.getByRole('button', { name: 'Close endpoint panel' }).click();
	await expect.element(page.getByRole('dialog')).not.toBeInTheDocument();
});

it('lets viewers open read-only endpoint details without administrator actions', async () => {
	api(url => {
		if (url.pathname === '/api/auth/me') return Response.json(user('VIEWER'));
		if (url.pathname.startsWith('/api/subscriptions/')) return Response.json([]);
		return Response.json(pageOf(url.pathname === '/api/endpoints' ? [endpoint()] : []));
	});
	await render(Dashboard);
	await page.getByRole('button', { name: 'Manage Orders receiver' }).click();
	await expect.element(page.getByText('No event types subscribed yet.')).toBeVisible();
	for (const name of ['New endpoint', 'Disable endpoint', 'Rotate signing secret', 'Subscribe']) {
		await expect.element(page.getByRole('button', { name, exact: true })).not.toBeInTheDocument();
	}
});

it('refreshes enabled state in the row and open panel after a scoped update', async () => {
	let enabled = true;
	const fetch = api((url, init) => {
		if (url.pathname === '/api/auth/me') return Response.json(user());
		if (url.pathname === `/api/endpoints/${ENDPOINT}/enabled`) {
			enabled = false;
			return Response.json(endpoint({ enabled }));
		}
		if (url.pathname.startsWith('/api/subscriptions/')) return Response.json([]);
		return Response.json(pageOf(url.pathname === '/api/endpoints' ? [endpoint({ enabled })] : []));
	});
	await render(Dashboard);
	await page.getByRole('button', { name: 'Manage Orders receiver' }).click();
	await page.getByRole('button', { name: 'Disable endpoint' }).click();
	await expect.element(page.getByRole('button', { name: 'Enable endpoint' })).toBeVisible();
	await expect.element(page.getByRole('dialog')).toBeVisible();
	mutation(fetch, `/api/endpoints/${ENDPOINT}/enabled`, 'PATCH', TENANT);
	await page.getByRole('button', { name: 'Close endpoint panel' }).click();
	await expect.element(page.getByRole('table', { name: 'Webhook endpoints' }).getByText('disabled', { exact: true })).toBeVisible();
});

it('clears a rotated secret when closing the panel and switching endpoints', async () => {
	api(url => {
		if (url.pathname === '/api/auth/me') return Response.json(user());
		if (url.pathname.includes('/signing-secret/rotate')) return Response.json({ id: ENDPOINT, signingSecret: 'temporary-rotation-secret', previousSigningSecretExpiresAt: NOW });
		if (url.pathname.startsWith('/api/subscriptions/')) return Response.json([]);
		return Response.json(pageOf(url.pathname === '/api/endpoints' ? [endpoint(), endpoint({ id: 'second-id', name: 'Billing receiver' })] : []));
	});
	await render(Dashboard);
	await page.getByRole('button', { name: 'Manage Orders receiver' }).click();
	await page.getByRole('button', { name: 'Rotate signing secret', exact: true }).click();
	await expect.element(page.getByText('temporary-rotation-secret', { exact: true })).toBeVisible();
	await page.getByRole('button', { name: 'Close endpoint panel' }).click();
	await page.getByRole('button', { name: 'Manage Billing receiver' }).click();
	await expect.element(page.getByText('temporary-rotation-secret', { exact: true })).not.toBeInTheDocument();
	await expect.element(page.getByLabelText('Endpoint ID', { exact: true })).toHaveValue('second-id');
});

it('closes the creation panel and clears its secret if workspace selection changes', async () => {
	api((url, init) => {
		if (url.pathname === '/api/auth/me') return Response.json(user());
		if (url.pathname === '/api/endpoints' && init.method === 'POST') return Response.json({ ...endpoint(), signingSecret: 'created-workspace-secret' }, { status: 201 });
		return Response.json(pageOf([]));
	});
	await render(Dashboard);
	await page.getByRole('button', { name: 'New endpoint', exact: true }).click();
	await page.getByLabelText('Endpoint name').fill('New receiver');
	await page.getByLabelText('Webhook URL').fill('https://example.com/webhook');
	await page.getByRole('button', { name: '+ Create endpoint', exact: true }).click();
	await expect.element(page.getByText('created-workspace-secret', { exact: true })).toBeVisible();
	// Model an external workspace update while the modal owns keyboard focus.
	const select = document.querySelector<HTMLSelectElement>('#workspace')!;
	select.value = OTHER_TENANT;
	select.dispatchEvent(new Event('change', { bubbles: true }));
	await expect.element(page.getByRole('heading', { level: 1, name: 'Other workspace' })).toBeVisible();
	await expect.element(page.getByRole('dialog')).not.toBeInTheDocument();
	await expect.element(page.getByText('created-workspace-secret', { exact: true })).not.toBeInTheDocument();
});

it('copies the full endpoint URL and ID and reports clipboard failures', async () => {
	api(url => {
		if (url.pathname === '/api/auth/me') return Response.json(user());
		if (url.pathname.startsWith('/api/subscriptions/')) return Response.json([]);
		return Response.json(pageOf(url.pathname === '/api/endpoints' ? [endpoint()] : []));
	});
	const copy = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue();
	await render(Dashboard);
	await page.getByRole('button', { name: 'Manage Orders receiver' }).click();
	await page.getByRole('button', { name: 'Copy URL', exact: true }).click();
	await expect.element(page.getByText('URL copied.', { exact: true })).toBeVisible();
	expect(copy).toHaveBeenLastCalledWith('https://example.com/webhook');
	await page.getByRole('button', { name: 'Copy ID', exact: true }).click();
	await expect.element(page.getByText('Endpoint ID copied.', { exact: true })).toBeVisible();
	expect(copy).toHaveBeenLastCalledWith(ENDPOINT);
	copy.mockRejectedValueOnce(new Error('Permission denied'));
	await page.getByRole('button', { name: 'Copy URL', exact: true }).click();
	await expect.element(page.getByText('Unable to copy. Select and copy the value manually.')).toBeVisible();
});

it('dismisses the panel with Escape and returns focus to its Manage button', async () => {
	api(url => {
		if (url.pathname === '/api/auth/me') return Response.json(user());
		if (url.pathname.startsWith('/api/subscriptions/')) return Response.json([]);
		return Response.json(pageOf(url.pathname === '/api/endpoints' ? [endpoint()] : []));
	});
	await render(Dashboard);
	const manage = page.getByRole('button', { name: 'Manage Orders receiver' });
	await manage.click();
	await expect.element(page.getByRole('dialog')).toBeVisible();
	await userEvent.keyboard('{Escape}');
	await expect.element(page.getByRole('dialog')).not.toBeInTheDocument();
	await expect.element(manage).toHaveFocus();
});

it('keeps endpoint pagination and workspace headers when browsing compact rows', async () => {
	const fetch = api(url => {
		if (url.pathname === '/api/auth/me') return Response.json(user());
		if (url.pathname === '/api/endpoints') {
			const secondPage = url.searchParams.get('page') === '1';
			return Response.json(pageOf([endpoint({ name: secondPage ? 'Next receiver' : 'Orders receiver' })], secondPage ? 1 : 0, 20, 21));
		}
		return Response.json(pageOf([]));
	});
	await render(Dashboard);
	await page.getByRole('navigation', { name: 'Endpoint pagination' }).getByRole('button', { name: 'Next', exact: true }).click();
	await expect.element(page.getByRole('table', { name: 'Webhook endpoints' }).getByText('Next receiver', { exact: true })).toBeVisible();
	await expect.element(page.getByRole('navigation', { name: 'Endpoint pagination' }).getByText('Page 2 of 2')).toBeVisible();
	const call = fetch.mock.calls.find(([url]) => String(url).startsWith('/api/endpoints?page=1'));
	expect(new Headers(call?.[1]?.headers).get('X-Tenant-ID')).toBe(TENANT);
});
