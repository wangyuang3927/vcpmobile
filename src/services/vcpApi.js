import { logger } from '../utils/debugLogger'

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
  logger.info('API', `GET ${normalized}/v1/models`)
  try {
    const response = await fetch(`${normalized}/v1/models`, {
      headers: buildHeaders(apiKey),
    })
    logger.info('API', `Models response: ${response.status}`)
    const data = await handleJsonResponse(response)
    if (Array.isArray(data?.data)) {
      const models = data.data.map((item) => item.id).filter(Boolean)
      logger.info('API', `Fetched ${models.length} models`)
      return models
    }
    return []
  } catch (error) {
    logger.error('API', `fetchModels failed: ${error.message}`, { url: `${normalized}/v1/models` })
    throw error
  }
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
  onReasoning,
  onError,
  signal,
}) => {
  const normalized = normalizeBaseUrl(baseUrl)
  if (!normalized) throw new Error('Base URL is required')

  logger.info('API', `POST ${normalized}/v1/chat/completions (stream)`, { model, temperature, maxTokens, msgCount: messages.length })
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
    logger.error('API', `Stream chat failed: HTTP ${response.status}`, { body: body?.slice(0, 200) })
    throw new Error(body || `HTTP ${response.status}`)
  }
  logger.info('API', `Stream started: ${response.status}`)

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
        
        if (reasoning) onReasoning?.(reasoning)
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
    logger.info('API', `POST ${normalized}/v1/interrupt`, { requestId })
    await fetch(`${normalized}/v1/interrupt`, {
      method: 'POST',
      headers: buildHeaders(apiKey),
      body: JSON.stringify({ requestId }),
    })
    logger.info('API', 'Interrupt sent')
  } catch (error) {
    logger.error('API', `Interrupt failed: ${error.message}`)
  }
}

export { normalizeBaseUrl }
