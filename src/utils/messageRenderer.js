import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { autoCompleteHtml, hasVcpRoot, hasExecutableScript } from './htmlAutoComplete'

marked.setOptions({
  breaks: true,
})

const BUBBLE_CSS_BLOCK_REGEX =
  /(?:```(?:css)?\s*\r?\n)?\s*<<<\[VCP_BUBBLE_CSS\]>>>([\s\S]*?)<<<\[END_VCP_BUBBLE_CSS\]>>>\s*(?:\r?\n\s*```)?/g
const STYLE_TAG_REGEX = /<style\b[^>]*>([\s\S]*?)<\/style>/gi
const CODE_FENCE_REGEX = /```[\s\S]*?```/g

const TOOL_REGEX = /<<<\[TOOL_REQUEST\]>>>(.*?)<<<\[END_TOOL_REQUEST\]>>>/gs
const NOTE_REGEX = /<<<DailyNoteStart>>>(.*?)<<<DailyNoteEnd>>>/gs
const TOOL_RESULT_REGEX = /\[\[VCP调用结果信息汇总:(.*?)VCP调用结果结束\]\]/gs
const BUTTON_CLICK_REGEX = /\[\[点击按钮:(.*?)\]\]/gs
const CANVAS_PLACEHOLDER_REGEX = /\{\{VCPChatCanvas\}\}/g
const THOUGHT_CHAIN_REGEX =
  /\[--- VCP元思考链(?::\s*"([^"]*)")?\s*---\]([\s\S]*?)\[--- 元思考链结束 ---\]/gs

const escapeAttr = (value = '') =>
  String(value)
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')

const makeSafeId = (value = '') =>
  String(value)
    .replace(/[^a-zA-Z0-9_-]/g, '_')
    .slice(-24)

const extractBubbleCss = (text) => {
  if (typeof text !== 'string') return { text: '', css: '' }
  const blocks = []
  const stripped = text.replace(BUBBLE_CSS_BLOCK_REGEX, (_, css) => {
    const cleanedCss = css.replace(/^```css\n/i, '').replace(/\n```$/i, '')
    blocks.push(cleanedCss)
    return ''
  })
  return { text: stripped, css: blocks.join('\n\n') }
}

// Extract <style> tags from content (matching desktop VCPChat behavior)
// Must be called AFTER protecting code blocks to avoid false matches
const extractStyleTags = (text) => {
  if (typeof text !== 'string') return { text: '', css: '' }
  const blocks = []
  const stripped = text.replace(STYLE_TAG_REGEX, (_, css) => {
    blocks.push(css.trim())
    return ''
  })
  return { text: stripped, css: blocks.join('\n') }
}

const sanitizeBubbleCss = (css = '') => {
  if (typeof css !== 'string') return ''
  let cleaned = css.trim()
  if (!cleaned) return ''

  if (cleaned.length > 20000) cleaned = cleaned.slice(0, 20000)
  const lowered = cleaned.toLowerCase()

  if (lowered.includes('@import')) return ''
  if (lowered.includes('@font-face')) return ''
  if (lowered.includes('expression(')) return ''
  if (lowered.includes('javascript:') || lowered.includes('vbscript:')) return ''
  if (lowered.includes('<') || lowered.includes('>')) return ''
  if (/position\s*:\s*fixed\b/i.test(cleaned)) return ''

  // Remove unsafe url() usages (allow http(s) and data:image)
  cleaned = cleaned.replace(/url\s*\(\s*(['"]?)([^'"\)]+)\1\s*\)/gi, (match, quote, url) => {
    const value = String(url || '').trim()
    if (value.startsWith('https://') || value.startsWith('http://')) return match
    if (value.startsWith('data:image/')) return match
    return 'url("")'
  })

  // Block a few known-dangerous legacy properties
  if (/(^|[;\s])behavior\s*:/i.test(cleaned)) return ''
  if (/(^|[;\s])-moz-binding\s*:/i.test(cleaned)) return ''

  return cleaned
}

const splitSelectors = (selectorText) => {
  const selectors = []
  let current = ''
  let depthParen = 0
  let depthBracket = 0
  let inString = null
  for (let i = 0; i < selectorText.length; i++) {
    const ch = selectorText[i]
    if (inString) {
      current += ch
      if (ch === inString && selectorText[i - 1] !== '\\') inString = null
      continue
    }
    if (ch === '"' || ch === "'") {
      inString = ch
      current += ch
      continue
    }
    if (ch === '(') depthParen++
    if (ch === ')') depthParen = Math.max(0, depthParen - 1)
    if (ch === '[') depthBracket++
    if (ch === ']') depthBracket = Math.max(0, depthBracket - 1)
    if (ch === ',' && depthParen === 0 && depthBracket === 0) {
      selectors.push(current.trim())
      current = ''
      continue
    }
    current += ch
  }
  if (current.trim()) selectors.push(current.trim())
  return selectors
}

const prefixSelectors = (selectorText, scopeSelector) => {
  const selectors = splitSelectors(selectorText)
  return selectors
    .map((selector) => {
      if (!selector) return ''
      // Handle :bubble placeholder
      if (selector.includes(':bubble')) {
        return selector.replace(/:bubble/g, scopeSelector)
      }
      // Replace #vcp-root with scoped selector (desktop VCPChat compatibility)
      if (selector.includes('#vcp-root')) {
        return selector.replace(/#vcp-root/g, scopeSelector)
      }
      if (selector.startsWith(scopeSelector)) return selector
      return `${scopeSelector} ${selector}`
    })
    .filter(Boolean)
    .join(', ')
}

const findMatchingBrace = (css, openIndex) => {
  let depth = 0
  let inString = null
  for (let i = openIndex; i < css.length; i++) {
    const ch = css[i]
    const next = css[i + 1]

    // comments
    if (!inString && ch === '/' && next === '*') {
      const end = css.indexOf('*/', i + 2)
      if (end === -1) return css.length - 1
      i = end + 1
      continue
    }

    if (inString) {
      if (ch === inString && css[i - 1] !== '\\') inString = null
      continue
    }

    if (ch === '"' || ch === "'") {
      inString = ch
      continue
    }

    if (ch === '{') depth++
    if (ch === '}') {
      depth--
      if (depth === 0) return i
    }
  }
  return css.length - 1
}

const renameKeyframes = (css, suffix) => {
  const nameMap = new Map()
  let renamed = css
  renamed = renamed.replace(/@(-webkit-)?keyframes\s+([a-zA-Z0-9_-]+)/g, (match, webkit, name) => {
    const newName = `${name}__${suffix}`
    nameMap.set(name, newName)
    return `@${webkit || ''}keyframes ${newName}`
  })

  if (nameMap.size === 0) return renamed

  // Only replace keyframe names in animation declarations
  const segments = renamed.split(';')
  const updated = segments
    .map((segment) => {
      const idx = segment.indexOf(':')
      if (idx === -1) return segment
      const prop = segment.slice(0, idx).trim().toLowerCase()
      if (prop !== 'animation' && prop !== 'animation-name') return segment
      let value = segment.slice(idx + 1)
      for (const [oldName, newName] of nameMap.entries()) {
        value = value.replace(new RegExp(`\\b${oldName}\\b`, 'g'), newName)
      }
      return segment.slice(0, idx + 1) + value
    })
    .join(';')

  return updated
}

const scopeCssBlock = (css, scopeSelector) => {
  let out = ''
  let i = 0

  while (i < css.length) {
    // skip whitespace
    if (/\s/.test(css[i])) {
      out += css[i]
      i++
      continue
    }

    // at-rules
    if (css[i] === '@') {
      const nextBrace = css.indexOf('{', i)
      const nextSemi = css.indexOf(';', i)

      if (nextSemi !== -1 && (nextBrace === -1 || nextSemi < nextBrace)) {
        out += css.slice(i, nextSemi + 1)
        i = nextSemi + 1
        continue
      }

      if (nextBrace === -1) {
        out += css.slice(i)
        break
      }

      const header = css.slice(i, nextBrace).trim()
      const close = findMatchingBrace(css, nextBrace)
      const inner = css.slice(nextBrace + 1, close)

      if (/^@(-webkit-)?keyframes\b/i.test(header)) {
        out += `${header}{${inner}}`
      } else if (/^@(media|supports|layer|container)\b/i.test(header)) {
        out += `${header}{${scopeCssBlock(inner, scopeSelector)}}`
      } else {
        out += `${header}{${inner}}`
      }

      i = close + 1
      continue
    }

    // normal rules
    const nextBrace = css.indexOf('{', i)
    if (nextBrace === -1) {
      out += css.slice(i)
      break
    }

    const selectorText = css.slice(i, nextBrace).trim()
    const close = findMatchingBrace(css, nextBrace)
    const body = css.slice(nextBrace + 1, close)
    const scopedSelectors = prefixSelectors(selectorText, scopeSelector)
    out += `${scopedSelectors}{${body}}`
    i = close + 1
  }

  return out
}

const scopeBubbleCss = ({ css, messageId }) => {
  const safeId = makeSafeId(messageId)
  const scopeSelector = `.vcp-bubble-scope[data-vcp-bubble="${safeId}"]`
  const renamed = renameKeyframes(css, safeId)
  return scopeCssBlock(renamed, scopeSelector)
}

const ensureBubbleStyleTag = (messageId) => {
  const id = `vcp-bubble-css-${makeSafeId(messageId)}`
  let el = document.getElementById(id)
  if (!el) {
    el = document.createElement('style')
    el.id = id
    el.type = 'text/css'
    document.head.appendChild(el)
  }
  return el
}

const removeBubbleStyleTag = (messageId) => {
  const id = `vcp-bubble-css-${makeSafeId(messageId)}`
  const el = document.getElementById(id)
  if (el && el.parentNode) el.parentNode.removeChild(el)
}

export const cleanupAllBubbleStyles = () => {
  document.querySelectorAll('style[id^="vcp-bubble-css-"]').forEach((el) => el.remove())
}

const escapeHtml = (text = '') =>
  text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;')

const processStartEndMarkers = (text) => {
  if (typeof text !== 'string' || !text.includes('「始」')) return text
  return text.replace(/「始」([\s\S]*?)(「末」|$)/g, (_, content, end) => {
    return `「始」${escapeHtml(content)}${end}`
  })
}

const ensureNewlineAfterCodeBlock = (text) => {
  if (typeof text !== 'string') return text
  return text.replace(/^(\s*```)(?![\r\n])/gm, '$1\n')
}

const ensureSeparatorBetweenImgAndCode = (text) => {
  if (typeof text !== 'string') return text
  return text.replace(
    /(<img[^>]+>)\s*(```)/g,
    '$1\n\n<!-- VCP-Renderer-Separator -->\n\n$2'
  )
}

const deIndentToolRequestBlocks = (text) => {
  if (typeof text !== 'string') return text
  const lines = text.split('\n')
  let inToolBlock = false
  return lines
    .map((line) => {
      const isStart = line.includes('<<<[TOOL_REQUEST]>>>')
      const isEnd = line.includes('<<<[END_TOOL_REQUEST]>>>')
      if (isStart) inToolBlock = true
      const processed = inToolBlock ? line.trimStart() : line
      if (isEnd) inToolBlock = false
      return processed
    })
    .join('\n')
}

const transformUserButtonClick = (text) =>
  text.replace(BUTTON_CLICK_REGEX, (_, content) => {
    const escaped = escapeHtml(content.trim())
    return `<span class="user-clicked-button-bubble">${escaped}</span>`
  })

const transformVCPChatCanvas = (text) =>
  text.replace(
    CANVAS_PLACEHOLDER_REGEX,
    () =>
      '<div class="vcp-chat-canvas-placeholder">Canvas syncing<span class="thinking-indicator-dots">...</span></div>'
  )

const transformSpecialBlocks = (text) => {
  let processed = text

  processed = processed.replace(TOOL_RESULT_REGEX, (_, rawContent) => {
    const content = rawContent.trim()
    const lines = content.split('\n')

    let toolName = 'Unknown Tool'
    let status = 'Unknown Status'
    const details = []
    const otherContent = []

    let currentKey = null
    let currentValue = []

    lines.forEach((line) => {
      const kvMatch = line.match(/^\-\s*([^:]+):\s*(.*)/)
      if (kvMatch) {
        if (currentKey) {
          const val = currentValue.join('\n').trim()
          if (currentKey === '工具名称') {
            toolName = val
          } else if (currentKey === '执行状态') {
            status = val
          } else {
            details.push({ key: currentKey, value: val })
          }
        }
        currentKey = kvMatch[1].trim()
        currentValue = [kvMatch[2].trim()]
      } else if (currentKey) {
        currentValue.push(line)
      } else if (line.trim()) {
        otherContent.push(line)
      }
    })

    if (currentKey) {
      const val = currentValue.join('\n').trim()
      if (currentKey === '工具名称') {
        toolName = val
      } else if (currentKey === '执行状态') {
        status = val
      } else {
        details.push({ key: currentKey, value: val })
      }
    }

    let html = '<div class="vcp-tool-result-bubble collapsible">'
    html += '<div class="vcp-tool-result-header">'
    html += '<span class="vcp-tool-result-label">VCP-ToolResult</span>'
    html += `<span class="vcp-tool-result-name">${escapeHtml(toolName)}</span>`
    html += `<span class="vcp-tool-result-status">${escapeHtml(status)}</span>`
    html += '<span class="vcp-result-toggle-icon"></span>'
    html += '</div>'
    html += '<div class="vcp-tool-result-collapsible-content">'

    html += '<div class="vcp-tool-result-details">'
    details.forEach(({ key, value }) => {
      const isMarkdownField =
        key === '返回内容' ||
        key === '内容' ||
        key === 'Result' ||
        key === '返回结果' ||
        key === 'output'
      const isImageUrl =
        typeof value === 'string' &&
        value.match(/^https?:\/\/[^\s]+\.(jpeg|jpg|png|gif|webp)$/i)
      let processedValue

      if (isImageUrl && (key === '可访问URL' || key === '返回内容' || key === 'url')) {
        processedValue = `<a href="${value}" target="_blank" rel="noopener noreferrer"><img src="${value}" class="vcp-tool-result-image" alt="Generated"></a>`
      } else if (isMarkdownField) {
        processedValue = marked.parse(value)
      } else {
        const urlRegex = /(https?:\/\/[^\s]+)/g
        processedValue = escapeHtml(value)
        processedValue = processedValue.replace(
          urlRegex,
          '<a href="$1" target="_blank" rel="noopener noreferrer">$1</a>'
        )
      }

      html += '<div class="vcp-tool-result-item">'
      html += `<span class="vcp-tool-result-item-key">${escapeHtml(key)}:</span>`
      const valueTag = isMarkdownField && !isImageUrl ? 'div' : 'span'
      html += `<${valueTag} class="vcp-tool-result-item-value">${processedValue}</${valueTag}>`
      html += '</div>'
    })
    html += '</div>'

    if (otherContent.length > 0) {
      const footerText = otherContent.join('\n')
      const footerHtml = marked.parse(footerText)
      html += `<div class="vcp-tool-result-footer">${footerHtml}</div>`
    }

    html += '</div>'
    html += '</div>'

    return html
  })

  processed = processed.replace(TOOL_REGEX, (_, content) => {
    const isDailyNoteCreate =
      /tool_name:\s*「始」\s*DailyNote\s*「末」/.test(content) &&
      /command:\s*「始」\s*create\s*「末」/.test(content)

    if (isDailyNoteCreate) {
      const maidRegex = /(?:maid|maidName):\s*「始」([^「」]*)「末」/
      const dateRegex = /Date:\s*「始」([^「」]*)「末」/
      const contentRegex = /Content:\s*「始」([\s\S]*?)「末」/

      const maidMatch = content.match(maidRegex)
      const dateMatch = content.match(dateRegex)
      const contentMatch = content.match(contentRegex)

      const maid = maidMatch ? maidMatch[1].trim() : ''
      const date = dateMatch ? dateMatch[1].trim() : ''
      const diaryContent = contentMatch
        ? contentMatch[1].trim()
        : '[Diary content unavailable]'

      let html = '<div class="maid-diary-bubble">'
      html += '<div class="diary-header">'
      html += '<span class="diary-title">Maid\'s Diary</span>'
      if (date) {
        html += `<span class="diary-date">${escapeHtml(date)}</span>`
      }
      html += '</div>'

      if (maid) {
        html += '<div class="diary-maid-info">'
        html += '<span class="diary-maid-label">Maid:</span>'
        html += `<span class="diary-maid-name">${escapeHtml(maid)}</span>`
        html += '</div>'
      }

      const processedDiary = marked.parse(diaryContent)
      html += `<div class="diary-content">${processedDiary}</div>`
      html += '</div>'
      return html
    }

    const toolNameRegex = /<tool_name>([\s\S]*?)<\/tool_name>|tool_name:\s*「始」([^「」]*)「末」/
    const toolNameMatch = content.match(toolNameRegex)
    let toolName = 'Processing...'
    if (toolNameMatch) {
      let extractedName = (toolNameMatch[1] || toolNameMatch[2] || '').trim()
      if (extractedName) {
        extractedName = extractedName.replace(/「始」|「末」/g, '').replace(/,$/, '').trim()
      }
      if (extractedName) {
        toolName = extractedName
      }
    }

    const escapedFullContent = escapeHtml(content)
    return (
      '<div class="vcp-tool-use-bubble">' +
      '<div class="vcp-tool-summary">' +
      '<span class="vcp-tool-label">VCP-ToolUse:</span>' +
      `<span class="vcp-tool-name-highlight">${escapeHtml(toolName)}</span>` +
      '</div>' +
      `<div class="vcp-tool-details"><pre>${escapedFullContent}</pre></div>` +
      '</div>'
    )
  })

  processed = processed.replace(NOTE_REGEX, (_, rawContent) => {
    const content = rawContent.trim()
    const maidRegex = /Maid:\s*([^\n\r]*)/
    const dateRegex = /Date:\s*([^\n\r]*)/
    const contentRegex = /Content:\s*([\s\S]*)/

    const maidMatch = content.match(maidRegex)
    const dateMatch = content.match(dateRegex)
    const contentMatch = content.match(contentRegex)

    const maid = maidMatch ? maidMatch[1].trim() : ''
    const date = dateMatch ? dateMatch[1].trim() : ''
    const diaryContent = contentMatch ? contentMatch[1].trim() : content

    let html = '<div class="maid-diary-bubble">'
    html += '<div class="diary-header">'
    html += '<span class="diary-title">Maid\'s Diary</span>'
    if (date) {
      html += `<span class="diary-date">${escapeHtml(date)}</span>`
    }
    html += '</div>'

    if (maid) {
      html += '<div class="diary-maid-info">'
      html += '<span class="diary-maid-label">Maid:</span>'
      html += `<span class="diary-maid-name">${escapeHtml(maid)}</span>`
      html += '</div>'
    }

    const processedDiary = marked.parse(diaryContent)
    html += `<div class="diary-content">${processedDiary}</div>`
    html += '</div>'

    return html
  })

  processed = processed.replace(THOUGHT_CHAIN_REGEX, (_, theme, rawContent) => {
    const displayTheme = theme ? theme.trim() : 'Thought Chain'
    const content = rawContent.trim()
    const processedContent = marked.parse(content)

    let html = '<div class="vcp-thought-chain-bubble collapsible">'
    html += '<div class="vcp-thought-chain-header">'
    html += '<span class="vcp-thought-chain-icon">🧠</span>'
    html += `<span class="vcp-thought-chain-label">${escapeHtml(displayTheme)}</span>`
    html += '<span class="vcp-result-toggle-icon"></span>'
    html += '</div>'
    html += '<div class="vcp-thought-chain-collapsible-content">'
    html += `<div class="vcp-thought-chain-body">${processedContent}</div>`
    html += '</div>'
    html += '</div>'

    return html
  })

  return processed
}

const fixStickerUrls = (text, baseUrl, imageKey) => {
  if (!text || !baseUrl) return text
  const normalizedBase = baseUrl.replace(/\/$/, '')
  const authPrefix = imageKey ? `/pw=${imageKey}/images` : ''
  
  // 1. Replace localhost/127.0.0.1 variants
  let fixed = text.replace(/http:\/\/(localhost|127\.0\.0\.1):6005/g, normalizedBase)
  
  // 2. Handle specific domains that the user might have used in the past (like iepose.cn)
  // or any full URL that points to a VCP sticker path /pw=
  fixed = fixed.replace(/https?:\/\/[^\s"'<>]+\/pw=/g, `${normalizedBase}/pw=`)
  
  // 3. Ensure relative paths starting with /pw= are also prefixed (if AI outputs relative path)
  fixed = fixed.replace(/(src=["'])\/pw=/g, `$1${normalizedBase}/pw=`)
  
  // 4. Fix sticker relative paths containing '表情包' — insert auth prefix
  if (authPrefix) {
    fixed = fixed.replace(/(src=["'])\/([^"']*表情包[^"']*)/g, `$1${normalizedBase}${authPrefix}/$2`)
    fixed = fixed.replace(/(!\[[^\]]*\]\()\/((?:[^)]*表情包[^)]*)\))/g, `$1${normalizedBase}${authPrefix}/$2)`)
  }
  
  // 5. Fix other relative src paths by prepending baseUrl
  fixed = fixed.replace(/(src=["'])\/((?!\/|https?:|pw=)[^"']*)/g, `$1${normalizedBase}/$2`)
  
  // 6. Fix markdown image relative paths ![alt](/path)
  fixed = fixed.replace(/(!\[[^\]]*\]\()\/((?!\/|https?:)[^)]*\))/g, `$1${normalizedBase}/$2`)
  
  return fixed
}

export const renderMessageHtml = (text = '', options = {}) => {
  const { messageId, allowBubbleCss, role, baseUrl, imageKey, isStreaming } = options || {}
  const bubbleScopeId = messageId ? makeSafeId(messageId) : ''
  
  let processed = text

  // === 富文本沙箱渲染 ===
  // 完成后的消息如果包含 vcp-root + 可执行脚本，标记为沙箱占位容器
  if (messageId && role === 'assistant' && !isStreaming && hasVcpRoot(processed) && hasExecutableScript(processed)) {
    return `<div class="vcp-sandbox-container" data-sandbox-id="${bubbleScopeId}"></div>`
  }

  // 流式渲染时如果检测到 vcp-root，使用 HTML 自动补全确保标签闭合
  if (isStreaming && hasVcpRoot(processed)) {
    processed = autoCompleteHtml(processed)
  }
  
  if (baseUrl) {
    processed = fixStickerUrls(processed, baseUrl, imageKey)
  }

  processed = processStartEndMarkers(processed)
  processed = ensureNewlineAfterCodeBlock(processed)
  processed = ensureSeparatorBetweenImgAndCode(processed)
  processed = deIndentToolRequestBlocks(processed)
  processed = transformUserButtonClick(processed)
  processed = transformVCPChatCanvas(processed)

  let allCss = ''
  let hasCustomBubble = false

  if (allowBubbleCss && messageId && role === 'assistant') {
    // --- Step 1: Protect code blocks (same as desktop VCPChat) ---
    const codeBlocks = []
    let protectedText = processed.replace(CODE_FENCE_REGEX, (match) => {
      const placeholder = `__VCP_CODE_PROTECT_${codeBlocks.length}__`
      codeBlocks.push(match)
      return placeholder
    })

    // --- Step 2: Extract <style> tags (desktop VCPChat primary method) ---
    const styleExtracted = extractStyleTags(protectedText)
    protectedText = styleExtracted.text
    allCss += styleExtracted.css

    // --- Step 3: Also extract <<<[VCP_BUBBLE_CSS]>>> blocks (mobile fallback) ---
    const bubbleExtracted = extractBubbleCss(protectedText)
    protectedText = bubbleExtracted.text
    if (bubbleExtracted.css) {
      allCss += '\n' + bubbleExtracted.css
    }

    // --- Step 4: Restore code blocks ---
    codeBlocks.forEach((block, i) => {
      protectedText = protectedText.replace(`__VCP_CODE_PROTECT_${i}__`, block)
    })
    processed = protectedText

    // Detect custom bubble: any div with id/class or inline style
    hasCustomBubble = /<div\s+[^>]*(id|class|style)=["'][^"']+["']/i.test(processed)

    // --- Step 5: Sanitize and inject CSS ---
    const sanitizedCss = sanitizeBubbleCss(allCss)
    if (sanitizedCss) {
      const scopedCss = scopeBubbleCss({ css: sanitizedCss, messageId })
      try {
        const styleEl = ensureBubbleStyleTag(messageId)
        styleEl.textContent = scopedCss
      } catch (e) {}
    } else if (!isStreaming) {
      removeBubbleStyleTag(messageId)
    }
  } else {
    if (messageId) removeBubbleStyleTag(messageId)
  }

  // Always transform special blocks to avoid raw markers showing in chat
  processed = transformSpecialBlocks(processed)

  const rawHtml = marked.parse(processed)
  const sanitized = DOMPurify.sanitize(rawHtml, {
    USE_PROFILES: { html: true },
    ADD_ATTR: ['class', 'target', 'rel', 'data-mermaid-code', 'style', 'id', 'onclick'],
    ADD_TAGS: ['style', 'button']
  })

  if (messageId) {
    const hasCssOrBubble = !!(allCss || hasCustomBubble)
    const scopeClass = hasCssOrBubble && !isStreaming ? 'vcp-bubble-scope custom-bubble-active' : 'vcp-bubble-scope'
    return `<div class="${scopeClass}" data-vcp-bubble="${bubbleScopeId}"><div class="vcp-bubble-root" id="${bubbleScopeId}">${sanitized}</div></div>`
  }
  return sanitized
}
