<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <header class="topbar">
    <div class="brand">
      <span class="brand-mark">ATP</span>
      <span class="brand-name">inbound-atp</span>
      <span class="brand-sub">available-to-promise · inbound arrival prediction</span>
    </div>
    <nav>
      <RouterLink to="/">Availability</RouterLink>
      <RouterLink to="/inbound">Inbound</RouterLink>
      <RouterLink to="/exceptions">Exceptions</RouterLink>
      <RouterLink to="/lanes">Lanes</RouterLink>
    </nav>
  </header>
  <div class="synthetic-banner" role="note">
    Synthetic demo data: simulated suppliers, carriers, warehouse and customers. Not Article data.
  </div>
  <main class="page">
    <RouterView />
  </main>
</template>
