<script lang="ts">
  import StatusBadge from '#lib/components/StatusBadge.svelte';
  import Icon from '#lib/components/Icon.svelte';
	import { getCsrfToken } from '#lib/api/csrf.ts';
	import type { ApiKeyCreated, ApiKeyPage } from '#lib/types/api-key.ts';

	let { tenantId }: { tenantId: string } = $props();
	let keyPage = $state<ApiKeyPage | null>(null);
	let createdKey = $state<ApiKeyCreated | null>(null);
	let loading = $state(false);
	let creating = $state(false);
	let revokingId = $state<string | null>(null);
	let message = $state('');
	let listMessage = $state('');
	let latestRequestId = 0;

	$effect(() => {
		void loadKeys();
	});

	async function loadKeys(pageNumber = 0) {
		const requestId = ++latestRequestId;
		const requestTenantId = tenantId;
		keyPage = null;
		loading = true;
		listMessage = 'Loading producer API keys...';
		try {
			const response = await fetch(`/api/key?page=${pageNumber}&size=20`, {
				headers: { 'X-Tenant-ID': requestTenantId },
				cache: 'no-store'
			});
			if (requestId !== latestRequestId || tenantId !== requestTenantId) return;
			if (!response.ok) {
				listMessage = response.status === 401
					? 'Please log in again to view producer API keys.'
					: 'Unable to load keys. Check your workspace admin access and try again.';
				return;
			}
			const result: ApiKeyPage = await response.json();
			if (requestId === latestRequestId && tenantId === requestTenantId) {
				keyPage = result;
				listMessage = '';
			}
		} catch {
			if (requestId === latestRequestId) listMessage = 'Unable to load keys. Check your connection and try again.';
		} finally {
			if (requestId === latestRequestId) loading = false;
		}
	}

	async function createKey(event: SubmitEvent) {
		event.preventDefault();
		if (creating || revokingId || createdKey) return;
		const form = event.currentTarget;
		if (!(form instanceof HTMLFormElement)) return;
		const requestTenantId = tenantId;
		const name = String(new FormData(form).get('name') ?? '');
		message = '';
		if (!name.trim()) {
			message = 'Enter a key name.';
			return;
		}

		creating = true;
		try {
			const csrf = await getCsrfToken();
			if (!csrf) {
				message = 'Unable to prepare key creation. Please try again.';
				return;
			}
			const response = await fetch('/api/key', {
				method: 'POST',
				headers: {
					'Content-Type': 'application/json',
					'X-Tenant-ID': requestTenantId,
					[csrf.headerName]: csrf.token
				},
				body: JSON.stringify({ name })
			});
			if (tenantId !== requestTenantId) return;
			if (response.status !== 201) {
				message = response.status === 400
					? 'Enter a key name containing 1 to 100 characters.'
					: 'Unable to create key. Check your session and workspace admin access.';
				return;
			}
			const result: ApiKeyCreated = await response.json();
			if (tenantId !== requestTenantId) return;
			createdKey = result;
			form.reset();
			message = 'Producer API key created.';
			await loadKeys();
		} catch {
			message = 'Unable to complete key creation. Refresh the key list before trying again.';
		} finally {
			creating = false;
		}
	}

	async function revokeKey(keyId: string) {
		if (creating || revokingId) return;
		const requestTenantId = tenantId;
		const pageNumber = keyPage?.page ?? 0;
		revokingId = keyId;
		message = '';
		try {
			const csrf = await getCsrfToken();
			if (!csrf) {
				message = 'Unable to prepare revocation. Please try again.';
				return;
			}
			const response = await fetch(`/api/key/${keyId}/revoke`, {
				method: 'POST',
				headers: {
					'X-Tenant-ID': requestTenantId,
					[csrf.headerName]: csrf.token
				}
			});
			if (tenantId !== requestTenantId) return;
			if (response.status !== 204) {
				message = response.status === 404
					? 'Key not found in this workspace. Refresh the list.'
					: 'Unable to revoke key. Check your session and workspace admin access.';
				return;
			}
			if (createdKey?.id === keyId) createdKey = null;
			message = 'Producer API key revoked. New publishing requests using it will be rejected.';
			await loadKeys(pageNumber);
		} catch {
			message = 'Unable to complete revocation. Refresh the key list to check its status.';
		} finally {
			revokingId = null;
		}
	}
</script>

<section class="panel" aria-labelledby="producer-keys-heading">
  <div class="section-heading"><div><h2 id="producer-keys-heading"><span class="section-index">03</span>Producer API keys</h2><p>Credentials for applications publishing events to this workspace.</p></div><button type="button" disabled={loading || creating || revokingId !== null} onclick={() => loadKeys(keyPage?.page ?? 0)}><Icon name="refresh" size={14} />Refresh</button></div>
  <form class="key-create-form" onsubmit={createKey}>
    <div class="field"><label for="producer-key-name">Create a producer key</label><input id="producer-key-name" name="name" type="text" maxlength="100" placeholder="e.g. Production order service" required /></div>
    <button type="submit" disabled={creating || revokingId !== null || createdKey !== null}><Icon name="plus" size={14} />{creating ? 'Creating...' : 'Create key'}</button>
  </form>
  <p role="status">{message}</p>
  {#if createdKey}
    <div class="secret-box"><h3>New key: {createdKey.name}</h3><p>Save this key privately. You cannot retrieve it after hiding it or leaving this view.</p><code>{createdKey.rawKey}</code><button class="button--small" type="button" onclick={() => createdKey = null}>Hide key</button><p style="margin: 10px 0 0">Save and hide this key before creating another.</p></div>
  {/if}
  <p role="status">{listMessage}</p>
  {#if keyPage}
    <div class="table-wrap"><table><thead><tr><th scope="col">Key</th><th scope="col">Status</th><th scope="col">Created</th><th scope="col">Action</th></tr></thead><tbody>
      {#each keyPage.content as key (key.id)}
        <tr><td><span class="table-title">{key.name}</span><code title={key.id}>{key.id.slice(0, 8)}&hellip;</code></td><td><StatusBadge status={key.revokedAt ? 'REVOKED' : 'ACTIVE'} />{#if key.revokedAt}<small class="table-subtext">{new Date(key.revokedAt).toLocaleString()}</small>{/if}</td><td><time class="table-date" datetime={key.createdAt}>{new Date(key.createdAt).toLocaleString()}</time></td><td>{#if !key.revokedAt}<button class="button--danger button--small" type="button" disabled={creating || revokingId !== null} onclick={() => revokeKey(key.id)}>{revokingId === key.id ? 'Revoking...' : 'Revoke key'}</button>{:else}<span class="muted">&mdash;</span>{/if}</td></tr>
      {:else}<tr><td colspan="4" class="empty-state">No producer keys yet. Create one to connect a publishing application.</td></tr>{/each}
    </tbody></table></div>
    <p class="list-meta">{keyPage.totalElements} producer API keys</p>
    {#if keyPage.totalPages > 0}<nav aria-label="Producer API key pagination"><button type="button" disabled={keyPage.page === 0 || creating || revokingId !== null} onclick={() => { if (keyPage) void loadKeys(keyPage.page - 1); }}>Previous</button><span>Page {keyPage.page + 1} of {keyPage.totalPages}</span><button type="button" disabled={keyPage.page + 1 >= keyPage.totalPages || creating || revokingId !== null} onclick={() => { if (keyPage) void loadKeys(keyPage.page + 1); }}>Next</button></nav>{/if}
  {/if}
</section>
