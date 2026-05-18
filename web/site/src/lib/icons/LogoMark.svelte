<script lang="ts">
  interface Props { size?: number; }
  let { size = 24 }: Props = $props();
</script>

<svg width={size} height={size} viewBox="0 0 512 512" fill="none" aria-hidden="true">
  <defs>
    <linearGradient id="lm-bond" x1="0" y1="1" x2="1" y2="0">
      <stop offset="0" stop-color="#d4e8ff"/>
      <stop offset="0.5" stop-color="#4a90e2"/>
      <stop offset="1" stop-color="#0d3a8a"/>
    </linearGradient>
  </defs>
  <path
    fill="url(#lm-bond)"
    fill-rule="evenodd"
    d="M168 139 a14 14 0 0 1 14 -14 H244 a116 80 0 0 1 0 160 V396 a14 14 0 0 1 -14 14 H182 a14 14 0 0 1 -14 -14 Z M244 175 a45 30 0 0 1 0 60 Z"
  />
</svg>
