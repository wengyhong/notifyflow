<script lang="ts">
	import { getCsrfToken } from '#lib/api/csrf.ts';
	import type { WebhookSubscription } from '#lib/types/webhook-subscription.ts';

	let { tenantId, endpointId, onCreated }: {
		tenantId: string;
		endpointId: string;
		onCreated: () => Promise<void>;
	} = $props();

	let message = $state('');
	let submitting = $state(false);

	async function handleSubmit(event: SubmitEvent) {
		event.preventDefault();
		if (submitting) return;

		const form = event.currentTarget;
		if (!(form instanceof HTMLFormElement)) return;

		const requestTenantId = tenantId;
		const requestEndpointId = endpointId;
		const formData = new FormData(form);
		const eventType = String(formData.get('eventType') ?? '');

		message = '';
		if (!eventType.trim()) {
			message = 'Enter an event type.';
			return;
		}

		submitting = true;
		try {
			const csrf = await getCsrfToken();
			if (!csrf) {
				message = 'Unable to prepare subscription. Please try again.';
				return;
			}

			const response = await fetch(`/api/subscriptions/${requestEndpointId}`, {
				method: 'POST',
				headers: {
					'Content-Type': 'application/json',
					'X-Tenant-ID': requestTenantId,
					[csrf.headerName]: csrf.token
				},
				body: JSON.stringify({ eventType })
			});

			if (tenantId !== requestTenantId || endpointId !== requestEndpointId) return;

			if (response.status === 201) {
				const subscription: WebhookSubscription = await response.json();
				form.reset();
				message = `Subscribed to ${subscription.eventType}.`;
				await onCreated();
			} else if (response.status === 409) {
				message = 'This endpoint is already subscribed to that event type.';
			} else if (response.status === 400) {
				message = 'Enter an event type containing 1 to 100 characters.';
			} else if (response.status === 401) {
				message = 'Your session has expired. Please log in again.';
			} else if (response.status === 403) {
				message = 'Subscription was not authorized. Check your workspace access and try again.';
			} else if (response.status === 404) {
				message = 'Endpoint not found in this workspace.';
			} else {
				message = 'Unable to subscribe. Please try again.';
			}
		} catch {
			message = 'Unable to connect. Please try again.';
		} finally {
			submitting = false;
		}
	}
</script>

<form class="subscription-form" onsubmit={handleSubmit}>
  <div><label for={`event-type-${endpointId}`}>Subscribe to an event type</label><input id={`event-type-${endpointId}`} name="eventType" type="text" maxlength="100" placeholder="e.g. order.created" required /></div>
  <button type="submit" disabled={submitting}>{submitting ? 'Adding...' : 'Subscribe'}</button>
</form>
<p role="status">{message}</p>
