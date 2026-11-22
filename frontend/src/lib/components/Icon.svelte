<script lang="ts">
	let { name, size = 20 }: { name: 'endpoints' | 'activity' | 'key' | 'arrow' | 'refresh' | 'plus' | 'workspace'; size?: number } = $props();
	const paths = {
		endpoints: 'M8 6H6a2 2 0 0 0-2 2v2m12-4h2a2 2 0 0 1 2 2v2M8 18H6a2 2 0 0 1-2-2v-2m12 4h2a2 2 0 0 0 2-2v-2M9 9h6v6H9z',
		activity: 'M3 12h4l3-8 4 16 3-8h4',
		key: 'M14 7a5 5 0 1 1-4.9 6H3v-3h2V8h4.1A5 5 0 0 1 14 7Zm2 4h.01',
		arrow: 'M5 12h14m-6-6 6 6-6 6',
		refresh: 'M20 7v5h-5M4 17v-5h5M6 7a7 7 0 0 1 12-1l2 2M4 16l2 2a7 7 0 0 0 12-1',
		plus: 'M12 5v14M5 12h14',
		workspace: 'M4 21V5a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v16M2 21h20M8 7h1m6 0h1M8 11h1m6 0h1M10 21v-6h4v6'
	};
</script>

<svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
	<path d={paths[name]} />
</svg>
