export interface Project {
  id: string
  name: string
  goal: string
  progress: number
  status: string
  owner: string
}

export interface Health {
  status: string
  service: string
}

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path)
  if (!response.ok) {
    throw new Error(`${path} returned ${response.status}`)
  }
  return response.json() as Promise<T>
}

export function fetchHealth(): Promise<Health> {
  return getJson<Health>('/api/health')
}

export function fetchProjects(): Promise<Project[]> {
  return getJson<Project[]>('/api/projects')
}
