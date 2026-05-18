<script lang="ts">
  interface Props { size?: number; }
  let { size = 24 }: Props = $props();
</script>

<svg width={size} height={size} viewBox="0 0 1024 1024" fill="none" aria-hidden="true">
  <g transform="rotate(-13 512 512)" fill="none" stroke="#1a3fa6"
     stroke-width="170" stroke-linecap="round" stroke-linejoin="round">
    <path d="M 332 772 L 332 252"/>
    <path d="M 332 252 L 552 252 C 692 252 772 332 772 422
             C 772 512 692 582 552 582 L 332 582"/>
  </g>
</svg>
