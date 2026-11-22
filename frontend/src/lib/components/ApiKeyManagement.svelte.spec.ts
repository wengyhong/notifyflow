import { afterEach, expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import { render } from 'vitest-browser-svelte';
import ApiKeyManagement from './ApiKeyManagement.svelte';
import { api, key, KEY, mutation, NOW, pageOf, TENANT } from '#lib/testing/api.ts';

afterEach(() => vi.unstubAllGlobals());

it('lists active/revoked metadata and paginates within the selected workspace', async () => {
	const fetch = api((url) => {
		const current = Number(url.searchParams.get('page') ?? 0);
		return Response.json(pageOf(current === 0
			? [key(), key({ id: 'revoked-id', name: 'Old producer', revokedAt: NOW })]
			: [key({ id: 'next-id', name: 'Next page producer' })], current, 20, 21));
	});
	await render(ApiKeyManagement, { tenantId: TENANT });
	await expect.element(page.getByText('Order producer', { exact: true })).toBeVisible();
	await expect.element(page.getByText('revoked', { exact: true })).toBeVisible();
	await expect.element(page.getByRole('button', { name: 'Revoke key', exact: true })).toBeVisible();
	await expect.element(page.getByRole('button', { name: 'Previous', exact: true })).toBeDisabled();
	await page.getByRole('button', { name: 'Next', exact: true }).click();
	await expect.element(page.getByText('Next page producer', { exact: true })).toBeVisible();
	await expect.element(page.getByRole('button', { name: 'Next', exact: true })).toBeDisabled();
	expect(fetch.mock.calls.every(([, init]) => new Headers(init?.headers).get('X-Tenant-ID') === TENANT)).toBe(true);
});

it('shows a new raw key once, refreshes metadata, and requires hiding it before another creation', async () => {
	let created = false;
	const fetch = api((_url, init) => {
		if (init.method === 'POST') {
			created = true;
			return Response.json({ id: KEY, name: 'Order producer', createdAt: NOW, rawKey: 'nf_test-only-raw-key' }, { status: 201 });
		}
		return Response.json(pageOf(created ? [key()] : []));
	});
	await render(ApiKeyManagement, { tenantId: TENANT });
	await page.getByLabelText('Create a producer key').fill('Order producer');
	await page.getByRole('button', { name: 'Create key', exact: true }).click();
	await expect.element(page.getByText('nf_test-only-raw-key', { exact: true })).toBeVisible();
	await expect.element(page.getByRole('button', { name: 'Create key', exact: true })).toBeDisabled();
	await expect.element(page.getByText('Order producer', { exact: true })).toBeVisible();
	expect(JSON.parse(String(mutation(fetch, '/api/key', 'POST', TENANT).body))).toEqual({ name: 'Order producer' });
	await page.getByRole('button', { name: 'Hide key' }).click();
	await expect.element(page.getByText('nf_test-only-raw-key', { exact: true })).not.toBeInTheDocument();
	await expect.element(page.getByRole('button', { name: 'Create key', exact: true })).toBeEnabled();
	await page.getByRole('button', { name: 'Refresh', exact: true }).click();
	await expect.element(page.getByText('Order producer', { exact: true })).toBeVisible();
	await expect.element(page.getByText('nf_test-only-raw-key', { exact: true })).not.toBeInTheDocument();
});

it('revokes the newly created key, clears its raw value, and reloads revoked metadata', async () => {
	let created = false;
	let revoked = false;
	const fetch = api((url, init) => {
		if (url.pathname.endsWith('/revoke')) { revoked = true; return new Response(null, { status: 204 }); }
		if (init.method === 'POST') {
			created = true;
			return Response.json({ id: KEY, name: 'Order producer', createdAt: NOW, rawKey: 'nf_revoke-this-test-key' }, { status: 201 });
		}
		return Response.json(pageOf(created ? [key({ revokedAt: revoked ? NOW : null })] : []));
	});
	await render(ApiKeyManagement, { tenantId: TENANT });
	await page.getByLabelText('Create a producer key').fill('Order producer');
	await page.getByRole('button', { name: 'Create key', exact: true }).click();
	await expect.element(page.getByText('nf_revoke-this-test-key', { exact: true })).toBeVisible();
	await page.getByRole('button', { name: 'Revoke key', exact: true }).click();
	await expect.element(page.getByText('revoked', { exact: true })).toBeVisible();
	await expect.element(page.getByText('nf_revoke-this-test-key', { exact: true })).not.toBeInTheDocument();
	await expect.element(page.getByRole('button', { name: 'Revoke key', exact: true })).not.toBeInTheDocument();
	mutation(fetch, `/api/key/${KEY}/revoke`, 'POST', TENANT);
});

it('shows a denied key list without displaying secret values', async () => {
	api(() => new Response(null, { status: 403 }));
	await render(ApiKeyManagement, { tenantId: TENANT });
	await expect.element(page.getByText('Unable to load keys. Check your workspace admin access and try again.')).toBeVisible();
	await expect.element(page.getByRole('button', { name: 'Hide key' })).not.toBeInTheDocument();
});

it('reports invalid creation without clearing the key name', async () => {
	api((_url, init) => init.method === 'POST' ? new Response(null, { status: 400 }) : Response.json(pageOf([])));
	await render(ApiKeyManagement, { tenantId: TENANT });
	await page.getByLabelText('Create a producer key').fill('Invalid name');
	await page.getByRole('button', { name: 'Create key', exact: true }).click();
	await expect.element(page.getByText('Enter a key name containing 1 to 100 characters.')).toBeVisible();
	await expect.element(page.getByLabelText('Create a producer key')).toHaveValue('Invalid name');
});

it('does not mark a key revoked when the server rejects revocation', async () => {
	api((url) => url.pathname.endsWith('/revoke') ? new Response(null, { status: 404 }) : Response.json(pageOf([key()])));
	await render(ApiKeyManagement, { tenantId: TENANT });
	await page.getByRole('button', { name: 'Revoke key', exact: true }).click();
	await expect.element(page.getByText('Key not found in this workspace. Refresh the list.')).toBeVisible();
	await expect.element(page.getByText('active', { exact: true })).toBeVisible();
	await expect.element(page.getByRole('button', { name: 'Revoke key', exact: true })).toBeEnabled();
});
