<script lang="ts">
  import { getCsrfToken } from '#lib/api/csrf.ts';
  import type { WebhookEndpointCreated } from "#lib/types/webhook-endpoint.ts";

  let createdEndpoint = $state<WebhookEndpointCreated | null>(null);
let { tenantId, onCreated }: {
	tenantId: string;
	onCreated: () => Promise<void>;
} = $props();  let submitting = $state(false);
  let message = $state("");
  async function handleSubmit(event: SubmitEvent) {
    event.preventDefault();
    if (submitting) return;
    const form = event.currentTarget;
    if (!(form instanceof HTMLFormElement)) return;

    const requestTenantId = tenantId;
    const formData = new FormData(form);

    const body = JSON.stringify({
      name: String(formData.get("name") ?? ""),
      url: String(formData.get("url") ?? ""),
    });

    message = "";
    submitting = true;
    try {
      const csrf = await getCsrfToken();

      if (!csrf) {
        message = "Unable to prepare endpoint creation. Please try again.";
        return;
      }

      const response = await fetch("/api/endpoints", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Tenant-ID": requestTenantId,
          [csrf.headerName]: csrf.token,
        },
        body,
      });

      if (!response.ok) {
        message =
          response.status === 400
            ? "Check the endpoint name and URL. The URL must be allowed by the server."
            : "Unable to create endpoint. Please try again.";
        return;
      }

      const created: WebhookEndpointCreated = await response.json();

      if (tenantId === requestTenantId) {
        createdEndpoint = created;
        form.reset();
        message = "Endpoint created.";
        await onCreated();
      }
    } catch {
      message = "Unable to connect. Please try again.";
    } finally {
      submitting = false;
    }
  }
</script>

<h3>Add an endpoint</h3>
<p class="muted" style="font-size: 11px; margin-bottom: 20px">Give your events a destination.</p>
<form onsubmit={handleSubmit}>
  <div><label for="endpoint-name">Endpoint name</label><input id="endpoint-name" name="name" type="text" maxlength="100" placeholder="e.g. Order notifications" required /></div>
  <div><label for="endpoint-url">Webhook URL</label><input id="endpoint-url" name="url" type="url" placeholder="https://your-app.com/webhook" required /></div>
  <button type="submit" disabled={submitting}>{submitting ? 'Creating endpoint...' : '+ Create endpoint'}</button>
</form>
<p role="status">{message}</p>
{#if createdEndpoint}
  <div class="secret-box"><h4>Your signing secret</h4><p>Save this secret for {createdEndpoint.name}'s webhook receiver.</p><code>{createdEndpoint.signingSecret}</code><button type="button" class="button--small" onclick={() => createdEndpoint = null}>Hide secret</button></div>
{/if}
