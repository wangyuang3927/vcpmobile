/**
 * 气泡截图 & 话题长图 工具
 * 
 * 使用 html2canvas 将消息气泡或整个话题渲染为高清图片。
 * - 单气泡：2x 像素比，直接下载 PNG
 * - 话题长图：分段渲染后拼接，避免内存溢出
 */

import html2canvas from 'html2canvas'
import { Capacitor, registerPlugin } from '@capacitor/core'

const SCALE = 2 // 高清 2x 像素比
const MAX_SEGMENT_HEIGHT = 4000 // 单段最大高度（px），超过则分段
const isNative = Capacitor.isNativePlatform()
const ImageSaver = isNative ? registerPlugin('ImageSaver') : null

/**
 * 将 DOM 元素截图为 canvas
 * @param {HTMLElement} element
 * @param {object} opts
 * @returns {Promise<HTMLCanvasElement>}
 */
async function captureElement(element, opts = {}) {
  return html2canvas(element, {
    scale: opts.scale || SCALE,
    useCORS: true,
    allowTaint: false,
    backgroundColor: opts.backgroundColor || '#1a1a2e',
    logging: false,
    ...opts,
  })
}

/**
 * canvas 转 Blob
 * @param {HTMLCanvasElement} canvas
 * @returns {Promise<Blob>}
 */
function canvasToBlob(canvas) {
  return new Promise((resolve) => {
    canvas.toBlob((blob) => resolve(blob), 'image/png', 1.0)
  })
}

/**
 * canvas 转 base64 data URL
 * @param {HTMLCanvasElement} canvas
 * @returns {string}
 */
function canvasToBase64(canvas) {
  return canvas.toDataURL('image/png', 1.0)
}

/**
 * 保存图片到设备
 * 原生端：使用 Capacitor Filesystem 写入 Downloads 目录
 * Web 端：降级为 <a download> 下载
 * @param {HTMLCanvasElement} canvas
 * @param {string} filename
 * @returns {Promise<void>}
 */
async function saveImage(canvas, filename) {
  let base64Data
  try {
    base64Data = canvasToBase64(canvas)
  } catch (e) {
    throw new Error('canvas 转 base64 失败: ' + (e.message || e))
  }

  if (isNative && ImageSaver) {
    try {
      await ImageSaver.saveImage({ base64: base64Data, filename })
    } catch (e) {
      throw new Error('原生保存失败: ' + (e.message || e))
    }
    return
  }

  // Web 端降级
  const a = document.createElement('a')
  a.href = base64Data
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

/**
 * 截图并下载/分享单个气泡
 * @param {HTMLElement} bubbleEl - 气泡 DOM 元素
 * @param {string} messageId - 消息 ID（用于文件名）
 * @returns {Promise<void>}
 */
export async function captureBubble(bubbleEl, messageId) {
  if (!bubbleEl) throw new Error('找不到气泡元素')

  let targetEl = bubbleEl
  let tempEl = null

  // 如果气泡内容在 sandbox iframe 中，html2canvas 无法跨 iframe 捕获
  // 需要提取 iframe 内容到主文档的临时 div 中截图
  const sandboxIframe = bubbleEl.querySelector('.vcp-rich-sandbox')
  if (sandboxIframe && sandboxIframe.contentDocument) {
    try {
      const iframeDoc = sandboxIframe.contentDocument
      const iframeBody = iframeDoc.body
      if (iframeBody) {
        tempEl = document.createElement('div')
        // 复制 iframe 中的 <style> 标签
        iframeDoc.querySelectorAll('style').forEach(s => {
          tempEl.appendChild(s.cloneNode(true))
        })
        // 复制 body 内容（排除 <script>）
        const bodyClone = iframeBody.cloneNode(true)
        bodyClone.querySelectorAll('script').forEach(s => s.remove())
        tempEl.appendChild(bodyClone)
        // 设置离屏样式，模拟 iframe 的渲染环境
        const iframeWidth = sandboxIframe.offsetWidth || bubbleEl.offsetWidth
        const bodyBg = getComputedStyle(iframeBody).backgroundColor
        tempEl.style.cssText = `
          position:fixed; left:-9999px; top:0; z-index:-1;
          width:${iframeWidth}px; padding:8px;
          background:${bodyBg && bodyBg !== 'rgba(0, 0, 0, 0)' ? bodyBg : '#1a1a2e'};
          color:#e0e0e0; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
          word-wrap:break-word; overflow-wrap:break-word;
        `
        document.body.appendChild(tempEl)
        // 等待渲染完成，然后裁剪到实际内容高度（去底部空白）
        await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))
        const tempRect = tempEl.getBoundingClientRect()
        let maxBottom = 0
        for (const child of tempEl.querySelectorAll('*')) {
          const r = child.getBoundingClientRect()
          if (r.height > 0 && r.bottom > maxBottom) maxBottom = r.bottom
        }
        if (maxBottom > tempRect.top) {
          tempEl.style.height = `${Math.ceil(maxBottom - tempRect.top) + 16}px`
          tempEl.style.overflow = 'hidden'
        }
        targetEl = tempEl
      }
    } catch (e) {
      // iframe 跨域或访问失败，降级为截图原始元素
      console.warn('[Capture] 无法提取 iframe 内容，降级截图:', e.message)
    }
  }

  try {
    const canvas = await captureElement(targetEl)
    const filename = `vcp_bubble_${messageId?.slice(-8) || Date.now()}.png`
    await saveImage(canvas, filename)
  } finally {
    if (tempEl && tempEl.parentNode) {
      document.body.removeChild(tempEl)
    }
  }
}

/**
 * 将整个话题生成长图
 * 策略：
 * - 总高度 ≤ MAX_SEGMENT_HEIGHT：直接截图
 * - 总高度 > MAX_SEGMENT_HEIGHT：分段截图后拼接到一张 canvas
 * 
 * @param {HTMLElement} chatContainer - 聊天消息列表容器
 * @param {string} topicTitle - 话题标题
 * @param {function} onProgress - 进度回调 (current, total)
 * @returns {Promise<void>}
 */
export async function captureTopicAsImage(chatContainer, topicTitle, onProgress) {
  if (!chatContainer) throw new Error('找不到聊天容器')

  // 创建一个离屏克隆容器，确保渲染不受滚动影响
  const clone = chatContainer.cloneNode(true)
  clone.style.cssText = `
    position: fixed;
    left: -9999px;
    top: 0;
    width: ${chatContainer.offsetWidth}px;
    max-height: none;
    height: auto;
    overflow: visible;
    background: #1a1a2e;
    padding: 20px 16px;
    z-index: -1;
  `

  // 添加标题头
  const header = document.createElement('div')
  header.style.cssText = 'text-align:center;padding:20px 0 16px;color:#e0e0e0;font-size:18px;font-weight:600;border-bottom:1px solid rgba(255,255,255,0.1);margin-bottom:16px;'
  header.textContent = topicTitle || '对话记录'
  clone.insertBefore(header, clone.firstChild)

  // 添加底部水印
  const footer = document.createElement('div')
  footer.style.cssText = 'text-align:center;padding:16px 0 8px;color:rgba(255,255,255,0.3);font-size:12px;margin-top:16px;border-top:1px solid rgba(255,255,255,0.1);'
  footer.textContent = `VCP Mobile · ${new Date().toLocaleDateString('zh-CN')}`
  clone.appendChild(footer)

  document.body.appendChild(clone)

  try {
    const totalHeight = clone.scrollHeight
    const width = clone.offsetWidth

    if (totalHeight <= MAX_SEGMENT_HEIGHT) {
      // 单段直接截图
      onProgress?.(1, 1)
      const canvas = await captureElement(clone)
      const filename = `vcp_topic_${Date.now()}.png`
      await saveImage(canvas, filename)
    } else {
      // 分段截图后拼接
      const segments = Math.ceil(totalHeight / MAX_SEGMENT_HEIGHT)
      const canvases = []

      for (let i = 0; i < segments; i++) {
        onProgress?.(i + 1, segments)
        const y = i * MAX_SEGMENT_HEIGHT
        const h = Math.min(MAX_SEGMENT_HEIGHT, totalHeight - y)
        const segCanvas = await captureElement(clone, {
          y,
          height: h,
          windowHeight: h,
        })
        canvases.push(segCanvas)
      }

      // 拼接所有段落到一张大 canvas
      const finalCanvas = document.createElement('canvas')
      finalCanvas.width = width * SCALE
      finalCanvas.height = totalHeight * SCALE
      const ctx = finalCanvas.getContext('2d')

      let offsetY = 0
      for (const seg of canvases) {
        ctx.drawImage(seg, 0, offsetY)
        offsetY += seg.height
      }

      const filename = `vcp_topic_${Date.now()}.png`
      await saveImage(finalCanvas, filename)
    }
  } finally {
    document.body.removeChild(clone)
  }
}
