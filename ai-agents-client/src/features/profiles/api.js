import { request } from '../../api/agents'
const json = (body, method) => ({ method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
export const listProfiles = () => request('/profiles')
export const createProfile = body => request('/profiles', json(body, 'POST'))
export const updateProfile = (id, body) => request('/profiles/' + encodeURIComponent(id), json(body, 'PUT'))
