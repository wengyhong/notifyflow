<script lang="ts">
	let { href = '/' }: { href?: string } = $props();
</script>

<a {href} class="brand" aria-label="NotifyFlow home">
	<span class="brand-mark" aria-hidden="true">
		<svg width="23" height="23" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
			<path d="M5 17V7l14 10V7M5 12h14" />
		</svg>
	</span>
	<span>Notify<span class="brand-light">Flow</span></span>
</a>
