import { afterEach, describe, expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import { render } from 'vitest-browser-svelte';
import { goto } from '$app/navigation';
import Login from './+page.svelte';
import { api, deferred, mutation } from '#lib/testing/api.ts';

vi.mock('$app/navigation', () => ({ goto: vi.fn() }));
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); });

async function fillLogin() {
	await page.getByLabelText('Email address').fill('admin@example.com');
	await page.getByLabelText('Password', { exact: true }).fill('remember this password');
}

describe('login', () => {
	it('posts form-encoded credentials with CSRF, clears fields, and navigates after 204', async () => {
		const fetch = api(() => new Response(null, { status: 204 }));
		await render(Login);
		await fillLogin();
		await page.getByRole('button', { name: 'Sign in to your workspace' }).click();
		await expect.poll(() => vi.mocked(goto).mock.calls).toEqual([['/dashboard']]);
		const request = mutation(fetch, '/api/auth/login', 'POST');
		expect(request.body).toBeInstanceOf(URLSearchParams);
		expect(String(request.body)).toBe('email=admin%40example.com&password=remember+this+password');
		await expect.element(page.getByLabelText('Password', { exact: true })).toHaveValue('');
	});

	it('shows rejected credentials without navigating', async () => {
		api(() => new Response(null, { status: 401 }));
		await render(Login);
		await fillLogin();
		await page.getByRole('button', { name: 'Sign in to your workspace' }).click();
		await expect.element(page.getByText('Login failed', { exact: true })).toBeVisible();
		expect(goto).not.toHaveBeenCalled();
		await expect.element(page.getByRole('button', { name: 'Sign in to your workspace' })).toBeEnabled();
	});

	it('does not post credentials when CSRF preparation fails', async () => {
		const fetch = api(() => { throw new Error('Login must not be requested'); }, 403);
		await render(Login);
		await fillLogin();
		await page.getByRole('button', { name: 'Sign in to your workspace' }).click();
		await expect.element(page.getByText('Unable to prepare login. Please try again.')).toBeVisible();
		expect(fetch).toHaveBeenCalledTimes(1);
		expect(goto).not.toHaveBeenCalled();
	});

	it('shows network feedback and allows another submission', async () => {
		api(() => { throw new TypeError('Offline'); });
		await render(Login);
		await fillLogin();
		await page.getByRole('button', { name: 'Sign in to your workspace' }).click();
		await expect.element(page.getByText('Unable to connect. Please try again.')).toBeVisible();
		await expect.element(page.getByRole('button', { name: 'Sign in to your workspace' })).toBeEnabled();
	});

	it('disables submission while login is pending', async () => {
		const pending = deferred<Response>();
		const fetch = api(() => pending.promise);
		await render(Login);
		await fillLogin();
		await page.getByRole('button', { name: 'Sign in to your workspace' }).click();
		await expect.element(page.getByRole('button', { name: 'Signing in...' })).toBeDisabled();
		pending.resolve(new Response(null, { status: 401 }));
		await expect.element(page.getByText('Login failed', { exact: true })).toBeVisible();
		expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
	});
});
