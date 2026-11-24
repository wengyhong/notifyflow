<script lang="ts">
  import { onMount, type Snippet } from 'svelte';

  let { title, eyebrow = 'DETAILS', closeLabel = 'Close details panel', children, onClose }: {
    title: string;
    eyebrow?: string;
    closeLabel?: string;
    children: Snippet;
    onClose: () => void;
  } = $props();
  const titleId = $props.id();
  let dialog: HTMLDialogElement;

  onMount(() => {
    const opener = document.activeElement;
    dialog.showModal();
    return () => {
      dialog.close();
      if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
    };
  });
</script>

<dialog class="side-panel" bind:this={dialog} onclose={onClose} aria-labelledby={titleId}>
  <div class="side-panel-header">
    <div><span class="eyebrow">{eyebrow}</span><h2 id={titleId}>{title}</h2></div>
    <button type="button" class="button--small" onclick={() => dialog.close()} aria-label={closeLabel}>Close <span aria-hidden="true">&times;</span></button>
  </div>
  <div class="side-panel-body">{@render children()}</div>
</dialog>
