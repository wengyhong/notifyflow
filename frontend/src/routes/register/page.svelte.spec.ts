import { afterEach, describe, expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import { render } from 'vitest-browser-svelte';
import Register from './+page.svelte';
import { api, mutation } from '#lib/testing/api.ts';

afterEach(() => vi.unstubAllGlobals());
async function fillRegistration(password = 'a memorable test password') {
	await page.getByLabelText('Email address').fill('new@example.com');
	await page.getByLabelText('Workspace name').fill('Orders team');
	await page.getByLabelText('Password', { exact: true }).fill(password);
}

describe('registration', () => {
	it('posts JSON with the workspace name and clears credentials after creation', async () => {
		const fetch = api(() => Response.json({ id: 'user-id' }, { status: 201 }));
		await render(Register);
		await fillRegistration();
		await page.getByRole('button', { name: 'Create your workspace' }).click();
		await expect.element(page.getByText('Account and workspace created. You can now log in.')).toBeVisible();
		const request = mutation(fetch, '/api/auth/register', 'POST');
		expect(JSON.parse(String(request.body))).toEqual({ email: 'new@example.com', tenantName: 'Orders team', password: 'a memorable test password' });
		expect(new Headers(request.headers).get('Content-Type')).toBe('application/json');
		await expect.element(page.getByLabelText('Password', { exact: true })).toHaveValue('');
	});

	it.each([400, 409, 500])('shows server feedback for HTTP %i', async (status) => {
		api(() => new Response(null, { status }));
		await render(Register);
		await fillRegistration();
		await page.getByRole('button', { name: 'Create your workspace' }).click();
		const message = status === 409 ? 'This email is already registered. Log in or use another email.'
			: status === 400 ? 'Check your email, workspace name, and password requirements.' : 'Unable to register. Please try again.';
		await expect.element(page.getByText(message)).toBeVisible();
		await expect.element(page.getByRole('button', { name: 'Create your workspace' })).toBeEnabled();
	});

	it.each(['too short', '🔐'.repeat(19)])('rejects invalid password length or UTF-8 size before making requests', async (password) => {
		const fetch = api(() => { throw new Error('No registration expected'); });
		await render(Register);
		await fillRegistration(password);
		await page.getByRole('button', { name: 'Create your workspace' }).click();
		await expect.element(page.getByText('Password must contain at least 15 characters and use at most 72 UTF-8 bytes.')).toBeVisible();
		expect(fetch).not.toHaveBeenCalled();
	});

	it('stops registration when the CSRF endpoint is unavailable', async () => {
		const fetch = api(() => { throw new Error('No registration expected'); }, 500);
		await render(Register);
		await fillRegistration();
		await page.getByRole('button', { name: 'Create your workspace' }).click();
		await expect.element(page.getByText('Unable to prepare registration. Please try again.')).toBeVisible();
		expect(fetch).toHaveBeenCalledTimes(1);
	});
});
