import { expect, vi } from 'vitest';
import type { WebhookEndpoint } from '#lib/types/webhook-endpoint.ts';
import type { Delivery, DeliveryAttempt } from '#lib/types/delivery.ts';
import type { ApiKey } from '#lib/types/api-key.ts';

export const TENANT = '00000000-0000-0000-0000-000000000001';
export const OTHER_TENANT = '00000000-0000-0000-0000-000000000002';
export const ENDPOINT = '10000000-0000-0000-0000-000000000001';
export const DELIVERY = '20000000-0000-0000-0000-000000000001';
export const KEY = '30000000-0000-0000-0000-000000000001';
export const NOW = '2026-11-22T00:00:00Z';
export const CSRF = { headerName: 'X-CSRF-TOKEN', token: 'test-csrf' };

export function api(handler: (url: URL, init: RequestInit) => Response | Promise<Response>, csrfStatus = 200) {
	const mock = vi.fn<typeof fetch>(async (input, init = {}) => {
		const url = new URL(input instanceof Request ? input.url : String(input), 'http://notifyflow.test');
		if (url.pathname === '/api/auth/csrf') {
			return csrfStatus === 200 ? Response.json(CSRF) : new Response(null, { status: csrfStatus });
		}
		return handler(url, init);
	});
	vi.stubGlobal('fetch', mock);
	return mock;
}

export function mutation(mock: ReturnType<typeof api>, path: string, method: string, tenantId?: string) {
	const call = mock.mock.calls.find(([url, init]) => String(url) === path && init?.method === method);
	expect(call, `Expected ${method} ${path}`).toBeDefined();
	const init = call![1]!;
	expect(new Headers(init.headers).get(CSRF.headerName)).toBe(CSRF.token);
	if (tenantId) expect(new Headers(init.headers).get('X-Tenant-ID')).toBe(tenantId);
	return init;
}

export function pageOf<T>(content: T[], page = 0, size = 20, totalElements = content.length) {
	return { content, page, size, totalElements, totalPages: Math.ceil(totalElements / size) };
}

export function deferred<T>() {
	let resolve!: (value: T) => void;
	let reject!: (reason: unknown) => void;
	const promise = new Promise<T>((accept, fail) => { resolve = accept; reject = fail; });
	return { promise, resolve, reject };
}

export function endpoint(overrides: Partial<WebhookEndpoint> = {}): WebhookEndpoint {
	return { id: ENDPOINT, name: 'Orders receiver', url: 'https://example.com/webhook', enabled: true, createdAt: NOW, updatedAt: NOW, ...overrides };
}

export function delivery(overrides: Partial<Delivery> = {}): Delivery {
	return { id: DELIVERY, eventId: '40000000-0000-0000-0000-000000000001', endpointId: ENDPOINT,
		status: 'FAILED', createdAt: NOW, nextAttemptAt: null, retryCycleStartAttempt: 1, ...overrides };
}

export function attempt(overrides: Partial<DeliveryAttempt> = {}): DeliveryAttempt {
	return { id: '50000000-0000-0000-0000-000000000001', deliveryId: DELIVERY, attemptNumber: 1,
		startedAt: NOW, completedAt: NOW, responseStatus: 400, errorMessage: null, ...overrides };
}

export function key(overrides: Partial<ApiKey> = {}): ApiKey {
	return { id: KEY, name: 'Order producer', createdAt: NOW, revokedAt: null, ...overrides };
}
