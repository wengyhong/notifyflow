import { afterEach, expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import { render } from 'vitest-browser-svelte';
import RotateEndpointSecret from './RotateEndpointSecret.svelte';
import { api, ENDPOINT, mutation, TENANT } from '#lib/testing/api.ts';

afterEach(() => vi.unstubAllGlobals());
async function openRotation() {
	await render(RotateEndpointSecret, { tenantId: TENANT, endpointId: ENDPOINT });
	await page.getByText('Signing secret rotation', { exact: true }).click();
}

it('rotates with the scoped CSRF POST and lets the user hide the new secret', async () => {
	const fetch = api(() => Response.json({ id: ENDPOINT, signingSecret: 'rotated-test-secret', previousSigningSecretExpiresAt: '2026-11-22T01:00:00Z' }));
	await openRotation();
	await page.getByRole('button', { name: 'Rotate signing secret', exact: true }).click();
	await expect.element(page.getByText('rotated-test-secret', { exact: true })).toBeVisible();
	mutation(fetch, `/api/endpoints/${ENDPOINT}/signing-secret/rotate`, 'POST', TENANT);
	expect(document.querySelector('time')?.getAttribute('datetime')).toBe('2026-11-22T01:00:00Z');
	await page.getByRole('button', { name: 'Hide secret' }).click();
	await expect.element(page.getByText('rotated-test-secret', { exact: true })).not.toBeInTheDocument();
});

it('preserves an already returned secret when a repeated rotation conflicts', async () => {
	let calls = 0;
	api(() => ++calls === 1 ? Response.json({ id: ENDPOINT, signingSecret: 'keep-this-secret', previousSigningSecretExpiresAt: '2026-11-22T01:00:00Z' }) : new Response(null, { status: 409 }));
	await openRotation();
	await page.getByRole('button', { name: 'Rotate signing secret', exact: true }).click();
	await expect.element(page.getByText('keep-this-secret', { exact: true })).toBeVisible();
	await page.getByRole('button', { name: 'Rotate signing secret', exact: true }).click();
	await expect.element(page.getByText('A rotation overlap is already active. Wait until the previous secret expires before rotating again.')).toBeVisible();
	await expect.element(page.getByText('keep-this-secret', { exact: true })).toBeVisible();
});

it.each([
	[403, 'Rotation was not authorized. Check your workspace access and try again.'],
	[404, 'Endpoint not found in this workspace.']
] as const)('reports HTTP %i without displaying a secret', async (status, message) => {
	api(() => new Response(null, { status }));
	await openRotation();
	await page.getByRole('button', { name: 'Rotate signing secret', exact: true }).click();
	await expect.element(page.getByText(message, { exact: true })).toBeVisible();
	await expect.element(page.getByRole('button', { name: 'Hide secret' })).not.toBeInTheDocument();
});
