<script setup lang="ts">
import type { PortalDefinition } from './portal-config'

defineProps<{ portal: PortalDefinition }>()
</script>

<template>
  <div class="platform-shell" :data-portal-code="portal.code" :data-home-role="portal.code">
    <header class="phase-header">
      <div>
        <p class="eyebrow">上金谷平台 · {{ portal.title }}</p>
        <h2>{{ portal.title }}运行壳</h2>
        <p class="lead">{{ portal.description }}</p>
      </div>
      <aside class="phase-status" aria-label="当前平台能力">
        <strong>当前已接入的平台能力</strong>
        <span>登录与会话恢复、身份切换、权限投影和来源化导航已经接线；未施工业务页面与业务数据不会用静态内容代替。</span>
      </aside>
    </header>

    <section class="principle-card" aria-label="本端工作定位">
      <h2>本端工作定位</h2>
      <ul>
        <li v-for="focus in portal.homeFocus" :key="focus">{{ focus }}</li>
      </ul>
    </section>

    <section class="principle-card" aria-label="运行壳数据原则">
      <h2>运行壳数据原则</h2>
      <ul>
        <li>业务状态、待办、消息、搜索与 KPI 只有在存在真实服务端 read model 时才展示。</li>
        <li>导航只激活已实现 Route、服务端权限和 mobile_access 同时允许的入口。</li>
        <li>身份切换后重新读取 SessionView/permissions，不复用旧身份授权快照。</li>
        <li>服务端权限、数据范围、字段范围、RLS 与审计始终是最终安全边界。</li>
      </ul>
    </section>
  </div>
</template>
