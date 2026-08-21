import { createMemoryHistory } from 'vue-router'
import { describe,expect,it } from 'vitest'
import { PORTALS } from '../platform/portal-config'
import { createPortalRouter,type PortalRouterSession } from './portal-router'
class Session implements PortalRouterSession{authenticated=true;permissions=new Set<string>();restore(){return Promise.resolve(true)}can(p:string){return this.permissions.has(p)}}
describe('PHASE-10 P007 frozen routes and permissions',()=>{
  it('protects three employee routes with read/change',async()=>{for(const path of ['/employee/04/01/01','/employee/03/01/09','/employee/03/01/10']){const s=new Session();s.permissions.add('p007.schedule.change');const r=createPortalRouter(PORTALS.work,s,createMemoryHistory());await r.push(path);expect(String(r.currentRoute.value.name)).toMatch(/^p007-/)}})
  it('protects three center scheduling routes with manage/review',async()=>{for(const path of ['/center/04/01/01','/center/04/07/05']){const s=new Session();s.permissions.add('p007.schedule.manage');const r=createPortalRouter(PORTALS.work,s,createMemoryHistory());await r.push(path);expect(String(r.currentRoute.value.name)).toMatch(/^p007-/)}const s=new Session();s.permissions.add('p007.schedule.review');const r=createPortalRouter(PORTALS.work,s,createMemoryHistory());await r.push('/center/04/07/04');expect(r.currentRoute.value.name).toBe('p007-shift-review')})
  it('adds frozen P007 monitor to shared tech route',async()=>{const s=new Session();s.permissions.add('p007.schedule.monitor');const r=createPortalRouter(PORTALS.tech,s,createMemoryHistory());await r.push('/tech/05/03/01');expect(r.currentRoute.value.name).toBe('p004-workflow-instance-monitor')})
})
