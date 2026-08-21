<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { ApiClientError } from '../api'
import { SgjStatusChip } from '../design-system'
import PortalNavigation from '../router/PortalNavigation.vue'
import { projectActiveNavigation, projectNavigationGroups, resolveActiveItem, type NavigationRouteAccessRule } from '../router/navigation-projection'
import { safeInternalRedirect } from '../router/redirect'
import { usePortalSessionStore } from '../session'
import UnifiedPortalShell from '../shared/layout/rebuild/UnifiedPortalShell.vue'
import type { PortalDefinition } from './portal-config'
import PortalSessionHeader from './PortalSessionHeader.vue'
const props=defineProps<{portal:PortalDefinition}>(),session=usePortalSessionStore(),route=useRoute(),router=useRouter(),sessionNotice=ref(''),sessionRequestId=ref<string|undefined>()
const records=computed(()=>router.getRoutes()),implemented=computed(()=>new Set(records.value.map(r=>r.path))),rules=computed<ReadonlyMap<string,NavigationRouteAccessRule>>(()=>{const m=new Map<string,NavigationRouteAccessRule>();for(const r of records.value){const all=typeof r.meta.permission==='string'?[r.meta.permission]:[];const any=Array.isArray(r.meta.permissionsAny)?r.meta.permissionsAny.filter((x):x is string=>typeof x==='string'):[];if(all.length||any.length)m.set(r.path,{all,any})}return m}),permissions=computed(()=>new Set(session.session?.permissions??[]))
const identityLabel=computed(()=>{const s=session.session;if(!s)return null;return s.availableIdentities.find(i=>i.identityId===s.identityId)?.identityName??null})
const navigationItems=computed(()=>projectActiveNavigation({runtimeCode:props.portal.code,permissions:permissions.value,implementedRoutePaths:implemented.value,mobile:false}))
const navigationGroups=computed(()=>projectNavigationGroups({runtimeCode:props.portal.code,permissions:permissions.value,implementedRoutePaths:implemented.value,routeAccessRules:rules.value,mobile:false,identityLabel:identityLabel.value}))
const pageTitle=computed(()=>{if(typeof route.meta.pageTitle==='string')return route.meta.pageTitle;if((route.path==='/developing'||route.path==='/forbidden')&&typeof route.query.label==='string')return route.query.label;if(route.path==='/')return props.portal.homeTitle;return resolveActiveItem(navigationGroups.value,route.path,route.query.module)?.label??props.portal.homeTitle})
function showFailure(c:unknown){sessionNotice.value='会话操作未完成，请检查网络或权限后重试。';sessionRequestId.value=c instanceof ApiClientError?c.requestId:undefined}
async function switchIdentity(id:string){sessionNotice.value='';try{await session.switchIdentity(id);const p=typeof route.meta.permission==='string'?route.meta.permission:undefined;if(p&&!session.can(p))await router.replace({name:'forbidden'})}catch(c){showFailure(c)}}
async function logout(){let ok=true;try{await session.logout()}catch{ok=false}await router.replace({name:'login',query:ok?{}:{notice:'logout-unconfirmed'}})}
watch(()=>session.phase,phase=>{if(phase==='expired'||phase==='signed_out')void router.replace({name:'login',query:{redirect:safeInternalRedirect(route.fullPath),notice:phase}})})
</script>
<template><UnifiedPortalShell :portal="props.portal" :page-title="pageTitle" :search-items="navigationItems" :alert-visible="Boolean(sessionNotice)"><template #header><PortalSessionHeader v-if="session.session" :portal="props.portal" :session="session.session" :switching="session.phase==='switching'" @switch-identity="switchIdentity" @logout="logout"/><SgjStatusChip v-else tone="warning">会话状态：{{session.phase}}</SgjStatusChip></template><template #globalAlert><div role="alert"><strong>{{sessionNotice}}</strong><span v-if="sessionRequestId"> Request ID: {{sessionRequestId}}</span></div></template><template #sidebar><PortalNavigation :runtime-code="props.portal.code" :identity-label="identityLabel"/></template><template #bottomNav><PortalNavigation :runtime-code="props.portal.code" :identity-label="identityLabel" mobile/></template><RouterView/></UnifiedPortalShell></template>
