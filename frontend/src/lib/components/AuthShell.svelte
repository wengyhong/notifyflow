<script lang="ts">
	import type { Snippet } from 'svelte';
	import Brand from '#lib/components/Brand.svelte';
	import Icon from '#lib/components/Icon.svelte';
	let { title, description, eyebrow, children }: {
		title: string; description: string; eyebrow: string; children: Snippet;
	} = $props();
</script>

<main class="auth-shell">
	<aside class="auth-story">
		<Brand />
		<div class="auth-story-content">
			<span class="eyebrow">A LITTLE LESS GUESSWORK</span>
			<h2>Your webhooks.<br />Under control.</h2>
			<p>One workspace to connect your endpoints, follow every delivery, and keep things moving.</p>
			<div class="flow-preview" aria-label="Example webhook delivery flow">
				<div class="flow-step"><span class="flow-icon"><Icon name="activity" /></span><div><small>01 / EVENT</small><strong>order.created</strong></div><span class="flow-check">✓</span></div>
				<div class="flow-connector"></div>
				<div class="flow-step"><span class="flow-icon"><Icon name="endpoints" /></span><div><small>02 / ROUTE</small><strong>Endpoint matched</strong></div><span class="flow-check">✓</span></div>
				<div class="flow-connector"></div>
				<div class="flow-step"><span class="flow-icon"><Icon name="arrow" /></span><div><small>03 / DELIVER</small><strong>Delivered · HTTP 204</strong></div><span class="flow-check">✓</span></div>
			</div>
		</div>
		<p class="auth-story-footer">Built for the events that matter.</p>
	</aside>
	<section class="auth-main">
		<div class="auth-card">
			<span class="eyebrow">{eyebrow}</span>
			<h1>{title}</h1>
			<p class="auth-description">{description}</p>
			{@render children()}
		</div>
		<p class="auth-main-footer">NotifyFlow · Your delivery workspace</p>
	</section>
</main>
