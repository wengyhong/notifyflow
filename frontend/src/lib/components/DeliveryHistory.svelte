<script lang="ts">
  import StatusBadge from '#lib/components/StatusBadge.svelte';
  import Icon from '#lib/components/Icon.svelte';
	import DeliveryDetails from '#lib/components/DeliveryDetails.svelte';
	import { deliveryStatuses, type DeliveryPage, type DeliveryStatus } from '#lib/types/delivery.ts';

	let { tenantId, canReplay }: { tenantId: string; canReplay: boolean } = $props();
	let selectedStatus = $state<DeliveryStatus | ''>('');
	let deliveryPage = $state<DeliveryPage | null>(null);
	let selectedDeliveryId = $state('');
	let message = $state('');
	let loading = $state(false);
	let latestRequestId = 0;

	$effect(() => {
		selectedDeliveryId = '';
		void loadDeliveries();
	});

	async function loadDeliveries(pageNumber = 0) {
		const requestId = ++latestRequestId;
		const requestTenantId = tenantId;
		const status = selectedStatus;
		const query = new URLSearchParams({ page: String(pageNumber), size: '20' });
		if (status) query.set('status', status);
		deliveryPage = null;
		loading = true;
		message = 'Loading deliveries...';
		try {
			const response = await fetch(`/api/deliveries?${query}`, {
				headers: { 'X-Tenant-ID': requestTenantId }, cache: 'no-store'
			});
			if (requestId !== latestRequestId) return;
			if (!response.ok) {
				message = response.status === 401
					? 'Please log in again to view deliveries.'
					: 'Unable to load deliveries. Check your workspace access and try again.';
				return;
			}
			const result: DeliveryPage = await response.json();
			if (requestId === latestRequestId && tenantId === requestTenantId && selectedStatus === status) {
				deliveryPage = result;
				message = '';
			}
		} catch {
			if (requestId === latestRequestId) message = 'Unable to connect. Please try again.';
		} finally {
			if (requestId === latestRequestId) loading = false;
		}
	}
</script>

<section class="panel" aria-labelledby="delivery-history-heading">
  <div class="section-heading"><div><h2 id="delivery-history-heading"><span class="section-index">02</span>Delivery history</h2><p>Follow your events from the first attempt to the final outcome.</p></div></div>
  <div class="toolbar"><div><label for="delivery-status">FILTER BY STATUS</label><select id="delivery-status" bind:value={selectedStatus}><option value="">All statuses</option>{#each deliveryStatuses as status}<option value={status}>{status.toLowerCase().replaceAll('_', ' ')}</option>{/each}</select></div><button type="button" disabled={loading} onclick={() => loadDeliveries(deliveryPage?.page ?? 0)}><Icon name="refresh" size={14} />Refresh deliveries</button></div>
  <p role="status">{message}</p>
  {#if deliveryPage}
    <div class="table-wrap"><table><thead><tr><th scope="col">Delivery</th><th scope="col">Status</th><th scope="col">Created</th><th scope="col">Action</th></tr></thead><tbody>
      {#each deliveryPage.content as delivery (delivery.id)}
        <tr class:selected-row={selectedDeliveryId === delivery.id}><td><code title={delivery.id}>{delivery.id.slice(0, 8)}&hellip;</code></td><td><StatusBadge status={delivery.status} /></td><td><time class="table-date" datetime={delivery.createdAt}>{new Date(delivery.createdAt).toLocaleString()}</time></td><td><button class="button--small" type="button" aria-pressed={selectedDeliveryId === delivery.id} onclick={() => selectedDeliveryId = delivery.id}>View details <Icon name="arrow" size={12} /></button></td></tr>
      {:else}<tr><td colspan="4" class="empty-state">No deliveries match this filter. Your events will appear here as they are processed.</td></tr>{/each}
    </tbody></table></div>
    <p class="list-meta">{deliveryPage.totalElements} deliveries in this view</p>
    {#if deliveryPage.totalPages > 0}<nav aria-label="Delivery history pagination"><button type="button" disabled={deliveryPage.page === 0} onclick={() => { if (deliveryPage) void loadDeliveries(deliveryPage.page - 1); }}>Previous</button><span>Page {deliveryPage.page + 1} of {deliveryPage.totalPages}</span><button type="button" disabled={deliveryPage.page + 1 >= deliveryPage.totalPages} onclick={() => { if (deliveryPage) void loadDeliveries(deliveryPage.page + 1); }}>Next</button></nav>{/if}
  {/if}
  {#if selectedDeliveryId}
    <button class="button--small" style="margin-top: 22px" type="button" onclick={() => selectedDeliveryId = ''}>Close details</button>
    {#key selectedDeliveryId}<DeliveryDetails {tenantId} deliveryId={selectedDeliveryId} {canReplay} onReplayed={() => loadDeliveries(deliveryPage?.page ?? 0)} />{/key}
  {/if}
</section>
