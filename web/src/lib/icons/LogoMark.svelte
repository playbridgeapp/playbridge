<script lang="ts">
  interface Props { size?: number; }
  let { size = 24 }: Props = $props();
</script>

<svg width={size} height={size} viewBox="0 0 32 32" fill="none" aria-hidden="true">
  <path d="M3 22 Q 16 6, 29 22" stroke="#9ea7ff" stroke-width="1.5" stroke-linecap="round" fill="none" />
  <circle cx="3" cy="22" r="1.6" fill="#9ea7ff" />
  <circle cx="29" cy="22" r="1.6" fill="#9ea7ff" />
  <path d="M14 12.5 L20 16 L14 19.5 Z" fill="#9ea7ff" />
</svg>
