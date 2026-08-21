<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const moduleLabel = computed(() => typeof route.query.label === 'string' ? route.query.label : '该模块')
const groupLabel = computed(() => typeof route.query.group === 'string' ? route.query.group : '导航功能')
</script>

<template>
  <section class="navigation-developing" aria-labelledby="navigation-developing-title">
    <div class="navigation-developing__mark" aria-hidden="true">
      <svg viewBox="0 0 24 24"><path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1" /><circle cx="12" cy="12" r="3" /></svg>
    </div>
    <span class="navigation-developing__eyebrow">{{ groupLabel }}</span>
    <h2 id="navigation-developing-title">{{ moduleLabel }}正在开发中</h2>
    <p>导航入口已经按正式信息架构开放；该页面尚未接通真实业务组件、后端服务或数据库读模型，因此不会展示模拟数据或伪造完成状态。</p>
    <div class="navigation-developing__actions">
      <button type="button" @click="router.back()">返回上一页</button>
      <RouterLink to="/">返回工作台</RouterLink>
    </div>
  </section>
</template>

<style scoped>
.navigation-developing {
  min-height: min(560px, 64vh);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
  border: 1px solid var(--sgj-border);
  border-radius: var(--sgj-radius-xl);
  background: linear-gradient(180deg, #fff, color-mix(in srgb, var(--sgj-brand-50) 52%, #fff));
  box-shadow: var(--sgj-shadow-sm);
  text-align: center;
}
.navigation-developing__mark {
  width: 76px; height: 76px; display: grid; place-items: center;
  border-radius: 24px; color: var(--sgj-brand-600); background: var(--sgj-brand-50);
  box-shadow: 0 0 0 9px color-mix(in srgb, var(--sgj-brand-50) 56%, transparent);
}
.navigation-developing__mark svg { width: 34px; height: 34px; fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; }
.navigation-developing__eyebrow { margin-top: 24px; color: var(--sgj-brand-600); font-size: 13px; font-weight: 750; letter-spacing: .08em; }
.navigation-developing h2 { margin: 8px 0 0; font-size: clamp(25px, 3vw, 34px); letter-spacing: -.025em; }
.navigation-developing p { max-width: 620px; margin: 14px 0 0; color: var(--sgj-text-secondary); font-size: 15px; line-height: 1.8; }
.navigation-developing__actions { display: flex; gap: 10px; margin-top: 26px; flex-wrap: wrap; justify-content: center; }
.navigation-developing__actions :is(button, a) {
  min-height: 44px; display: inline-flex; align-items: center; justify-content: center; padding: 0 18px;
  border: 1px solid var(--sgj-border-strong); border-radius: var(--sgj-radius-md); color: var(--sgj-text-primary);
  background: #fff; font: inherit; font-weight: 650; text-decoration: none; cursor: pointer;
}
.navigation-developing__actions a { color: #fff; border-color: var(--sgj-brand-600); background: var(--sgj-brand-600); }
</style>
