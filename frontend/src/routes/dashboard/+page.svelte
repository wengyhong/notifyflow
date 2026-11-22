<script lang="ts">
  import Brand from '#lib/components/Brand.svelte';
  import Icon from '#lib/components/Icon.svelte';
  import StatusBadge from '#lib/components/StatusBadge.svelte';
  let selectedTenantId = $state("");
  import CreateEndpointForm from "#lib/components/CreateEndpointForm.svelte";
  import ApiKeyManagement from '#lib/components/ApiKeyManagement.svelte';
  import DeliveryHistory from '#lib/components/DeliveryHistory.svelte';
  import EndpointSubscriptions from '#lib/components/EndpointSubscriptions.svelte';
  import { onMount } from "svelte";
  import type { WebhookEndpointPage } from "#lib/types/webhook-endpoint.ts";
  import type { CurrentUser } from "#lib/types/current-user.ts";
  import EndpointEnabledButton from "#lib/components/EndpointEnabledButton.svelte";
  import RotateEndpointSecret from '#lib/components/RotateEndpointSecret.svelte';
  import LogoutButton from '#lib/components/LogoutButton.svelte';
  let endpointPage = $state<WebhookEndpointPage | null>(null);
  let endpointMessage = $state("");
  let endpointRequestId = 0;
  let user = $state<CurrentUser | null>(null);
  let selectedWorkspace = $derived(
    user?.workspaces.find(
      (workspace) => workspace.tenantId === selectedTenantId,
    ),
  );
  let message = $state("Loading your account...");

  //The dependency comes from what the effect reads, not every $state declared in the component.
  $effect(() => {
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
      }
    } catch {
      if (selectedTenantId === tenantId && requestId === endpointRequestId) {
        endpointMessage = "Unable to load endpoints. Please try again.";
      }
    }
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
        <span class="heading-symbol"><Icon name="workspace" size={26} /></span>
      </header>

      {#if selectedWorkspace}
        <div class="metric-grid">
          <article class="metric-card"><div><p>CONNECTED ENDPOINTS</p><strong>{endpointPage?.totalElements ?? '...'}</strong></div><span class="metric-icon"><Icon name="endpoints" /></span></article>
          <article class="metric-card"><div><p>YOUR WORKSPACE ROLE</p><strong class="metric-text">{selectedWorkspace.role.toLowerCase()}</strong></div><span class="metric-icon"><Icon name="key" /></span></article>
          <article class="metric-card"><div><p>YOUR WORKSPACES</p><strong>{user.workspaces.length}</strong></div><span class="metric-icon"><Icon name="workspace" /></span></article>
        </div>

        <section id="endpoints" class="content-section" aria-labelledby="endpoints-heading">
          <div class="section-heading">
            <div><h2 id="endpoints-heading"><span class="section-index">01</span>Webhook endpoints</h2><p>The destinations that receive your events.</p></div>
            <button type="button" onclick={() => loadEndpoints()}><Icon name="refresh" size={14} />Refresh</button>
          </div>
          <p role="status">{endpointMessage}</p>
          <div class="endpoint-layout" class:endpoint-layout--readonly={selectedWorkspace.role !== 'ADMIN'}>
            <div>
              {#if endpointPage}
                <ul class="endpoint-grid">
                  {#each endpointPage.content as endpoint (endpoint.id)}
                    <li class="endpoint-card">
                      <div class="endpoint-card-heading">
                        <div class="endpoint-name"><span class="endpoint-symbol"><Icon name="endpoints" size={17} /></span><h3>{endpoint.name}</h3></div>
                        <StatusBadge status={endpoint.enabled ? 'ENABLED' : 'DISABLED'} />
                      </div>
                      <code class="endpoint-url">{endpoint.url}</code>
                      {#if selectedWorkspace.role === 'ADMIN'}
                        <div class="endpoint-actions"><EndpointEnabledButton tenantId={selectedWorkspace.tenantId} {endpoint} onChanged={loadEndpoints} /></div>
                        {#key selectedWorkspace.tenantId}<RotateEndpointSecret tenantId={selectedWorkspace.tenantId} endpointId={endpoint.id} />{/key}
                      {/if}
                      {#key selectedWorkspace.tenantId}<EndpointSubscriptions tenantId={selectedWorkspace.tenantId} endpointId={endpoint.id} canManage={selectedWorkspace.role === 'ADMIN'} />{/key}
                    </li>
                  {:else}
                    <li class="empty-state">No endpoints yet. Your workspace destinations will appear here.</li>
                  {/each}
                </ul>
                <p class="list-meta">{endpointPage.totalElements} {endpointPage.totalElements === 1 ? 'endpoint' : 'endpoints'} in this workspace</p>
                {#if endpointPage.totalPages > 0}
                  <nav aria-label="Endpoint pagination">
                    <button type="button" disabled={endpointPage.page === 0} onclick={() => { if (endpointPage) void loadEndpoints(endpointPage.page - 1); }}>Previous</button>
                    <span>Page {endpointPage.page + 1} of {endpointPage.totalPages}</span>
                    <button type="button" disabled={endpointPage.page + 1 >= endpointPage.totalPages} onclick={() => { if (endpointPage) void loadEndpoints(endpointPage.page + 1); }}>Next</button>
                  </nav>
                {/if}
              {/if}
            </div>
            {#if selectedWorkspace.role === 'ADMIN'}
              <div class="panel form-panel">
                <span class="panel-icon"><Icon name="plus" size={18} /></span>
                {#key selectedWorkspace.tenantId}<CreateEndpointForm tenantId={selectedWorkspace.tenantId} onCreated={loadEndpoints} />{/key}
              </div>
            {/if}
          </div>
        </section>

        <div id="deliveries" class="content-section">
          {#key selectedWorkspace.tenantId}<DeliveryHistory tenantId={selectedWorkspace.tenantId} canReplay={selectedWorkspace.role === 'ADMIN'} />{/key}
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
