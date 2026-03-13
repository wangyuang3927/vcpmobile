// modules/renderer/imageHandler.js
import { fixEmoticonUrl } from './emoticonUrlFixer.js';
 

let imageHandlerRefs = {
    electronAPI: null,
    uiHelper: null,
    chatMessagesDiv: null,
};

export function initializeImageHandler(refs) {
    imageHandlerRefs.electronAPI = refs.electronAPI;
    imageHandlerRefs.uiHelper = refs.uiHelper;
    imageHandlerRefs.chatMessagesDiv = refs.chatMessagesDiv;
    console.log("[ImageHandler] Initialized.");
}

/**
 * 将内容设置到DOM元素，并处理其中的图片。
 * 此函数现在管理一个持久化的图片加载状态，以防止在流式渲染中重复加载和闪烁。
 * @param {HTMLElement} contentDiv - 要设置内容的DOM元素。
 * @param {string} rawHtml - 经过marked.parse()处理的原始HTML。
 * @param {string} messageId - 消息ID。
 */
export function setContentAndProcessImages(contentDiv, rawHtml, messageId) {
    // 🟢 直接设置 HTML，不做替换
    contentDiv.innerHTML = rawHtml;
    
    // 🟢 然后对所有 <img> 添加事件监听
    const images = contentDiv.querySelectorAll('img');
    images.forEach((img, index) => {
        let src = img.src;
        
        // 修复表情包 URL
        if (fixEmoticonUrl && src.includes('表情包')) {
            const fixedSrc = fixEmoticonUrl(src);
            if (fixedSrc !== src) {
                img.src = fixedSrc;
                src = fixedSrc;
            }
        }
        
        // 添加交互事件
        img.style.cursor = 'pointer';
        img.title = `点击在新窗口预览\n右键可复制图片`;
        
        img.addEventListener('click', (e) => {
            e.stopPropagation();
            const currentTheme = document.body.classList.contains('light-theme') ? 'light' : 'dark';
            imageHandlerRefs.electronAPI.openImageViewer({
                src: src,
                title: img.alt || src.split('/').pop() || 'AI 图片',
                theme: currentTheme
            });
        });

        img.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            e.stopPropagation();
            imageHandlerRefs.electronAPI.showImageContextMenu(src);
        });
    });
}