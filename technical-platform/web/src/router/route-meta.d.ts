import 'vue-router'
declare module 'vue-router' { interface RouteMeta { requiresAuth?:boolean; guestOnly?:boolean; permission?:string; permissionsAny?:string[]; pageTitle?:string; navigationState?:string } }
