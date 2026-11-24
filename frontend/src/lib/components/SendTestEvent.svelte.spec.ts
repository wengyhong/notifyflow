import { afterEach, expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import { render } from 'vitest-browser-svelte';
import { tick } from 'svelte';
import SendTestEvent from './SendTestEvent.svelte';
import { api, deferred, mutation, NOW, OTHER_TENANT, TENANT } from '#lib/testing/api.ts';

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
const event = { id: 'accepted-event-id', type: 'hosted.receiver.demo', createdAt: NOW };
const props = () => ({ tenantId: TENANT, onAccepted: vi.fn(), onViewHistory: vi.fn() });
const currentKey = () => document.querySelector<HTMLInputElement>('#test-event-key')!.value;

async function fill() {
    await page.getByLabelText('Event type', { exact: true }).fill(event.type);
    await page.getByLabelText('JSON payload', { exact: true }).fill('{ "orderId": 150 }');
}

it('sends a session-authenticated, tenant-scoped CSRF request and displays acceptance', async () => {
    const fetch = api(() => Response.json(event, { status: 202 }));
    const options = props();
    await render(SendTestEvent, options);
    await fill();
    const key = currentKey();
    expect(key).toMatch(/^[0-9a-f-]{36}$/);
    await page.getByRole('button', { name: 'Send event', exact: true }).click();
    await expect.element(page.getByRole('heading', { name: 'Event accepted for processing' })).toBeVisible();
    await expect.element(page.getByText(event.id, { exact: true })).toBeVisible();
    const request = mutation(fetch, '/api/events', 'POST', TENANT);
    expect(new Headers(request.headers).get('Idempotency-Key')).toBe(key);
    expect(new Headers(request.headers).get('Authorization')).toBeNull();
    expect(JSON.parse(String(request.body))).toEqual({ type: event.type, payload: { orderId: 150 } });
    expect(options.onAccepted).toHaveBeenCalledOnce();
    await page.getByRole('button', { name: 'View delivery history' }).click();
    expect(options.onViewHistory).toHaveBeenCalledOnce();
});

it.each([
    ['{ invalid', 'Payload must be valid JSON. Check commas, quotes, and brackets.'],
    ['null', 'Payload cannot be null. Enter a JSON object, array, string, number, or boolean.'],
])('rejects payload %s before making any request', async (payload, message) => {
    const fetch = api(() => Response.json(event, { status: 202 }));
    await render(SendTestEvent, props());
    await fill();
    await page.getByLabelText('JSON payload', { exact: true }).fill(payload);
    await page.getByRole('button', { name: 'Send event', exact: true }).click();
    await expect.element(page.getByText(message, { exact: true })).toBeVisible();
    expect(fetch).not.toHaveBeenCalled();
});

it.each(['[]', 'false', '0', '"sample"'])('accepts a non-null JSON payload %s', async payload => {
    const fetch = api(() => Response.json(event, { status: 202 }));
    await render(SendTestEvent, props());
    await fill();
    await page.getByLabelText('JSON payload', { exact: true }).fill(payload);
    await page.getByRole('button', { name: 'Send event', exact: true }).click();
    await expect.element(page.getByText(event.id, { exact: true })).toBeVisible();
    expect(JSON.parse(String(mutation(fetch, '/api/events', 'POST', TENANT).body)).payload).toEqual(JSON.parse(payload));
});

it('retains the same key for repeated sends and generates a new key only on request', async () => {
    const fetch = api(() => Response.json(event, { status: 202 }));
    await render(SendTestEvent, props());
    await fill();
    const key = currentKey();
    for (let i = 0; i < 2; i++) {
        await page.getByRole('button', { name: 'Send event', exact: true }).click();
        await expect.element(page.getByText(event.id, { exact: true })).toBeVisible();
        await expect.element(page.getByRole('button', { name: 'Send event', exact: true })).toBeEnabled();
    }
    const posts = fetch.mock.calls.filter(([url]) => String(url) === '/api/events');
    expect(posts).toHaveLength(2);
    expect(posts.map(([, init]) => new Headers(init?.headers).get('Idempotency-Key'))).toEqual([key, key]);
    await page.getByRole('button', { name: 'Generate new idempotency key' }).click();
    expect(currentKey()).not.toBe(key);
    await expect.element(page.getByText(event.id, { exact: true })).not.toBeInTheDocument();
});

it('preserves large numeric literals in the validated JSON payload', async () => {
    const fetch = api(() => Response.json(event, { status: 202 }));
    await render(SendTestEvent, props());
    await fill();
    const payload = '{ "orderId": 9007199254740993, "amount": 1.234567890123456789 }';
    await page.getByLabelText('JSON payload', { exact: true }).fill(payload);
    await page.getByRole('button', { name: 'Send event', exact: true }).click();
    await expect.element(page.getByText(event.id, { exact: true })).toBeVisible();
    expect(String(mutation(fetch, '/api/events', 'POST', TENANT).body)).toBe(`{"type":"${event.type}","payload":${payload}}`);
});

it.each(['type', 'key'])('rejects a whitespace-only %s without making a request', async field => {
    const fetch = api(() => Response.json(event, { status: 202 }));
    await render(SendTestEvent, props());
    await fill();
    await page.getByLabelText(field === 'type' ? 'Event type' : 'Idempotency key', { exact: true }).fill('   ');
    await page.getByRole('button', { name: 'Send event', exact: true }).click();
    await expect.element(page.getByText('Enter an event type and an idempotency key within the displayed limits.')).toBeVisible();
    expect(fetch).not.toHaveBeenCalled();
});

it.each([
    [400, 'Check the event type, JSON payload, and idempotency key.'],
    [401, 'Your session has expired. Please log in again.'],
    [403, 'Sending was not authorized. Check your workspace admin access and refresh your session.'],
    [409, 'This idempotency key was already used with different event content. Restore the original content or generate a new key.'],
    [500, 'Unable to confirm acceptance. Retry with the same idempotency key to avoid creating a duplicate.'],
])('handles HTTP %i without claiming acceptance or changing the key', async (status, message) => {
    api(() => new Response(null, { status }));
    const options = props();
    await render(SendTestEvent, options);
    await fill();
    const key = currentKey();
    await page.getByRole('button', { name: 'Send event', exact: true }).click();
    await expect.element(page.getByText(message, { exact: true })).toBeVisible();
    expect(currentKey()).toBe(key);
    expect(options.onAccepted).not.toHaveBeenCalled();
    await expect.element(page.getByRole('region', { name: 'Accepted event' })).not.toBeInTheDocument();
});

it('does not send when a CSRF token cannot be obtained', async () => {
    const fetch = api(() => Response.json(event, { status: 202 }), 403);
    await render(SendTestEvent, props());
    await fill();
    await page.getByRole('button', { name: 'Send event', exact: true }).click();
    await expect.element(page.getByText('Unable to prepare the request. Refresh and try again.')).toBeVisible();
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(String(fetch.mock.calls[0][0])).toBe('/api/auth/csrf');
});

it('keeps the key after a connection failure and can retry safely', async () => {
    let failed = true;
    const fetch = api(() => {
        if (failed) throw new TypeError('Failed to fetch');
        return Response.json(event, { status: 202 });
    });
    await render(SendTestEvent, props());
    await fill();
    const key = currentKey();
    await page.getByRole('button', { name: 'Send event', exact: true }).click();
    await expect.element(page.getByText('Unable to confirm acceptance. Retry with the same idempotency key to avoid creating a duplicate.')).toBeVisible();
    failed = false;
    await page.getByRole('button', { name: 'Send event', exact: true }).click();
    await expect.element(page.getByText(event.id, { exact: true })).toBeVisible();
    expect(fetch.mock.calls.filter(([url]) => String(url) === '/api/events').every(([, init]) => new Headers(init?.headers).get('Idempotency-Key') === key)).toBe(true);
});

it('blocks duplicate submissions and field changes while acceptance is pending', async () => {
    const pending = deferred<Response>();
    const fetch = api(() => pending.promise);
    await render(SendTestEvent, props());
    await fill();
    await page.getByRole('button', { name: 'Send event', exact: true }).click();
    await expect.element(page.getByRole('button', { name: 'Sending event...' })).toBeDisabled();
    await expect.element(page.getByLabelText('JSON payload', { exact: true })).toBeDisabled();
    await expect.element(page.getByRole('button', { name: 'Generate new idempotency key' })).toBeDisabled();
    document.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await tick();
    expect(fetch.mock.calls.filter(([url]) => String(url) === '/api/events')).toHaveLength(1);
    pending.resolve(Response.json(event, { status: 202 }));
    await expect.element(page.getByText(event.id, { exact: true })).toBeVisible();
});

it('clears form data on workspace change and ignores a late response from the old workspace', async () => {
    const pending = deferred<Response>();
    api(() => pending.promise);
    const options = props();
    const screen = await render(SendTestEvent, options);
    await fill();
    const key = currentKey();
    await page.getByRole('button', { name: 'Send event', exact: true }).click();
    await screen.rerender({ tenantId: OTHER_TENANT });
    await expect.element(page.getByLabelText('Event type', { exact: true })).toHaveValue('');
    expect(currentKey()).not.toBe(key);
    pending.resolve(Response.json(event, { status: 202 }));
    await pending.promise;
    await tick();
    await expect.element(page.getByRole('region', { name: 'Accepted event' })).not.toBeInTheDocument();
    expect(options.onAccepted).not.toHaveBeenCalled();
});
