<script lang="ts">
	let { status }: { status: string } = $props();
	const tones: Record<string, string> = {
		DELIVERED: 'success', ACTIVE: 'success', ENABLED: 'success',
		FAILED: 'danger', REVOKED: 'neutral', DISABLED: 'neutral',
		PENDING: 'info', PROCESSING: 'info', RETRY_SCHEDULED: 'warning',
		ADMIN: 'success', VIEWER: 'neutral'
	};
	let label = $derived(status.toLowerCase().replaceAll('_', ' '));
</script>

<span class="status-badge" data-tone={tones[status] ?? 'neutral'}>
	<span class="status-dot" aria-hidden="true"></span>{label}
</span>
