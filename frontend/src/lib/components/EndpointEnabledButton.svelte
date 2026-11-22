<script lang="ts">
	import { getCsrfToken } from '#lib/api/csrf.ts';
	import type { WebhookEndpoint } from '#lib/types/webhook-endpoint.ts';

	let { tenantId, endpoint, onChanged }: {
		tenantId: string;
		endpoint: WebhookEndpoint;
		onChanged: () => Promise<void>;
	} = $props();

	let submitting = $state(false);
	let message = $state('');

	async function toggleEnabled() {
	if (submitting) return;

	const requestTenantId = tenantId;
	const endpointId = endpoint.id;
	const enabled = !endpoint.enabled;

	message = '';
	submitting = true;

	try {
		const csrf = await getCsrfToken();

		if (!csrf) {
			message = 'Unable to prepare update. Please try again.';
			return;
		}

		const response = await fetch(`/api/endpoints/${endpointId}/enabled`, {
			method: 'PATCH',
			headers: {
				'Content-Type': 'application/json',
				'X-Tenant-ID': requestTenantId,
				[csrf.headerName]: csrf.token
			},
			body: JSON.stringify({ enabled })
		});

		if (!response.ok) {
			message = 'Unable to update endpoint. Please try again.';
			return;
		}

		await onChanged();
	} catch {
		message = 'Unable to connect. Please try again.';
	} finally {
		submitting = false;
	}
}
</script>

<button class="button--small" type="button" disabled={submitting} onclick={toggleEnabled}>{submitting ? 'Updating...' : endpoint.enabled ? 'Disable endpoint' : 'Enable endpoint'}</button>
<p role="status">{message}</p>
