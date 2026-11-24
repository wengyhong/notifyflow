<script lang="ts">
  import { getCsrfToken } from '#lib/api/csrf.ts';
  import Icon from '#lib/components/Icon.svelte';
  import type { EventAccepted } from '#lib/types/event.ts';

  let { tenantId, onAccepted, onViewHistory }: {
    tenantId: string;
    onAccepted: () => void;
    onViewHistory: () => void;
  } = $props();

  let eventType = $state('');
  let payload = $state('');
  let idempotencyKey = $state('');
  let submitting = $state(false);
  let accepted = $state<EventAccepted | null>(null);
  let message = $state('');
  let requestVersion = 0;

  $effect(() => {
    // Discard form data and late responses when the workspace changes or panel closes.
    tenantId;
    requestVersion++;
    eventType = '';
    payload = '{\n  "orderId": 150\n}';
    idempotencyKey = crypto.randomUUID();
    accepted = null;
    message = '';
    submitting = false;
    return () => { requestVersion++; };
  });

  function newKey() {
    idempotencyKey = crypto.randomUUID();
    accepted = null;
    message = '';
  }

  async function send(event: SubmitEvent) {
    event.preventDefault();
    if (submitting) return;
    accepted = null;
    message = '';

    const type = eventType.trim();
    const key = idempotencyKey.trim();
    if (!type || type.length > 100 || !key || key.length > 200) {
      message = 'Enter an event type and an idempotency key within the displayed limits.';
      return;
    }

    let parsedPayload: unknown;
    try {
      parsedPayload = JSON.parse(payload);
    } catch {
      message = 'Payload must be valid JSON. Check commas, quotes, and brackets.';
      return;
    }
    if (parsedPayload === null) {
      message = 'Payload cannot be null. Enter a JSON object, array, string, number, or boolean.';
      return;
    }

    const requestTenantId = tenantId;
    const version = ++requestVersion;
    const isCurrent = () => requestTenantId === tenantId && version === requestVersion;
    submitting = true;
    try {
      const csrf = await getCsrfToken();
      if (!isCurrent()) return;
      if (!csrf) {
        message = 'Unable to prepare the request. Refresh and try again.';
        return;
      }
      const response = await fetch('/api/events', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Tenant-ID': requestTenantId,
          'Idempotency-Key': key,
          [csrf.headerName]: csrf.token
        },
        // Validate with JSON.parse, but preserve payload text (including large numeric values).
        body: `{"type":${JSON.stringify(type)},"payload":${payload}}`
      });
      if (!isCurrent()) return;

      if (response.status === 202) {
        const result: EventAccepted = await response.json();
        if (!isCurrent()) return;
        accepted = result;
        onAccepted();
      } else if (response.status === 409) {
        message = 'This idempotency key was already used with different event content. Restore the original content or generate a new key.';
      } else if (response.status === 401) {
        message = 'Your session has expired. Please log in again.';
      } else if (response.status === 403) {
        message = 'Sending was not authorized. Check your workspace admin access and refresh your session.';
      } else if (response.status === 400) {
        message = 'Check the event type, JSON payload, and idempotency key.';
      } else {
        message = 'Unable to confirm acceptance. Retry with the same idempotency key to avoid creating a duplicate.';
      }
    } catch {
      if (isCurrent()) message = 'Unable to confirm acceptance. Retry with the same idempotency key to avoid creating a duplicate.';
    } finally {
      if (isCurrent()) submitting = false;
    }
  }
</script>

<p class="test-event-intro">Publish a real event to this workspace's matching enabled endpoints.</p>
<form onsubmit={send}>
  <fieldset class="test-event-fields" disabled={submitting}>
    <div>
      <label for="test-event-type">Event type</label>
      <input id="test-event-type" bind:value={eventType} maxlength="100" placeholder="e.g. hosted.receiver.demo" required aria-describedby="test-event-type-help" />
      <p class="field-help" id="test-event-type-help">Use an event type subscribed to by an enabled endpoint.</p>
    </div>
    <div>
      <label for="test-event-payload">JSON payload</label>
      <textarea id="test-event-payload" bind:value={payload} rows="9" spellcheck="false" required aria-describedby="test-event-payload-help"></textarea>
      <p class="field-help" id="test-event-payload-help">Use sample data. This payload will be sent to matching receivers.</p>
    </div>
    <div>
      <label for="test-event-key">Idempotency key</label>
      <div class="test-event-key-row">
        <input id="test-event-key" bind:value={idempotencyKey} maxlength="200" required aria-describedby="test-event-key-help" />
        <button type="button" class="button--small" onclick={newKey} aria-label="Generate new idempotency key">New key</button>
      </div>
      <p class="field-help" id="test-event-key-help">Reuse this key with unchanged content to return the same event. A new key creates a new event.</p>
    </div>
    <button type="submit">{submitting ? 'Sending event...' : 'Send event'}<Icon name="arrow" size={14} /></button>
  </fieldset>
</form>
<p role="status">{message}</p>

{#if accepted}
  <section class="test-event-result" aria-label="Accepted event" aria-live="polite">
    <span class="status-badge" data-tone="success">Accepted</span>
    <h3>Event accepted for processing</h3>
    <dl>
      <div><dt>Event ID</dt><dd><code>{accepted.id}</code></dd></div>
      <div><dt>Event type</dt><dd>{accepted.type}</dd></div>
      <div><dt>Accepted at</dt><dd><time datetime={accepted.createdAt}>{new Date(accepted.createdAt).toLocaleString()}</time></dd></div>
    </dl>
    <p>Acceptance is not a delivery result. Only matching subscriptions create deliveries.</p>
    <button type="button" class="button--small" onclick={onViewHistory}>View delivery history<Icon name="arrow" size={13} /></button>
  </section>
{/if}
