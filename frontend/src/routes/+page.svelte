<script lang="ts">
  import Brand from '#lib/components/Brand.svelte';
  import Icon from '#lib/components/Icon.svelte';
</script>

<svelte:head><title>NotifyFlow &middot; Your delivery workspace</title><meta name="description" content="Connect webhook endpoints, follow delivery attempts, and manage your event delivery workspace." /></svelte:head>
<header class="landing-header"><Brand /><nav aria-label="Main navigation"><a href="/login">Sign in</a><a class="button button--primary" href="/register">Get started</a></nav></header>
<main>
  <section class="landing-hero"><span class="eyebrow">EVENTS IN MOTION. YOU IN CONTROL.</span><h1>A clearer view of<br />every delivery.</h1><p>Connect your endpoints, follow your webhook deliveries, and give every event a place to land.</p><a class="button button--primary" href="/dashboard">Open your workspace<Icon name="arrow" size={16} /></a></section>
  <section class="landing-features" aria-label="NotifyFlow features">
    <article><Icon name="endpoints" size={24} /><h2>Connect your destinations</h2><p>Manage endpoints and subscribe them to the events they need.</p></article>
    <article><Icon name="activity" size={24} /><h2>Follow the whole journey</h2><p>Inspect delivery history and HTTP attempts, and replay failed deliveries.</p></article>
    <article><Icon name="key" size={24} /><h2>Your workspace, your control</h2><p>Manage producer keys, signing secrets, and workspace access in one place.</p></article>
  </section>
</main>
<footer class="landing-footer">NotifyFlow &middot; Every event has a destination.</footer>
