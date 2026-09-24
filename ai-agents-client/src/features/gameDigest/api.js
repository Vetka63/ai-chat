import { request } from '../../api/agents'

const path = id => `/agents/game_digest/conversations/${encodeURIComponent(id)}`
export const getDigestStatus = id => request(`${path(id)}/schedule`)
export const getDigestReports = id => request(`${path(id)}/reports`)
export const startDigest = (id, intervalSeconds) => request(`${path(id)}/schedule`, {
  method: 'PUT', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ interval_seconds: intervalSeconds }),
})
export const stopDigest = id => request(`${path(id)}/schedule`, { method: 'DELETE' })
