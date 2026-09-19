import { request } from '../../api/agents'

const base = (a, c) => '/agents/' + encodeURIComponent(a) + '/conversations/' + encodeURIComponent(c) + '/memory'
const json = (body, method = 'POST') => ({ method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
export const getMemory = (a, c) => request(base(a, c))
export const saveProblem = (a, c, body) => request(base(a, c) + '/problem', json(body, 'PUT'))
export const saveEntry = (a, c, body) => request(base(a, c) + '/entries', json(body))
export const deleteEntry = (a, c, id, body) => request(base(a, c) + '/entries/' + encodeURIComponent(id) + '/delete', json(body))
export const proposeMemory = (a, c, body) => request(base(a, c) + '/proposals', json(body))
export const resolveProposal = (a, c, id, body) => request(base(a, c) + '/proposals/' + encodeURIComponent(id), json(body))
