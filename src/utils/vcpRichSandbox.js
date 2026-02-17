/**
 * VCP Rich Sandbox — iframe 沙箱渲染器
 * 
 * 当 AI 输出包含 <div id="vcp-root"> 且含有 <script> 或交互事件时，
 * 使用 iframe 沙箱渲染完整 HTML/CSS/JS 内容，确保：
 * 1. 样式完全隔离（不污染主界面）
 * 2. 脚本可安全执行（anime.js, three.js 等）
 * 3. 通过 input(text) 桥接函数与聊天输入框交互
 */

const CDN_LIBS = [
  'https://cdn.jsdelivr.net/npm/animejs@3.2.2/lib/anime.min.js',
  'https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.min.js',
]

// 活跃沙箱映射：messageId → iframe element
const activeSandboxes = new Map()

/**
 * 构建 iframe 的 srcdoc 内容
 * @param {string} htmlContent - 消息中的原始 HTML 内容
 * @param {string} baseUrl - VCP 后端地址（用于修复贴纸路径）
 * @returns {string} 完整的 HTML 文档字符串
 */
function buildSrcdoc(htmlContent, baseUrl) {
  const cdnScripts = CDN_LIBS.map(url => `<script src="${url}"><\/script>`).join('\n  ')

  return `<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  ${cdnScripts}
  <style>
    * { box-sizing: border-box; }
    html, body {
      margin: 0;
      padding: 0;
      background: transparent;
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
      color: #e0e0e0;
      overflow: hidden;
    }
    #vcp-root {
      padding: 8px;
      word-wrap: break-word;
      overflow-wrap: break-word;
    }
    img { max-width: 100%; height: auto; }
    pre { overflow-x: auto; max-width: 100%; }
  </style>
</head>
<body>
  ${htmlContent}
  <script>
    // === Event Bridge ===
    function input(text) {
      window.parent.postMessage({ type: 'vcp-input', text: text }, '*');
    }

    // === Auto-resize: 通知父窗口调整 iframe 高度 ===
    let _lastH = 0;
    function notifyResize() {
      // 使用子元素实际底部位置，避免 100vh 等视口单位导致循环膨胀
      let h = 0;
      const children = document.body.children;
      for (let i = 0; i < children.length; i++) {
        const rect = children[i].getBoundingClientRect();
        if (rect.bottom > h) h = rect.bottom;
      }
      h = Math.ceil(h);
      if (h < 60) h = 60;
      // 去重：高度未变化时不发送
      if (Math.abs(h - _lastH) < 2) return;
      _lastH = h;
      window.parent.postMessage({ type: 'vcp-resize', height: h }, '*');
    }

    // 初始 resize + MutationObserver 监控动态内容
    requestAnimationFrame(notifyResize);
    new MutationObserver(notifyResize).observe(document.body, {
      childList: true, subtree: true, attributes: true, characterData: true
    });
    window.addEventListener('resize', notifyResize);

    // 图片加载完成后也需要 resize
    document.querySelectorAll('img').forEach(img => {
      img.addEventListener('load', notifyResize);
    });

    // 定期检查（防止动画改变高度后不触发 resize）
    setInterval(notifyResize, 2000);
  <\/script>
</body>
</html>`
}

/**
 * 从消息内容中提取完整的 vcp-root HTML 块（含 style 标签）
 * @param {string} content - 消息原始内容
 * @returns {string|null} 提取的 HTML 内容，或 null（非 vcp-root 消息）
 */
function extractVcpRootHtml(content) {
  if (!content) return null
  // 检测是否包含 vcp-root
  if (!/<div[^>]*\bid\s*=\s*["']?vcp-root["']?/i.test(content)) return null

  // 提取所有 <style> 标签
  const styleTags = []
  const withoutStyles = content.replace(/<style\b[^>]*>([\s\S]*?)<\/style>/gi, (match) => {
    styleTags.push(match)
    return ''
  })

  // 查找 vcp-root 的 div 块
  const rootMatch = withoutStyles.match(/<div[^>]*\bid\s*=\s*["']?vcp-root["']?[\s\S]*/i)
  if (!rootMatch) return null

  // 组合 style + root div
  return styleTags.join('\n') + '\n' + rootMatch[0]
}

/**
 * 为指定消息创建沙箱 iframe
 * @param {string} messageId - 消息 ID
 * @param {string} content - 消息原始内容
 * @param {HTMLElement} container - 挂载容器
 * @param {string} baseUrl - VCP 后端 base URL
 * @returns {boolean} 是否成功创建沙箱
 */
export function mountSandbox(messageId, content, container, baseUrl, imageKey) {
  const html = extractVcpRootHtml(content)
  if (!html) return false

  // 如果已有沙箱，先销毁
  unmountSandbox(messageId)

  const iframe = document.createElement('iframe')
  iframe.className = 'vcp-rich-sandbox'
  iframe.setAttribute('sandbox', 'allow-scripts allow-same-origin')
  iframe.setAttribute('frameborder', '0')
  iframe.setAttribute('scrolling', 'no')
  iframe.style.cssText = 'width:100%;border:none;overflow:hidden;min-height:60px;background:transparent;display:block;'

  // 修复贴纸路径（与 messageRenderer fixStickerUrls 保持一致）
  let fixedHtml = html
  if (baseUrl) {
    const normalizedBase = baseUrl.replace(/\/$/, '')
    // 1. localhost/127.0.0.1 替换
    fixedHtml = fixedHtml.replace(/http:\/\/(localhost|127\.0\.0\.1):6005/g, normalizedBase)
    // 2. 任意域名的 /pw= 路径统一替换为当前 baseUrl
    fixedHtml = fixedHtml.replace(/https?:\/\/[^\s"'<>]+\/pw=/g, `${normalizedBase}/pw=`)
    // 3. 相对路径 src="/pw=..." 补全域名
    fixedHtml = fixedHtml.replace(/(src=["'])\/pw=/g, `$1${normalizedBase}/pw=`)
    // 4. 表情包路径注入认证前缀
    if (imageKey) {
      const authPrefix = `/pw=${imageKey}/images`
      fixedHtml = fixedHtml.replace(/(src=["'])\/([^"']*表情包[^"']*)/g, `$1${normalizedBase}${authPrefix}/$2`)
    }
    // 5. 其他相对路径补全域名
    fixedHtml = fixedHtml.replace(/(src=["'])\/((?!\/|https?:|pw=|data:)[^"']*)/g, `$1${normalizedBase}/$2`)
  }

  iframe.srcdoc = buildSrcdoc(fixedHtml, baseUrl)

  // 清空容器并挂载
  container.innerHTML = ''
  container.appendChild(iframe)

  activeSandboxes.set(messageId, iframe)
  return true
}

/**
 * 销毁指定消息的沙箱
 * @param {string} messageId
 */
export function unmountSandbox(messageId) {
  const iframe = activeSandboxes.get(messageId)
  if (iframe) {
    iframe.remove()
    activeSandboxes.delete(messageId)
  }
}

/**
 * 销毁所有沙箱
 */
export function unmountAllSandboxes() {
  activeSandboxes.forEach((iframe) => iframe.remove())
  activeSandboxes.clear()
}

/**
 * 处理来自沙箱的 postMessage 事件
 * @param {function} onInput - 当沙箱触发 input(text) 时的回调
 * @returns {function} 清理函数
 */
export function setupSandboxBridge(onInput) {
  const handler = (event) => {
    if (!event.data || typeof event.data !== 'object') return

    if (event.data.type === 'vcp-input' && typeof event.data.text === 'string') {
      onInput(event.data.text)
    }

    if (event.data.type === 'vcp-resize' && typeof event.data.height === 'number') {
      // 找到发送消息的 iframe 并调整高度
      activeSandboxes.forEach((iframe) => {
        try {
          if (iframe.contentWindow === event.source) {
            iframe.style.height = `${event.data.height + 4}px`
          }
        } catch (e) { /* cross-origin safety */ }
      })
    }
  }

  window.addEventListener('message', handler)
  return () => window.removeEventListener('message', handler)
}

/**
 * 检测消息内容是否需要沙箱渲染（含有 vcp-root + 可执行脚本/事件）
 * @param {string} content
 * @returns {boolean}
 */
export function needsSandbox(content) {
  if (!content) return false
  const hasRoot = /<div[^>]*\bid\s*=\s*["']?vcp-root["']?/i.test(content)
  if (!hasRoot) return false
  // 有 <script> 标签 或 onclick/oninput 等交互事件
  return /<script[\s>]/i.test(content) || /\bon(?:click|input|change|submit|mouse|touch|key)\w*\s*=/i.test(content)
}
