import { afterEach, describe, expect, it, vi } from 'vitest';
import { getCsrfToken } from './csrf.ts';

afterEach(() => {
	vi.unstubAllGlobals();
});

describe('getCsrfToken', () => {
	it('returns the token and header name from a successful response', async () => {
		const token = { headerName: 'X-CSRF-TOKEN', token: 'test-token' };
		const fetchMock = vi.fn().mockResolvedValue(Response.json(token));
		vi.stubGlobal('fetch', fetchMock);

		await expect(getCsrfToken()).resolves.toEqual(token);
		expect(fetchMock).toHaveBeenCalledWith('/api/auth/csrf', { cache: 'no-store' });
	});

	it.each([401, 403, 500])('returns null for an HTTP %i response', async (status) => {
		vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('Request failed', { status })));

		await expect(getCsrfToken()).resolves.toBeNull();
	});

	it('propagates a network failure so the caller can display feedback', async () => {
		const failure = new TypeError('Failed to fetch');
		vi.stubGlobal('fetch', vi.fn().mockRejectedValue(failure));

		await expect(getCsrfToken()).rejects.toBe(failure);
	});

	it('propagates invalid JSON instead of treating it as a usable token', async () => {
		vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>not a token</html>')));
		await expect(getCsrfToken()).rejects.toBeInstanceOf(SyntaxError);
	});

	it('fetches again on each call instead of reusing a token across session changes', async () => {
		const beforeLogin = { headerName: 'X-CSRF-TOKEN', token: 'before-login' };
		const afterLogin = { headerName: 'X-CSRF-TOKEN', token: 'after-login' };
		const fetchMock = vi.fn()
			.mockResolvedValueOnce(Response.json(beforeLogin))
			.mockResolvedValueOnce(Response.json(afterLogin));
		vi.stubGlobal('fetch', fetchMock);

		await expect(getCsrfToken()).resolves.toEqual(beforeLogin);
		await expect(getCsrfToken()).resolves.toEqual(afterLogin);
		expect(fetchMock).toHaveBeenCalledTimes(2);
	});
});
