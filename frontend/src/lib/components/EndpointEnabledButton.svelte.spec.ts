import { afterEach, expect, it, vi } from 'vitest';
import { page } from 'vitest/browser';
import { render } from 'vitest-browser-svelte';
import EndpointEnabledButton from './EndpointEnabledButton.svelte';
import { api, ENDPOINT, endpoint, mutation, TENANT } from '#lib/testing/api.ts';

afterEach(() => vi.unstubAllGlobals());

it.each([true, false])('requests the opposite enabled state from %s and refreshes after success', async (enabled) => {
	const onChanged = vi.fn().mockResolvedValue(undefined);
	const fetch = api(() => Response.json(endpoint({ enabled: !enabled })));
	await render(EndpointEnabledButton, { tenantId: TENANT, endpoint: endpoint({ enabled }), onChanged });
	await page.getByRole('button', { name: enabled ? 'Disable endpoint' : 'Enable endpoint' }).click();
	await expect.poll(() => onChanged.mock.calls.length).toBe(1);
	expect(JSON.parse(String(mutation(fetch, `/api/endpoints/${ENDPOINT}/enabled`, 'PATCH', TENANT).body)))
		.toEqual({ enabled: !enabled });
});

it('shows rejected updates without calling the refresh callback', async () => {
	const onChanged = vi.fn().mockResolvedValue(undefined);
	api(() => new Response(null, { status: 403 }));
	await render(EndpointEnabledButton, { tenantId: TENANT, endpoint: endpoint(), onChanged });
	await page.getByRole('button', { name: 'Disable endpoint' }).click();
	await expect.element(page.getByText('Unable to update endpoint. Please try again.')).toBeVisible();
	expect(onChanged).not.toHaveBeenCalled();
});
