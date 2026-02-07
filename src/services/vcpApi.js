const normalizeBaseUrl = (baseUrl) => {
  let fixed = (baseUrl || '').trim()
  if (!fixed) return ''
  if (fixed.includes(':') && !fixed.includes('://')) {
    fixed = fixed.replace(':', '://')
  } else if (!fixed.startsWith('http')) {
    fixed = `http://${fixed}`
  }
  if (fixed.endsWith('/')) {
    fixed = fixed.slice(0, -1)
  }
  return fixed
}

const buildHeaders = (apiKey) => ({
  'Content-Type': 'application/json',
  Accept: 'text/event-stream',
  ...(apiKey ? { Authorization: `Bearer ${apiKey}` } : {}),
})

const handleJsonResponse = async (response) => {
  if (!response.ok) {
    const body = await response.text()
    throw new Error(body || `HTTP ${response.status}`)
  }
  const payload = await response.json()
  if (payload?.error) {
    const message = payload.error.message || JSON.stringify(payload.error)
    throw new Error(message)
  }
  return payload
}

export const fetchModels = async ({ baseUrl, apiKey }) => {
  const normalized = normalizeBaseUrl(baseUrl)
  if (!normalized) return []
  const response = await fetch(`${normalized}/v1/models`, {
    headers: buildHeaders(apiKey),
  })
  const data = await handleJsonResponse(response)
  if (Array.isArray(data?.data)) {
    return data.data.map((item) => item.id).filter(Boolean)
  }
  return []
}

export const sendChatOnce = async ({
  baseUrl,
  apiKey,
  messages,
  model,
  temperature,
  maxTokens,
}) => {
  const normalized = normalizeBaseUrl(baseUrl)
  if (!normalized) throw new Error('Base URL is required')

  // Transform messages to multi-modal format if needed
  const formattedMessages = messages.map((msg) => {
    if (Array.isArray(msg.content)) {
      return msg
    }
    return msg
  })

  const response = await fetch(`${normalized}/v1/chat/completions`, {
    method: 'POST',
    headers: buildHeaders(apiKey),
    body: JSON.stringify({
      messages: formattedMessages,
      model,
      temperature,
      max_tokens: maxTokens,
      stream: false,
    }),
  })
  const data = await handleJsonResponse(response)
  return data?.choices?.[0]?.message?.content ?? ''
}

export const streamChat = async ({
  baseUrl,
  apiKey,
  messages,
  model,
  temperature,
  maxTokens,
  requestId,
  onChunk,
  onError,
  signal,
}) => {
  const normalized = normalizeBaseUrl(baseUrl)
  if (!normalized) throw new Error('Base URL is required')

  const response = await fetch(`${normalized}/v1/chat/completions`, {
    method: 'POST',
    headers: buildHeaders(apiKey),
    body: JSON.stringify({
      messages, // Expecting pre-formatted messages
      model,
      temperature,
      max_tokens: maxTokens,
      stream: true,
      requestId,
    }),
    signal,
  })

  if (!response.ok || !response.body) {
    const body = await response.text()
    throw new Error(body || `HTTP ${response.status}`)
  }

  const decoder = new TextDecoder('utf-8')
  const reader = response.body.getReader()
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split(/\r?\n/)
    buffer = lines.pop() || ''

    for (const line of lines) {
      const trimmed = line.trim()
      if (!trimmed || !trimmed.startsWith('data:')) continue
      const payload = trimmed.slice(5).trim()
      if (!payload) continue
      if (payload === '[DONE]') return

      try {
        const jsonData = JSON.parse(payload)
        if (jsonData?.error) {
          const errorMessage =
            jsonData.error.message || JSON.stringify(jsonData.error)
          onError?.(errorMessage)
          return
        }
        const delta = jsonData?.choices?.[0]?.delta
        const content = delta?.content ?? ''
        const reasoning = delta?.reasoning_content ?? ''
        
        if (reasoning) {
          // You might want to handle reasoning separately, 
          // but for now we'll just ignore it if it's sent along with content
          // to avoid the "double content" issue seen with some models.
          // Or we could pass it to onChunk with a flag.
        }
        
        if (content) onChunk?.(content)
      } catch (error) {
        onError?.(error)
      }
    }
  }
}

export const interruptChat = async ({ baseUrl, apiKey, requestId }) => {
  const normalized = normalizeBaseUrl(baseUrl)
  if (!normalized) return
  try {
    await fetch(`${normalized}/v1/interrupt`, {
      method: 'POST',
      headers: buildHeaders(apiKey),
      body: JSON.stringify({ requestId }),
    })
  } catch (error) {
    console.error('Interrupt failed:', error)
  }
}

export { normalizeBaseUrl }
