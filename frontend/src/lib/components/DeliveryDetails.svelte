<script lang="ts">
  import StatusBadge from '#lib/components/StatusBadge.svelte';
  import Icon from '#lib/components/Icon.svelte';
	import { getCsrfToken } from '#lib/api/csrf.ts';
	import type { Delivery, DeliveryAttemptPage } from '#lib/types/delivery.ts';

	let { tenantId, deliveryId, canReplay, onReplayed, embedded = false }: {
		tenantId: string;
		deliveryId: string;
		canReplay: boolean;
		onReplayed: () => Promise<void>;
		embedded?: boolean;
	} = $props();

	let delivery = $state<Delivery | null>(null);
	let attemptPage = $state<DeliveryAttemptPage | null>(null);
	let loading = $state(false);
	let replaying = $state(false);
	let message = $state('');
	let replayMessage = $state('');
	let latestRequestId = 0;

	$effect(() => {
		void loadDetails();
	});

	async function loadDetails(pageNumber = 0) {
		const requestId = ++latestRequestId;
		const requestTenantId = tenantId;
		const requestDeliveryId = deliveryId;
		delivery = null;
		attemptPage = null;
		loading = true;
		message = 'Loading delivery details...';
		try {
			const headers = { 'X-Tenant-ID': requestTenantId };
			const [detailResponse, attemptsResponse] = await Promise.all([
				fetch(`/api/deliveries/${requestDeliveryId}`, { headers, cache: 'no-store' }),
				fetch(`/api/deliveries/${requestDeliveryId}/attempts?page=${pageNumber}&size=20`, { headers, cache: 'no-store' })
			]);
			if (requestId !== latestRequestId) return;
			if (!detailResponse.ok || !attemptsResponse.ok) {
				const status = !detailResponse.ok ? detailResponse.status : attemptsResponse.status;
				message = status === 404
					? 'Delivery not found in this workspace.'
					: status === 401 ? 'Please log in again to view this delivery.'
					: 'Unable to load delivery details. Check your workspace access and try again.';
				return;
			}
			const result: Delivery = await detailResponse.json();
			const attempts: DeliveryAttemptPage = await attemptsResponse.json();
			if (requestId === latestRequestId && tenantId === requestTenantId && deliveryId === requestDeliveryId) {
				delivery = result;
				attemptPage = attempts;
				message = '';
			}
		} catch {
			if (requestId === latestRequestId) message = 'Unable to connect. Please try again.';
		} finally {
			if (requestId === latestRequestId) loading = false;
		}
	}

	async function replay() {
		if (replaying || loading || !canReplay || delivery?.status !== 'FAILED') return;
		const requestTenantId = tenantId;
		const requestDeliveryId = deliveryId;
		replaying = true;
		replayMessage = '';
		try {
			const csrf = await getCsrfToken();
			if (!csrf) {
				replayMessage = 'Unable to prepare replay. Please try again.';
				return;
			}
			const response = await fetch(`/api/deliveries/${requestDeliveryId}/replay`, {
				method: 'POST',
				headers: { 'X-Tenant-ID': requestTenantId, [csrf.headerName]: csrf.token }
			});
			if (tenantId !== requestTenantId || deliveryId !== requestDeliveryId) return;
			if (response.status === 202) {
				replayMessage = 'Replay accepted. Refresh to see processing progress and new attempts.';
				await onReplayed();
				await loadDetails();
			} else if (response.status === 409) {
				replayMessage = 'Replay is only allowed for failed deliveries. The delivery status may have changed.';
				await onReplayed();
				await loadDetails();
			} else if (response.status === 404) {
				replayMessage = 'Delivery not found in this workspace.';
			} else {
				replayMessage = 'Replay was not accepted. Check your session and workspace admin access.';
			}
		} catch {
			replayMessage = 'Unable to complete replay. Refresh the delivery status before trying again.';
		} finally {
			replaying = false;
		}
	}
</script>

<section class="details-panel" class:details-panel--embedded={embedded} aria-labelledby={embedded ? undefined : 'delivery-details-heading'} aria-label={embedded ? 'Delivery details' : undefined}>
  <div class="section-heading"><div>{#if !embedded}<h3 id="delivery-details-heading">Delivery details</h3>{/if}<code>{deliveryId}</code></div><button class="button--small" type="button" disabled={loading || replaying} onclick={() => loadDetails(attemptPage?.page ?? 0)}><Icon name="refresh" size={13} />Refresh</button></div>
  <p role="status">{message}</p><p role="status">{replayMessage}</p>
  {#if delivery}
    <dl class="details-grid">
      <div><dt>Status</dt><dd><StatusBadge status={delivery.status} /></dd></div>
      <div><dt>Created</dt><dd>{new Date(delivery.createdAt).toLocaleString()}</dd></div>
      <div><dt>Next attempt</dt><dd>{delivery.nextAttemptAt ? new Date(delivery.nextAttemptAt).toLocaleString() : 'Not scheduled'}</dd></div>
      <div><dt>Event ID</dt><dd><code>{delivery.eventId}</code></dd></div>
      <div><dt>Endpoint ID</dt><dd><code>{delivery.endpointId}</code></dd></div>
      <div><dt>Retry cycle starts at attempt</dt><dd>{delivery.retryCycleStartAttempt}</dd></div>
    </dl>
    {#if canReplay && delivery.status === 'FAILED'}<button class="button--primary" type="button" disabled={replaying || loading} onclick={replay}>{replaying ? 'Requesting replay...' : 'Replay failed delivery'}<Icon name="arrow" size={14} /></button>{/if}
  {/if}
  {#if attemptPage}
    <h4>HTTP attempts</h4><ol class="attempt-list">
      {#each attemptPage.content as attempt (attempt.id)}
        <li><div class="attempt-heading"><strong>Attempt {attempt.attemptNumber}</strong><span class="status-badge" data-tone={attempt.responseStatus !== null ? (attempt.responseStatus >= 200 && attempt.responseStatus < 300 ? 'success' : 'danger') : 'neutral'}>{attempt.responseStatus !== null ? `HTTP ${attempt.responseStatus}` : attempt.completedAt ? 'No response' : 'In progress'}</span></div><p>Started: {new Date(attempt.startedAt).toLocaleString()}</p><p>Completed: {attempt.completedAt ? new Date(attempt.completedAt).toLocaleString() : 'In progress'}</p>{#if attempt.errorMessage}<p class="attempt-error">{attempt.errorMessage}</p>{/if}</li>
      {:else}<li class="empty-state">No HTTP attempts recorded yet.</li>{/each}
    </ol><p class="list-meta">{attemptPage.totalElements} attempts &middot; newest first</p>
    {#if attemptPage.totalPages > 0}<nav aria-label="Delivery attempt pagination"><button type="button" disabled={attemptPage.page === 0 || replaying} onclick={() => { if (attemptPage) void loadDetails(attemptPage.page - 1); }}>Previous</button><span>Page {attemptPage.page + 1} of {attemptPage.totalPages}</span><button type="button" disabled={attemptPage.page + 1 >= attemptPage.totalPages || replaying} onclick={() => { if (attemptPage) void loadDetails(attemptPage.page + 1); }}>Next</button></nav>{/if}
  {/if}
</section>
