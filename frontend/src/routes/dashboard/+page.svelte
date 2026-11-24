<script lang="ts">
  import Brand from '#lib/components/Brand.svelte';
  import Icon from '#lib/components/Icon.svelte';
  import StatusBadge from '#lib/components/StatusBadge.svelte';
  import SidePanel from '#lib/components/SidePanel.svelte';
  import SendTestEvent from '#lib/components/SendTestEvent.svelte';
  let selectedTenantId = $state("");
  import CreateEndpointForm from "#lib/components/CreateEndpointForm.svelte";
  import ApiKeyManagement from '#lib/components/ApiKeyManagement.svelte';
  import DeliveryHistory from '#lib/components/DeliveryHistory.svelte';
  import EndpointSubscriptions from '#lib/components/EndpointSubscriptions.svelte';
  import { onMount, tick } from "svelte";
  import type { WebhookEndpoint, WebhookEndpointPage } from "#lib/types/webhook-endpoint.ts";
  import type { CurrentUser } from "#lib/types/current-user.ts";
  import EndpointEnabledButton from "#lib/components/EndpointEnabledButton.svelte";
  import RotateEndpointSecret from '#lib/components/RotateEndpointSecret.svelte';
  import LogoutButton from '#lib/components/LogoutButton.svelte';
  let endpointPage = $state<WebhookEndpointPage | null>(null);
  let endpointMessage = $state("");
  let endpointRequestId = 0;
  let selectedEndpoint = $state<WebhookEndpoint | null>(null);
  let creatingEndpoint = $state(false);
  let panelTenantId = $state('');
  let copyMessage = $state('');
  let showingTestEvent = $state(false);
  let deliveryRefreshVersion = $state(0);
  let user = $state<CurrentUser | null>(null);
  let selectedWorkspace = $derived(
    user?.workspaces.find(
      (workspace) => workspace.tenantId === selectedTenantId,
    ),
  );
  let message = $state("Loading your account...");

  //The dependency comes from what the effect reads, not every $state declared in the component.
  $effect(() => {
    // Workspace changes unmount forms and clear any displayed secrets.
    const tenantId = selectedTenantId;
    selectedEndpoint = null;
    creatingEndpoint = false;
    showingTestEvent = false;
    copyMessage = '';
    panelTenantId = tenantId;
    void loadEndpoints();
  });
  onMount(() => {
    loadUser();
  });

  async function loadUser() {
    try {
      const response = await fetch("/api/auth/me");

      if (!response.ok) {
        message =
          response.status === 401
            ? "Please log in to view your dashboard."
            : "Unable to load your account.";
        return;
      }

      user = await response.json();
      selectedTenantId = user?.workspaces[0]?.tenantId ?? "";
    } catch {
      message = "Unable to connect. Please try again.";
    }
  }
  async function loadEndpoints(pageNumber = 0) {
    const requestId = ++endpointRequestId;
    const tenantId = selectedTenantId;
    endpointPage = null;
    endpointMessage = "";

    if (!tenantId) return;

    endpointMessage = "Loading endpoints...";

    try {
      const response = await fetch(`/api/endpoints?page=${pageNumber}&size=20`, {
        headers: {
          "X-Tenant-ID": tenantId,
        },
      });

      if (!response.ok) {
        throw new Error("Unable to load endpoints.");
      }

      const page: WebhookEndpointPage = await response.json();

      if (selectedTenantId === tenantId && requestId === endpointRequestId) {
        endpointPage = page;
        endpointMessage = "";
        if (selectedEndpoint && panelTenantId === tenantId) {
          selectedEndpoint = page.content.find(endpoint => endpoint.id === selectedEndpoint?.id) ?? selectedEndpoint;
        }
      }
    } catch {
      if (selectedTenantId === tenantId && requestId === endpointRequestId) {
        endpointMessage = "Unable to load endpoints. Please try again.";
      }
    }
  }

  async function copyEndpointValue(value: string, label: string) {
    const tenantId = selectedTenantId;
    const endpointId = selectedEndpoint?.id;
    try {
      await navigator.clipboard.writeText(value);
      if (selectedTenantId === tenantId && selectedEndpoint?.id === endpointId) copyMessage = `${label} copied.`;
    } catch {
      if (selectedTenantId === tenantId && selectedEndpoint?.id === endpointId) copyMessage = 'Unable to copy. Select and copy the value manually.';
    }
  }

  async function viewEventDeliveries() {
    showingTestEvent = false;
    await tick();
    const heading = document.getElementById('delivery-history-heading');
    heading?.scrollIntoView({ block: 'start' });
    heading?.focus({ preventScroll: true });
  }
</script>

<svelte:head><title>Workspace | NotifyFlow</title></svelte:head>

{#if user}
  <div class="app-shell">
    <a class="skip-link" href="#main-content">Skip to workspace content</a>
    <aside class="sidebar">
      <Brand />
      <div class="workspace-picker">
        <label for="workspace">Current workspace</label>
        <select id="workspace" bind:value={selectedTenantId}>
          <option value="" disabled>Select a workspace</option>
          {#each user.workspaces as workspace (workspace.tenantId)}
            <option value={workspace.tenantId}>{workspace.name}</option>
          {/each}
        </select>
      </div>
      <p class="sidebar-label">WORKSPACE</p>
      <nav aria-label="Workspace navigation">
        <a href="#endpoints"><Icon name="endpoints" size={18} />Endpoints</a>
        <a href="#deliveries"><Icon name="activity" size={18} />Delivery history</a>
        {#if selectedWorkspace?.role === 'ADMIN'}
          <a href="#api-keys"><Icon name="key" size={18} />Producer API keys</a>
        {/if}
      </nav>
      <p class="sidebar-note">A clearer view of every event.<br />From your application to theirs.</p>
      <div class="sidebar-account">
        <div class="account-info">
          <span class="avatar" aria-hidden="true">{user.email.charAt(0).toUpperCase()}</span>
          <span class="account-email" title={user.email}>{user.email}</span>
        </div>
        <LogoutButton />
      </div>
    </aside>

    <main class="dashboard-main" id="main-content">
      <div class="topbar">
        <div class="topbar-path"><span>Workspace</span><span>/</span><strong>Overview</strong></div>
        {#if selectedWorkspace}<StatusBadge status={selectedWorkspace.role} />{/if}
      </div>
      <header class="page-heading">
        <div>
          <span class="eyebrow">YOUR DELIVERY WORKSPACE</span>
          <h1>{selectedWorkspace?.name ?? 'Welcome to NotifyFlow'}</h1>
          <p>Connect your endpoints. Follow every delivery. Keep things flowing.</p>
        </div>
        {#if selectedWorkspace?.role === 'ADMIN'}
          <button type="button" class="button--primary" onclick={() => showingTestEvent = true}>Send test event<Icon name="arrow" size={15} /></button>
        {:else}
          <span class="heading-symbol"><Icon name="workspace" size={26} /></span>
        {/if}
      </header>

      {#if selectedWorkspace}
        {#if showingTestEvent && selectedWorkspace.role === 'ADMIN'}
          {#key selectedTenantId}
            <SidePanel title="Send test event" eyebrow="EVENT PUBLISHING" closeLabel="Close event panel" onClose={() => showingTestEvent = false}>
              <SendTestEvent tenantId={selectedTenantId} onAccepted={() => deliveryRefreshVersion++} onViewHistory={viewEventDeliveries} />
            </SidePanel>
          {/key}
        {/if}
        <div class="metric-grid">
          <article class="metric-card"><div><p>CONNECTED ENDPOINTS</p><strong>{endpointPage?.totalElements ?? '...'}</strong></div><span class="metric-icon"><Icon name="endpoints" /></span></article>
          <article class="metric-card"><div><p>YOUR WORKSPACE ROLE</p><strong class="metric-text">{selectedWorkspace.role.toLowerCase()}</strong></div><span class="metric-icon"><Icon name="key" /></span></article>
          <article class="metric-card"><div><p>YOUR WORKSPACES</p><strong>{user.workspaces.length}</strong></div><span class="metric-icon"><Icon name="workspace" /></span></article>
        </div>

        <section id="endpoints" class="content-section" aria-labelledby="endpoints-heading">
          <div class="section-heading">
            <div><h2 id="endpoints-heading"><span class="section-index">01</span>Webhook endpoints</h2><p>The destinations that receive your events.</p></div>
            <div class="section-actions">
              <button type="button" onclick={() => loadEndpoints(endpointPage?.page ?? 0)}><Icon name="refresh" size={14} />Refresh</button>
              {#if selectedWorkspace.role === 'ADMIN'}
                <button type="button" class="button--primary" onclick={() => { panelTenantId = selectedTenantId; creatingEndpoint = true; selectedEndpoint = null; }}><Icon name="plus" size={14} />New endpoint</button>
              {/if}
            </div>
          </div>
          <p role="status">{endpointMessage}</p>
          {#if endpointPage}
            {#if endpointPage.content.length}
              <div class="table-wrap endpoint-table-wrap">
                <table class="endpoint-table" aria-label="Webhook endpoints">
                  <thead><tr><th scope="col">Endpoint</th><th scope="col">Destination</th><th scope="col">Status</th><th scope="col"><span class="sr-only">Actions</span></th></tr></thead>
                  <tbody>
                    {#each endpointPage.content as endpoint (endpoint.id)}
                      <tr>
                        <td><span class="table-title">{endpoint.name}</span></td>
                        <td><span class="endpoint-table-url" title={endpoint.url}>{endpoint.url}</span></td>
                        <td><StatusBadge status={endpoint.enabled ? 'ENABLED' : 'DISABLED'} /></td>
                        <td class="endpoint-table-action"><button type="button" class="button--small" aria-label={`Manage ${endpoint.name}`} onclick={() => { selectedEndpoint = endpoint; panelTenantId = selectedTenantId; creatingEndpoint = false; copyMessage = ''; }}>Manage <Icon name="arrow" size={13} /></button></td>
                      </tr>
                    {/each}
                  </tbody>
                </table>
              </div>
            {:else}
              <p class="empty-state">No endpoints yet. Your workspace destinations will appear here.</p>
            {/if}
            <p class="list-meta">{endpointPage.totalElements} {endpointPage.totalElements === 1 ? 'endpoint' : 'endpoints'} in this workspace</p>
            {#if endpointPage.totalPages > 0}
              <nav aria-label="Endpoint pagination">
                <button type="button" disabled={endpointPage.page === 0} onclick={() => { if (endpointPage) void loadEndpoints(endpointPage.page - 1); }}>Previous</button>
                <span>Page {endpointPage.page + 1} of {endpointPage.totalPages}</span>
                <button type="button" disabled={endpointPage.page + 1 >= endpointPage.totalPages} onclick={() => { if (endpointPage) void loadEndpoints(endpointPage.page + 1); }}>Next</button>
              </nav>
            {/if}
          {/if}

          {#if panelTenantId === selectedTenantId}
            {#if creatingEndpoint && selectedWorkspace.role === 'ADMIN'}
              {#key selectedTenantId}
                <SidePanel eyebrow="WEBHOOK ENDPOINT" closeLabel="Close endpoint panel" title="New endpoint" onClose={() => creatingEndpoint = false}>
                  <CreateEndpointForm tenantId={selectedTenantId} onCreated={() => loadEndpoints()} />
                </SidePanel>
              {/key}
            {:else if selectedEndpoint}
              {#key `${selectedTenantId}:${selectedEndpoint.id}`}
                <SidePanel eyebrow="WEBHOOK ENDPOINT" closeLabel="Close endpoint panel" title={selectedEndpoint.name} onClose={() => { selectedEndpoint = null; copyMessage = ''; }}>
                  <div class="endpoint-panel-summary">
                    <StatusBadge status={selectedEndpoint.enabled ? 'ENABLED' : 'DISABLED'} />
                    {#if selectedWorkspace.role === 'ADMIN'}
                      <EndpointEnabledButton tenantId={selectedTenantId} endpoint={selectedEndpoint} onChanged={() => loadEndpoints(endpointPage?.page ?? 0)} />
                    {/if}
                  </div>
                  <div class="endpoint-panel-field">
                    <label for="managed-endpoint-url">Destination URL</label>
                    <div class="endpoint-copy-row"><input id="managed-endpoint-url" readonly value={selectedEndpoint.url} /><button type="button" class="button--small" onclick={() => copyEndpointValue(selectedEndpoint!.url, 'URL')}>Copy URL</button></div>
                  </div>
                  <div class="endpoint-panel-field">
                    <label for="managed-endpoint-id">Endpoint ID</label>
                    <div class="endpoint-copy-row"><input id="managed-endpoint-id" readonly value={selectedEndpoint.id} /><button type="button" class="button--small" onclick={() => copyEndpointValue(selectedEndpoint!.id, 'Endpoint ID')}>Copy ID</button></div>
                  </div>
                  <p role="status">{copyMessage}</p>
                  <EndpointSubscriptions tenantId={selectedTenantId} endpointId={selectedEndpoint.id} canManage={selectedWorkspace.role === 'ADMIN'} embedded />
                  {#if selectedWorkspace.role === 'ADMIN'}
                    <RotateEndpointSecret tenantId={selectedTenantId} endpointId={selectedEndpoint.id} embedded />
                  {/if}
                </SidePanel>
              {/key}
            {/if}
          {/if}
        </section>

        <div id="deliveries" class="content-section">
          {#key `${selectedWorkspace.tenantId}:${deliveryRefreshVersion}`}<DeliveryHistory tenantId={selectedWorkspace.tenantId} canReplay={selectedWorkspace.role === 'ADMIN'} />{/key}
        </div>
        {#if selectedWorkspace.role === 'ADMIN'}
          <div id="api-keys" class="content-section">
            {#key selectedWorkspace.tenantId}<ApiKeyManagement tenantId={selectedWorkspace.tenantId} />{/key}
          </div>
        {/if}
      {:else}
        <div class="panel empty-state">You don't belong to any workspaces yet.</div>
      {/if}
      <footer class="landing-footer">NotifyFlow &middot; Every event has a destination.</footer>
    </main>
  </div>
{:else}
  <main class="page-state panel"><Brand /><h1 style="margin-top: 28px">Your workspace awaits</h1><p role="status">{message}</p><a class="button button--primary" href="/login">Go to login</a></main>
{/if}
