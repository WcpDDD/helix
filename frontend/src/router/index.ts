import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'
import { useProjectStore } from '@/stores/projects'
import { useSessionStore } from '@/stores/session'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
    },
    {
      path: '/',
      name: 'home',
      component: HomeView,
    },
    {
      path: '/project',
      name: 'project',
      component: () => import('../views/ProjectView.vue'),
    },
    {
      path: '/members',
      name: 'members',
      component: () => import('../views/MembersView.vue'),
    },
    {
      path: '/agents',
      name: 'agents',
      component: () => import('../views/AgentView.vue'),
    },
    {
      path: '/tasks/:code/spec',
      name: 'task-spec',
      component: () => import('../views/SpecView.vue'),
    },
    {
      path: '/collab',
      name: 'collab',
      component: () => import('../views/CollabView.vue'),
    },
  ],
})

router.beforeEach(async (to) => {
  const session = useSessionStore()
  if (!session.ready) {
    await session.load()
  }
  if (to.name === 'login') {
    return session.user ? { path: '/' } : true
  }
  if (!session.user) {
    const authError = to.query.auth_error
    return {
      name: 'login',
      query: typeof authError === 'string' ? { auth_error: authError } : {},
    }
  }
  const projects = useProjectStore()
  if (!projects.ready) {
    await projects.load()
  }
  return true
})

export default router
