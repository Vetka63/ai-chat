async function readPayload(response) {
  try {
    return await response.json()
  } catch {
    return {}
  }
}

export async function getChatProfiles() {
  const response = await fetch('/api/profiles')
  const payload = await readPayload(response)

  if (!response.ok) {
    throw new Error(payload.error || 'Не удалось загрузить профили чата')
  }

  return payload
}

export async function sendChatMessage({
  message,
  profileId = 'general',
  responseMode = 'free',
  history = [],
}) {
  const response = await fetch('/api/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ message, profileId, responseMode, history }),
  })

  const payload = await readPayload(response)

  if (!response.ok) {
    throw new Error(payload.error || 'Не удалось получить ответ от сервера')
  }

  return payload
}

export async function runReasoningExperiment({
  task,
  profileId = 'day3-reasoning',
  strategies = [],
}) {
  const response = await fetch('/api/reasoning-experiments', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ task, profileId, strategies }),
  })

  const payload = await readPayload(response)

  if (!response.ok) {
    throw new Error(payload.error || 'Не удалось выполнить эксперимент')
  }

  return payload
}

export async function judgeReasoningExperiment({ task, profileId, candidates }) {
  const response = await fetch('/api/reasoning-experiments/judge', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ task, profileId, candidates }),
  })

  const payload = await readPayload(response)

  if (!response.ok) {
    throw new Error(payload.error || 'Не удалось получить оценку DeepSeek')
  }

  return payload
}

export async function runTemperatureExperiment({
  task,
  profileId = 'day4-temperature',
}) {
  const response = await fetch('/api/temperature-experiments', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ task, profileId }),
  })

  const payload = await readPayload(response)

  if (!response.ok) {
    throw new Error(payload.error || 'Не удалось выполнить температурный эксперимент')
  }

  return payload
}

export async function judgeTemperatureExperiment({ task, profileId, candidates }) {
  const response = await fetch('/api/temperature-experiments/judge', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ task, profileId, candidates }),
  })

  const payload = await readPayload(response)

  if (!response.ok) {
    throw new Error(payload.error || 'Не удалось получить оценку температурного эксперимента')
  }

  return payload
}
