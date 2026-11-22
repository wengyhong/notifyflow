import { afterEach, expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import { render } from 'vitest-browser-svelte';
import { goto } from '$app/navigation';
import LogoutButton from './LogoutButton.svelte';
import { api, mutation } from '#lib/testing/api.ts';

vi.mock('$app/navigation', () => ({ goto: vi.fn() }));
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); });

it('logs out with CSRF and opens the login page after 204', async () => {
	const fetch = api(() => new Response(null, { status: 204 }));
	await render(LogoutButton);
	await page.getByRole('button', { name: 'Log out' }).click();
	await expect.poll(() => vi.mocked(goto).mock.calls).toEqual([['/login']]);
	mutation(fetch, '/api/auth/logout', 'POST');
});

it('keeps the current page when logout is rejected', async () => {
	api(() => new Response(null, { status: 403 }));
	await render(LogoutButton);
	await page.getByRole('button', { name: 'Log out' }).click();
	await expect.element(page.getByText('Unable to log out. Please try again.')).toBeVisible();
	expect(goto).not.toHaveBeenCalled();
});

it('reports a network failure and restores the logout button', async () => {
	api(() => { throw new TypeError('Offline'); });
	await render(LogoutButton);
	await page.getByRole('button', { name: 'Log out' }).click();
	await expect.element(page.getByText('Unable to connect. Please try again.')).toBeVisible();
	await expect.element(page.getByRole('button', { name: 'Log out' })).toBeEnabled();
});
