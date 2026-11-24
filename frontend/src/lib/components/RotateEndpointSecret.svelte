<script lang="ts">
	import { getCsrfToken } from '#lib/api/csrf.ts';
	import type { WebhookSecretRotated } from '#lib/types/webhook-endpoint.ts';

	let { tenantId, endpointId, embedded = false }: {
		tenantId: string;
		endpointId: string;
		embedded?: boolean;
	} = $props();

	let submitting = $state(false);
	let message = $state('');
	let rotation = $state<WebhookSecretRotated | null>(null);

	async function rotateSecret() {
		if (submitting) return;

		const requestTenantId = tenantId;
		const requestEndpointId = endpointId;
		submitting = true;
		message = '';

		try {
			const csrf = await getCsrfToken();
			if (!csrf) {
				message = 'Unable to prepare rotation. Please try again.';
				return;
			}

			const response = await fetch(`/api/endpoints/${requestEndpointId}/signing-secret/rotate`, {
				method: 'POST',
				headers: {
					'X-Tenant-ID': requestTenantId,
					[csrf.headerName]: csrf.token
				}
			});

			if (tenantId !== requestTenantId || endpointId !== requestEndpointId) return;

			if (response.status === 200) {
				const result: WebhookSecretRotated = await response.json();
				if (tenantId !== requestTenantId || endpointId !== requestEndpointId) return;
				rotation = result;
				message = 'Signing secret rotated. Save the new secret and update your receiver.';
			} else if (response.status === 409) {
				message = 'A rotation overlap is already active. Wait until the previous secret expires before rotating again.';
			} else if (response.status === 401) {
				message = 'Your session has expired. Please log in again.';
			} else if (response.status === 403) {
				message = 'Rotation was not authorized. Check your workspace access and try again.';
			} else if (response.status === 404) {
				message = 'Endpoint not found in this workspace.';
			} else {
				message = 'Unable to rotate signing secret. Please try again.';
			}
		} catch {
			message = 'Unable to complete rotation. Check your connection before trying again.';
		} finally {
			submitting = false;
		}
	}
</script>

{#snippet content()}
  <p>Create a new signing secret, then update your receiver during the one-hour overlap.</p>
  <button class="button--small" type="button" onclick={rotateSecret} disabled={submitting}>{submitting ? 'Rotating...' : 'Rotate signing secret'}</button>
  <p role="status">{message}</p>
  {#if rotation}
    <div class="secret-box"><h4>Your new signing secret</h4><p>Save this secret for your webhook receiver.</p><code>{rotation.signingSecret}</code><p>Previous secret expires: <time datetime={rotation.previousSigningSecretExpiresAt}>{new Date(rotation.previousSigningSecretExpiresAt).toLocaleString()}</time></p><button type="button" class="button--small" onclick={() => rotation = null}>Hide secret</button></div>
  {/if}
{/snippet}

{#if embedded}
  <section class="endpoint-panel-section" aria-label="Signing secret rotation">
    <h3>Signing secret rotation</h3>
    {@render content()}
  </section>
{:else}
  <details>
    <summary>Signing secret rotation</summary>
    {@render content()}
  </details>
{/if}
