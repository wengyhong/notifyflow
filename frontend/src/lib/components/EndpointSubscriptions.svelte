<script lang="ts">
	import CreateSubscriptionForm from '#lib/components/CreateSubscriptionForm.svelte';
	import type { WebhookSubscription } from '#lib/types/webhook-subscription.ts';

	let { tenantId, endpointId, canManage }: {
		tenantId: string;
		endpointId: string;
		canManage: boolean;
	} = $props();

	let open = $state(false);
	let subscriptions = $state<WebhookSubscription[] | null>(null);
	let message = $state('');
	let loading = $state(false);
	let latestRequestId = 0;

	$effect(() => {
		if (open) void loadSubscriptions();
	});

	async function loadSubscriptions() {
		const requestId = ++latestRequestId;
		const requestTenantId = tenantId;
		const requestEndpointId = endpointId;
		subscriptions = null;
		message = 'Loading subscriptions...';
		loading = true;

		try {
			const response = await fetch(`/api/subscriptions/${requestEndpointId}`, {
				headers: { 'X-Tenant-ID': requestTenantId }
			});
			if (requestId !== latestRequestId) return;

			if (!response.ok) {
				message = response.status === 401
					? 'Please log in again to view subscriptions.'
					: 'Unable to load subscriptions. Check your workspace access and try again.';
				return;
			}

			const result: WebhookSubscription[] = await response.json();
			if (requestId === latestRequestId && tenantId === requestTenantId && endpointId === requestEndpointId) {
				subscriptions = result;
				message = '';
			}
		} catch {
			if (requestId === latestRequestId) message = 'Unable to connect. Please try again.';
		} finally {
			if (requestId === latestRequestId) loading = false;
		}
	}
</script>

<details bind:open>
  <summary>Event subscriptions</summary>
  <button class="button--small" type="button" onclick={loadSubscriptions} disabled={loading}>Refresh subscriptions</button>
  <p role="status">{message}</p>
  {#if subscriptions}
    <ul class="subscription-list">
      {#each subscriptions as subscription (subscription.id)}<li><code>{subscription.eventType}</code></li>
      {:else}<li class="muted">No event types subscribed yet.</li>{/each}
    </ul>
  {/if}
  {#if canManage}<CreateSubscriptionForm {tenantId} {endpointId} onCreated={loadSubscriptions} />{/if}
</details>
