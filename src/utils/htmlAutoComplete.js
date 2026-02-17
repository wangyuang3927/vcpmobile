/**
 * HTML 自动补全状态机 (Streaming Differential Parser)
 * 
 * 在流式渲染时，AI 输出的 HTML 可能有未闭合的标签。
 * 此模块追踪标签栈，自动补全闭合标签，确保实时预览不崩溃。
 */

const VOID_ELEMENTS = new Set([
  'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input',
  'link', 'meta', 'param', 'source', 'track', 'wbr',
])

const RAW_TEXT_ELEMENTS = new Set(['script', 'style', 'textarea'])

/**
 * 解析部分 HTML，返回补全后的完整 HTML。
 * @param {string} partial - 流式接收到的部分 HTML
 * @returns {string} 补全闭合标签后的 HTML
 */
export function autoCompleteHtml(partial) {
  if (!partial) return ''

  const tagStack = []
  let i = 0
  const len = partial.length

  while (i < len) {
    // 处于原始文本元素 (script/style) 内部，找对应闭合标签
    if (tagStack.length > 0 && RAW_TEXT_ELEMENTS.has(tagStack[tagStack.length - 1])) {
      const currentRaw = tagStack[tagStack.length - 1]
      const closeTag = `</${currentRaw}`
      const closeIdx = partial.toLowerCase().indexOf(closeTag, i)
      if (closeIdx === -1) {
        // 未找到闭合，跳到末尾
        break
      }
      // 找到闭合标签的 >
      const gtIdx = partial.indexOf('>', closeIdx + closeTag.length)
      if (gtIdx === -1) break
      tagStack.pop()
      i = gtIdx + 1
      continue
    }

    if (partial[i] !== '<') {
      i++
      continue
    }

    // HTML 注释
    if (partial.substring(i, i + 4) === '<!--') {
      const commentEnd = partial.indexOf('-->', i + 4)
      if (commentEnd === -1) {
        // 未闭合注释
        return partial + ' -->' + closeTags(tagStack)
      }
      i = commentEnd + 3
      continue
    }

    // DOCTYPE / processing instruction
    if (partial[i + 1] === '!' || partial[i + 1] === '?') {
      const gt = partial.indexOf('>', i + 1)
      if (gt === -1) break
      i = gt + 1
      continue
    }

    const tagStart = i
    i++ // skip '<'

    // 闭合标签
    const isClosing = partial[i] === '/'
    if (isClosing) i++

    // 读取标签名
    if (i >= len || !/[a-zA-Z]/.test(partial[i])) {
      // 不是有效标签，跳过
      continue
    }

    let tagName = ''
    while (i < len && /[a-zA-Z0-9\-]/.test(partial[i])) {
      tagName += partial[i]
      i++
    }
    tagName = tagName.toLowerCase()

    // 跳过属性，找 >
    let foundClose = false
    let selfClosed = false
    let attrQuote = null

    while (i < len) {
      const c = partial[i]

      if (attrQuote) {
        if (c === attrQuote) attrQuote = null
        i++
        continue
      }

      if (c === '"' || c === "'") {
        attrQuote = c
        i++
        continue
      }

      if (c === '/') {
        if (i + 1 < len && partial[i + 1] === '>') {
          selfClosed = true
          i += 2
          foundClose = true
          break
        }
        i++
        continue
      }

      if (c === '>') {
        i++
        foundClose = true
        break
      }

      i++
    }

    if (!foundClose) {
      // 标签属性未写完，补全引号和标签闭合
      let suffix = attrQuote ? attrQuote : ''
      suffix += '>'
      if (!isClosing && !selfClosed && !VOID_ELEMENTS.has(tagName)) {
        tagStack.push(tagName)
      }
      return partial + suffix + closeTags(tagStack)
    }

    if (selfClosed || VOID_ELEMENTS.has(tagName)) {
      continue
    }

    if (isClosing) {
      // 从栈中弹出匹配的标签（容错：跳过不匹配的）
      const idx = tagStack.lastIndexOf(tagName)
      if (idx !== -1) {
        tagStack.splice(idx)
      }
    } else {
      tagStack.push(tagName)
    }
  }

  return partial + closeTags(tagStack)
}

function closeTags(stack) {
  if (stack.length === 0) return ''
  return [...stack].reverse().map(t => `</${t}>`).join('')
}

/**
 * 检测内容是否包含 vcp-root 富文本模式
 * @param {string} content - 消息内容
 * @returns {boolean}
 */
export function hasVcpRoot(content) {
  if (!content) return false
  return /<div[^>]*\bid\s*=\s*["']?vcp-root["']?/i.test(content)
}

/**
 * 检测内容是否包含需要沙箱执行的脚本
 * @param {string} content - 消息内容
 * @returns {boolean}
 */
export function hasExecutableScript(content) {
  if (!content) return false
  // <script> 标签 或 onclick/oninput 等事件属性
  return /<script[\s>]/i.test(content) || /\bon\w+\s*=/i.test(content)
}
