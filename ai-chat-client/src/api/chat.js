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
