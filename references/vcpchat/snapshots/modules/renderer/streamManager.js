// modules/renderer/streamManager.js

// --- Stream State ---
const streamingChunkQueues = new Map(); // messageId -> array of original chunk strings
const streamingTimers = new Map();      // messageId -> intervalId
const accumulatedStreamText = new Map(); // messageId -> string
let activeStreamingMessageId = null; // Track the currently active streaming message
const elementContentLengthCache = new Map(); // 跟踪每个元素的内容长度

// --- DOM Cache ---
const messageDomCache = new Map(); // messageId -> { messageItem, contentDiv }

// --- Performance Caches & Throttling ---
const scrollThrottleTimers = new Map(); // messageId -> timerId
const SCROLL_THROTTLE_MS = 100; // 100ms 节流
const viewContextCache = new Map(); // messageId -> boolean (是否为当前视图)
let currentViewSignature = null; // 当前视图的签名
let globalRenderLoopRunning = false;

// --- 新增：预缓冲系统 ---
const preBufferedChunks = new Map(); // messageId -> array of chunks waiting for initialization
const messageInitializationStatus = new Map(); // messageId -> 'pending' | 'ready' | 'finalized'

// --- 新增：消息上下文映射 ---
const messageContextMap = new Map(); // messageId -> {agentId, groupId, topicId, isGroupMessage}

// --- Local Reference Store ---
let refs = {};

// --- Pre-compiled Regular Expressions for Performance ---
const SPEAKER_TAG_REGEX = /^\[(?:(?!\]:\s).)*的发言\]:\s*/gm;
const NEWLINE_AFTER_CODE_REGEX = /^(\s*```)(?![\r\n])/gm;
const SPACE_AFTER_TILDE_REGEX = /(^|[^\w/\\=])~(?![\s~])/g;
const CODE_MARKER_INDENT_REGEX = /^(\s*)(```.*)/gm;
const IMG_CODE_SEPARATOR_REGEX = /(<img[^>]+>)\s*(```)/g;

/**
 * Initializes the Stream Manager with necessary dependencies from the main renderer.
 * @param {object} dependencies - An object containing all required functions and references.
 */
export function initStreamManager(dependencies) {
    refs = dependencies;
    // Assume morphdom is passed in dependencies, warn if not present.
    if (!refs.morphdom) {
        console.warn('[StreamManager] `morphdom` not provided. Streaming rendering will fall back to inefficient innerHTML updates.');
    }
}

function shouldEnableSmoothStreaming() {
    const globalSettings = refs.globalSettingsRef.get();
    return globalSettings.enableSmoothStreaming === true;
}

function messageIsFinalized(messageId) {
    // Don't rely on current history, check accumulated state
    const initStatus = messageInitializationStatus.get(messageId);
    return initStatus === 'finalized';
}

function isThinkingPlaceholderText(text) {
    if (typeof text !== 'string') return false;
    const normalized = text.trim();
    return normalized === '思考中...' || normalized === '思考中' || normalized === 'Thinking...' || normalized === 'thinking...';
}

/**
 * 🟢 生成当前视图的唯一签名
 */
function getCurrentViewSignature() {
    const currentSelectedItem = refs.currentSelectedItemRef.get();
    const currentTopicId = refs.currentTopicIdRef.get();
    return `${currentSelectedItem?.id || 'none'}-${currentTopicId || 'none'}`;
}

/**
 * 🟢 带缓存的视图检查
 */
function isMessageForCurrentView(context) {
    if (!context) return false;
    
    const newSignature = getCurrentViewSignature();
    
    // 如果视图切换了，清空缓存
    if (currentViewSignature !== newSignature) {
        currentViewSignature = newSignature;
        viewContextCache.clear();
    }
    
    const currentSelectedItem = refs.currentSelectedItemRef.get();
    const currentTopicId = refs.currentTopicIdRef.get();
    
    if (!currentSelectedItem || !currentTopicId) return false;
    
    const itemId = context.groupId || context.agentId;
    return itemId === currentSelectedItem.id && context.topicId === currentTopicId;
}

async function getHistoryForContext(context) {
    const { electronAPI } = refs;
    if (!context) return null;
    
    const { agentId, groupId, topicId, isGroupMessage } = context;
    const itemId = groupId || agentId;
    
    if (!itemId || !topicId) return null;
    
    try {
        const historyResult = isGroupMessage
            ? await electronAPI.getGroupChatHistory(itemId, topicId)
            : await electronAPI.getChatHistory(itemId, topicId);
        
        if (historyResult && !historyResult.error) {
            return historyResult;
        }
    } catch (e) {
        console.error(`[StreamManager] Failed to get history for context`, context, e);
    }
    
    return null;
}

// 🟢 历史保存防抖
const historySaveQueue = new Map(); // context signature -> {context, history, timerId}
const HISTORY_SAVE_DEBOUNCE = 1000; // 1秒防抖

async function debouncedSaveHistory(context, history) {
    if (!context || context.topicId === 'assistant_chat' || context.topicId?.startsWith('voicechat_')) {
        return; // 跳过临时聊天
    }
    
    const signature = `${context.groupId || context.agentId}-${context.topicId}`;
    
    // 清除之前的定时器
    const existing = historySaveQueue.get(signature);
    if (existing?.timerId) {
        clearTimeout(existing.timerId);
    }
    
    // 设置新的防抖定时器
    const timerId = setTimeout(async () => {
        const queuedData = historySaveQueue.get(signature);
        if (queuedData) {
            await saveHistoryForContext(queuedData.context, queuedData.history);
            historySaveQueue.delete(signature);
        }
    }, HISTORY_SAVE_DEBOUNCE);
    
    // 使用最新的 history 克隆以避免引用问题
    historySaveQueue.set(signature, { context, history: [...history], timerId });
}

async function saveHistoryForContext(context, history) {
    const { electronAPI } = refs;
    if (!context || context.isGroupMessage) {
        // For group messages, the main process (groupchat.js) is the single source of truth for history.
        // The renderer avoids saving to prevent race conditions and overwriting the correct history.
        return;
    }
    
    const { agentId, topicId } = context;
    
    if (!agentId || !topicId) return;
    
    const historyToSave = history.filter(msg => !msg.isThinking);
    
    try {
        await electronAPI.saveChatHistory(agentId, topicId, historyToSave);
    } catch (e) {
        console.error(`[StreamManager] Failed to save history for context`, context, e);
    }
}

/**
 * 批量应用流式渲染所需的轻量级预处理
 * 减少函数调用开销
 */
function applyStreamingPreprocessors(text) {
    if (!text) return '';
    
    // 🟢 在流式渲染前也修复一次（双重保险）
    // 因为流式输出可能绕过 preprocessFullContent
    if (refs.emoticonUrlFixer) {
        // Markdown 语法
        text = text.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, (match, alt, url) => {
            const fixedUrl = refs.emoticonUrlFixer.fixEmoticonUrl(url);
            return `![${alt}](${fixedUrl})`;
        });
        
        // HTML 标签
        text = text.replace(/<img([^>]*?)src=["']([^"']+)["']([^>]*?)>/gi, (match, before, url, after) => {
            const fixedUrl = refs.emoticonUrlFixer.fixEmoticonUrl(url);
            return `<img${before}src="${fixedUrl}"${after}>`;
        });
    }
    
    // 🟢 重置 lastIndex（全局正则）
    SPEAKER_TAG_REGEX.lastIndex = 0;
    NEWLINE_AFTER_CODE_REGEX.lastIndex = 0;
    SPACE_AFTER_TILDE_REGEX.lastIndex = 0;
    IMG_CODE_SEPARATOR_REGEX.lastIndex = 0;
    
    let processedText = text;

    // 🟢 新增：在流式处理中也修复错误的缩进代码块
    // 🟢 使用精细化的缩进处理，只处理HTML标签
    if (refs.deIndentMisinterpretedCodeBlocks) {
        processedText = refs.deIndentMisinterpretedCodeBlocks(processedText);
    }
    
    // 🔴 关键安全修复：在流式传输中也转义「始」和「末」之间的内容
    if (refs.processStartEndMarkers) {
        processedText = refs.processStartEndMarkers(processedText);
    }
    
    return processedText
        .replace(SPEAKER_TAG_REGEX, '')
        .replace(NEWLINE_AFTER_CODE_REGEX, '$1\n')
        .replace(SPACE_AFTER_TILDE_REGEX, '$1~ ')
        .replace(IMG_CODE_SEPARATOR_REGEX, '$1\n\n<!-- VCP-Renderer-Separator -->\n\n$2');
}

/**
 * 获取或缓存消息的 DOM 引用
 */
function getCachedMessageDom(messageId) {
    let cached = messageDomCache.get(messageId);
    
    if (cached) {
        // 验证缓存是否仍然有效（元素还在 DOM 中）
        if (cached.messageItem.isConnected) {
            return cached;
        }
        // 缓存失效，删除
        messageDomCache.delete(messageId);
    }
    
    // 重新查询并缓存
    const { chatMessagesDiv } = refs;
    const messageItem = chatMessagesDiv.querySelector(`.message-item[data-message-id="${messageId}"]`);
    
    if (!messageItem) return null;
    
    const contentDiv = messageItem.querySelector('.md-content');
    if (!contentDiv) return null;
    
    cached = { messageItem, contentDiv };
    messageDomCache.set(messageId, cached);
    
    return cached;
}

/**
 * Sets up onload and onerror handlers for an emoticon image to fix its URL on error
 * and prevent flickering by controlling its visibility.
 * @param {HTMLImageElement} img The image element.
 */
function setupEmoticonHandlers(img) {
    img.onload = function() {
        this.style.visibility = 'visible';
        this.onload = null;
        this.onerror = null;
    };
    
    img.onerror = function() {
        // If a fix was already attempted, make it visible (as a broken image) and stop.
        if (this.dataset.emoticonFixAttempted === 'true') {
            this.style.visibility = 'visible';
            this.onload = null;
            this.onerror = null;
            return;
        }
        this.dataset.emoticonFixAttempted = 'true';
        
        const fixedSrc = refs.emoticonUrlFixer.fixEmoticonUrl(this.src);
        if (fixedSrc !== this.src) {
            this.src = fixedSrc; // This will re-trigger either onload or onerror
        } else {
            // If the URL can't be fixed, show the broken image and clean up handlers.
            this.style.visibility = 'visible';
            this.onload = null;
            this.onerror = null;
        }
    };
}

/**
 * Renders a single frame of the streaming message using morphdom for efficient DOM updates.
 * This version performs minimal processing to keep it fast and avoid destroying JS state.
 * @param {string} messageId The ID of the message.
 */
function renderStreamFrame(messageId) {
    // 🟢 优先使用缓存
    let isForCurrentView = viewContextCache.get(messageId);
    
    // 如果没有缓存（可能是旧消息），回退到实时检查
    if (isForCurrentView === undefined) {
        const context = messageContextMap.get(messageId);
        isForCurrentView = isMessageForCurrentView(context);
        viewContextCache.set(messageId, isForCurrentView);
    }
    
    if (!isForCurrentView) return;

    // 🟢 使用缓存的 DOM 引用
    const cachedDom = getCachedMessageDom(messageId);
    if (!cachedDom) return;
    
    const { contentDiv } = cachedDom;

    const textForRendering = accumulatedStreamText.get(messageId) || "";

    // 移除思考指示器
    const streamingIndicator = contentDiv.querySelector('.streaming-indicator, .thinking-indicator');
    if (streamingIndicator) streamingIndicator.remove();

    // 🟢 使用批量处理函数
    const processedText = applyStreamingPreprocessors(textForRendering);
    const rawHtml = refs.markedInstance.parse(processedText);

    if (refs.morphdom) {
        try {
            refs.morphdom(contentDiv, `<div>${rawHtml}</div>`, {
                childrenOnly: true,
                
                onBeforeElUpdated: function(fromEl, toEl) {
                // 跳过相同节点
                if (fromEl.isEqualNode(toEl)) {
                    return false;
                }
                
                // 🟢 关键修复：保留正在进行的动画类，防止 morphdom 在下一帧将其移除
                // 因为 toEl 是从 marked 重新生成的，不包含这些动态添加的动画类
                if (fromEl.classList.contains('vcp-stream-element-fade-in')) {
                    toEl.classList.add('vcp-stream-element-fade-in');
                }
                if (fromEl.classList.contains('vcp-stream-content-pulse')) {
                    toEl.classList.add('vcp-stream-content-pulse');
                }

                // 🟢 检测块级元素的显著内容增长
                if (/^(P|DIV|UL|OL|LI|PRE|BLOCKQUOTE|H[1-6]|TABLE|TR|FIGURE)$/.test(fromEl.tagName)) {
                    const oldLength = elementContentLengthCache.get(fromEl) || fromEl.textContent.length;
                    const newLength = toEl.textContent.length;
                    const lengthDiff = newLength - oldLength;
                    
                    // 如果内容增长超过阈值（比如20个字符），触发微动画
                    if (lengthDiff > 20) {
                        // 使用脉冲动画而不是滑入动画
                        fromEl.classList.add('vcp-stream-content-pulse');
                        setTimeout(() => {
                            fromEl.classList.remove('vcp-stream-content-pulse');
                        }, 300);
                    }
                    
                    // 更新缓存
                    elementContentLengthCache.set(fromEl, newLength);
                }
                
                // 🟢 保留按钮状态
                if (fromEl.tagName === 'BUTTON' && fromEl.dataset.vcpInteractive === 'true') {
                    if (fromEl.disabled) {
                        toEl.disabled = true;
                        toEl.style.opacity = fromEl.style.opacity;
                        toEl.textContent = fromEl.textContent; // 保留"✓"标记
                    }
                }
                
                // 🟢 保留媒体播放状态
                if ((fromEl.tagName === 'VIDEO' || fromEl.tagName === 'AUDIO') && !fromEl.paused) {
                    return false; // 不更新正在播放的媒体
                }
                
                // 🟢 保留输入焦点
                if (fromEl === document.activeElement) {
                    requestAnimationFrame(() => toEl.focus());
                }
                
                // 🟢 简化图片逻辑：只保留状态，不再做 URL 对比
                if (fromEl.tagName === 'IMG') {
                    // 保留加载状态标记
                    if (fromEl.dataset.emoticonHandlerAttached) {
                        toEl.dataset.emoticonHandlerAttached = 'true';
                    }
                    if (fromEl.dataset.emoticonFixAttempted) {
                        toEl.dataset.emoticonFixAttempted = 'true';
                    }
                    
                    // 保留事件处理器
                    if (fromEl.onerror && !toEl.onerror) {
                        toEl.onerror = fromEl.onerror;
                    }
                    if (fromEl.onload && !toEl.onload) {
                        toEl.onload = fromEl.onload;
                    }
                    
                    // 保留可见性状态
                    if (fromEl.style.visibility) {
                        toEl.style.visibility = fromEl.style.visibility;
                    }
                    
                    // 🟢 如果图片已成功加载，不要更新它
                    if (fromEl.complete && fromEl.naturalWidth > 0) {
                        return false;
                    }
                }
                
                return true;
            },
            
            onBeforeNodeDiscarded: function(node) {
                // 防止删除标记为永久保留的元素
                if (node.classList?.contains('keep-alive')) {
                    return false;
                }
                return true;
            },
            
            onNodeAdded: function(node) {
                // 增强：包含更多常见的块级元素，确保列表、表格等都能触发横向渐入
                if (node.nodeType === 1 && /^(P|DIV|UL|OL|LI|PRE|BLOCKQUOTE|H[1-6]|TABLE|TR|FIGURE)$/.test(node.tagName)) {
                    // 确保新节点应用横向渐入类
                    node.classList.add('vcp-stream-element-fade-in');
                    
                    // 初始化长度缓存用于后续的脉冲检测
                    elementContentLengthCache.set(node, node.textContent.length);
                    
                    // 动画结束后清理类名，但保留一小段时间确保渲染稳定
                    setTimeout(() => {
                        if (node && node.classList) {
                            node.classList.remove('vcp-stream-element-fade-in');
                        }
                    }, 1000);
                }
                return node;
            }
        });
        } catch (error) {
            // 🟢 捕获不完整 HTML 导致的 morphdom 异常
            // 在流式输出过程中，这是预期内的行为，静默忽略即可
            // 等待下一个 chunk 到达后，内容变得完整，渲染会自动恢复正常
            console.debug('[StreamManager] morphdom skipped frame due to incomplete HTML, waiting for more chunks...');
        }
    } else {
        contentDiv.innerHTML = rawHtml;
    }

    // 🟢 新增：为表情包图片添加防闪烁和错误修复逻辑
    if (refs.emoticonUrlFixer) {
        const newImages = contentDiv.querySelectorAll('img[src*="表情包"]:not([data-emoticon-handler-attached])');
        
        newImages.forEach(img => {
            img.dataset.emoticonHandlerAttached = 'true';
            
            // Hide image initially to prevent broken icon flicker
            img.style.visibility = 'hidden';
    
            // If image is already loaded (e.g., from cache), show it immediately.
            // Otherwise, set up handlers to show it on load/error.
            if (img.complete && img.naturalWidth > 0) {
                img.style.visibility = 'visible';
            } else {
                setupEmoticonHandlers(img);
            }
        });
    }
}

/**
 * 🟢 节流版本的滚动函数
 */
function throttledScrollToBottom(messageId) {
    if (scrollThrottleTimers.has(messageId)) {
        return; // 节流期间，跳过
    }
    
    refs.uiHelper.scrollToBottom();
    
    const timerId = setTimeout(() => {
        scrollThrottleTimers.delete(messageId);
    }, SCROLL_THROTTLE_MS);
    
    scrollThrottleTimers.set(messageId, timerId);
}

function processAndRenderSmoothChunk(messageId) {
    const queue = streamingChunkQueues.get(messageId);
    if (!queue || queue.length === 0) return;

    const globalSettings = refs.globalSettingsRef.get();
    const minChunkSize = globalSettings.minChunkBufferSize !== undefined && globalSettings.minChunkBufferSize >= 1 ? globalSettings.minChunkBufferSize : 1;

    // Drain a small batch from the queue. The rendering uses the accumulated text,
    // so we don't need the return value here. This just advances the stream.
    let processedChars = 0;
    while (queue.length > 0 && processedChars < minChunkSize) {
        processedChars += queue.shift().length;
    }

    // Render the current state of the accumulated text using our lightweight method.
    renderStreamFrame(messageId);
    
    // Scroll if the message is in the current view.
    const context = messageContextMap.get(messageId);
    if (isMessageForCurrentView(context)) {
        throttledScrollToBottom(messageId);
    }
}

function renderChunkDirectlyToDOM(messageId, textToAppend) {
    // For non-smooth streaming, we just render the new frame immediately using the lightweight method.
    // The check for whether it's in the current view is handled inside renderStreamFrame.
    renderStreamFrame(messageId);
}

export async function startStreamingMessage(message, passedMessageItem = null) {
    const messageId = message.id;
    
    // 🟢 修复：如果消息已在处理中，且 isThinking 状态没变，直接返回现有状态
    const currentStatus = messageInitializationStatus.get(messageId);
    const cached = getCachedMessageDom(messageId);
    const isCurrentlyThinking = cached?.messageItem?.classList.contains('thinking');

    if ((currentStatus === 'pending' || currentStatus === 'ready') && (isCurrentlyThinking === !!message.isThinking)) {
        console.debug(`[StreamManager] Message ${messageId} already initialized (${currentStatus}) with same thinking state, skipping re-init`);
        return cached?.messageItem || null;
    }

    // Store the context for this message - ensure proper context structure
    const context = {
        agentId: message.agentId || message.context?.agentId || (message.isGroupMessage ? undefined : refs.currentSelectedItemRef.get()?.id),
        groupId: message.groupId || message.context?.groupId || (message.isGroupMessage ? refs.currentSelectedItemRef.get()?.id : undefined),
        topicId: message.topicId || message.context?.topicId || refs.currentTopicIdRef.get(),
        isGroupMessage: message.isGroupMessage || message.context?.isGroupMessage || false,
        agentName: message.name || message.context?.agentName,
        avatarUrl: message.avatarUrl || message.context?.avatarUrl,
        avatarColor: message.avatarColor || message.context?.avatarColor,
    };
    
    // Validate context
    if (!context.topicId || (!context.agentId && !context.groupId)) {
        console.error(`[StreamManager] Invalid context for message ${messageId}`, context);
        return null;
    }
    
    messageContextMap.set(messageId, context);
    
    // 🟢 关键修复：如果消息已经初始化过，不要重新设为 pending，避免阻塞后续 chunk
    if (!currentStatus || currentStatus === 'finalized') {
        messageInitializationStatus.set(messageId, 'pending');
    }
    
    activeStreamingMessageId = messageId;
    
    const { chatMessagesDiv, electronAPI, currentChatHistoryRef, uiHelper } = refs;
    const isForCurrentView = isMessageForCurrentView(context);
    // 🟢 缓存视图检查结果
    viewContextCache.set(messageId, isForCurrentView);
    
    // Get the correct history for this message's context
    let historyForThisMessage;
    // For assistant chat, always use a temporary in-memory history
    if (context.topicId === 'assistant_chat' || context.topicId?.startsWith('voicechat_')) {
        historyForThisMessage = currentChatHistoryRef.get();
    } else if (isForCurrentView) {
        // For current view, use in-memory history
        historyForThisMessage = currentChatHistoryRef.get();
    } else {
        // For background chats, load from disk
        historyForThisMessage = await getHistoryForContext(context);
        if (!historyForThisMessage) {
            console.error(`[StreamManager] Could not load history for background message ${messageId}`, context);
            messageInitializationStatus.set(messageId, 'finalized');
            return null;
        }
    }
    
    // Only manipulate DOM for current view
    let messageItem = null;
    if (isForCurrentView) {
        messageItem = passedMessageItem || chatMessagesDiv.querySelector(`.message-item[data-message-id="${message.id}"]`);
        if (!messageItem) {
            const placeholderMessage = { 
                ...message, 
                content: message.content || '思考中...', // Show thinking text initially
                isThinking: true, // Mark as thinking
                timestamp: message.timestamp || Date.now(), 
                isGroupMessage: message.isGroupMessage || false 
            };
            messageItem = refs.renderMessage(placeholderMessage, false);
            if (!messageItem) {
                console.error(`[StreamManager] Failed to render message item for ${message.id}`);
                messageInitializationStatus.set(messageId, 'finalized');
                return null;
            }
        }
        // Add streaming class and remove thinking class when we have a valid messageItem
        if (messageItem && messageItem.classList) {
            messageItem.classList.add('streaming');
            messageItem.classList.remove('thinking');
        }
    }
    
    // Initialize streaming state
    if (shouldEnableSmoothStreaming()) {
        if (!streamingChunkQueues.has(messageId)) {
            streamingChunkQueues.set(messageId, []);
        }
    }
    
    // 🟢 使用更明确的覆盖逻辑
    const existingText = accumulatedStreamText.get(messageId);
    const shouldSkipGroupThinkingSeed = context.isGroupMessage === true && message.isThinking === true;
    const newText = shouldSkipGroupThinkingSeed ? '' : (message.content || '');
    const shouldOverwrite = !existingText
        || existingText === '思考中...'
        || newText.length > existingText.length;
    
    if (shouldOverwrite) {
        accumulatedStreamText.set(messageId, newText);
    }
    
    // Prepare placeholder for history
    const placeholderForHistory = {
        ...message,
        content: shouldSkipGroupThinkingSeed ? '' : (message.content || ''),
        isThinking: false,
        timestamp: message.timestamp || Date.now(),
        isGroupMessage: context.isGroupMessage,
        name: context.agentName,
        agentId: context.agentId
    };
    
    // Update the appropriate history
    const historyIndex = historyForThisMessage.findIndex(m => m.id === message.id);
    if (historyIndex === -1) {
        historyForThisMessage.push(placeholderForHistory);
    } else {
        historyForThisMessage[historyIndex] = { ...historyForThisMessage[historyIndex], ...placeholderForHistory };
    }
    
    // Save the history
    if (isForCurrentView) {
        // Update in-memory reference for current view
        currentChatHistoryRef.set([...historyForThisMessage]);
    }
    
    // 🟢 使用防抖保存
    if (context.topicId !== 'assistant_chat' && !context.topicId.startsWith('voicechat_')) {
        debouncedSaveHistory(context, historyForThisMessage);
    }
    
    // Initialization is complete, message is ready to process chunks.
    messageInitializationStatus.set(messageId, 'ready');
    
    // Process any chunks that were pre-buffered during initialization.
    const bufferedChunks = preBufferedChunks.get(messageId);
    if (bufferedChunks && bufferedChunks.length > 0) {
        console.debug(`[StreamManager] Processing ${bufferedChunks.length} pre-buffered chunks for message ${messageId}`);
        for (const chunkData of bufferedChunks) {
            appendStreamChunk(messageId, chunkData.chunk, chunkData.context);
        }
        preBufferedChunks.delete(messageId);
    }
    
    if (isForCurrentView) {
        // 如果从思考转为非思考，立即触发一次渲染以清理占位符
        if (!message.isThinking && isCurrentlyThinking) {
            renderStreamFrame(messageId);
        }
        uiHelper.scrollToBottom();
    }
    
    return messageItem;
}

// 🟢 全局渲染循环（替代每个消息一个 interval）
let lastFrameTime = 0;
const TARGET_FPS = 30; // 流式渲染30fps足够
const FRAME_INTERVAL = 1000 / TARGET_FPS;

function startGlobalRenderLoop() {
    if (globalRenderLoopRunning) return;

    globalRenderLoopRunning = true;
    lastFrameTime = 0; // 重置时间戳

    function renderLoop(currentTime) {
        if (streamingTimers.size === 0) {
            globalRenderLoopRunning = false;
            return;
        }

        // 🟢 帧率限制
        if (!currentTime) { // Fallback for browsers that don't pass currentTime
            currentTime = performance.now();
        }
        if (!lastFrameTime) {
            lastFrameTime = currentTime;
        }
        const elapsed = currentTime - lastFrameTime;
        if (elapsed < FRAME_INTERVAL) {
            requestAnimationFrame(renderLoop);
            return;
        }

        lastFrameTime = currentTime - (elapsed % FRAME_INTERVAL); // More accurate timing

        // 处理所有活动的流式消息
        for (const [messageId, _] of streamingTimers) {
            processAndRenderSmoothChunk(messageId);

            const currentQueue = streamingChunkQueues.get(messageId);
            if ((!currentQueue || currentQueue.length === 0) && messageIsFinalized(messageId)) {
                streamingTimers.delete(messageId);

                const storedContext = messageContextMap.get(messageId);
                const isForCurrentView = viewContextCache.get(messageId) ?? isMessageForCurrentView(storedContext);

                if (isForCurrentView) {
                    const finalMessageItem = getCachedMessageDom(messageId)?.messageItem;
                    if (finalMessageItem) finalMessageItem.classList.remove('streaming');
                }

                streamingChunkQueues.delete(messageId);
            }
        }

        requestAnimationFrame(renderLoop);
    }

    requestAnimationFrame(renderLoop);
}

/**
 * 🟢 智能分块策略：按语义单位（词/短语）拆分，而非字符
 */
function intelligentChunkSplit(text) {
    const MIN_SPLIT_SIZE = 20;
    const MAX_CHUNK_SIZE = 10; // 每个语义块最大字符数

    if (text.length < MIN_SPLIT_SIZE) {
        return [text];
    }

    // 使用 matchAll 更快
    const regex = /[\u4e00-\u9fa5]+|[a-zA-Z0-9]+|[^\u4e00-\u9fa5a-zA-Z0-9\s]+|\s+/g;
    const semanticUnits = [...text.matchAll(regex)].map(m => m[0]);

    // 将语义单元合并为合理大小的chunk
    const chunks = [];
    let currentChunk = '';

    for (const unit of semanticUnits) {
        if (currentChunk.length + unit.length > MAX_CHUNK_SIZE) {
            if (currentChunk) { // Avoid pushing empty strings
                chunks.push(currentChunk);
            }
            currentChunk = unit;
        } else {
            currentChunk += unit;
        }
    }

    if (currentChunk) chunks.push(currentChunk);

    return chunks;
}

export function appendStreamChunk(messageId, chunkData, context) {
    const initStatus = messageInitializationStatus.get(messageId);
    
    if (!initStatus || initStatus === 'pending') {
        if (!preBufferedChunks.has(messageId)) {
            preBufferedChunks.set(messageId, []);
            // 只在第一次创建缓冲区时打印日志
            console.debug(`[StreamManager] Started pre-buffering for message ${messageId}`);
        }
        const buffer = preBufferedChunks.get(messageId);
        buffer.push({ chunk: chunkData, context });
        
        // 防止缓冲区无限增长 - 如果超过1000个chunks，可能有问题
        if (buffer.length > 1000) {
            console.warn(`[StreamManager] Pre-buffer overflow for ${messageId}, discarding old chunks.`);
            buffer.splice(0, buffer.length - 1000); // 只保留最新1000个
            return;
        }
        return;
    }
    
    if (initStatus === 'finalized') {
        console.warn(`[StreamManager] Received chunk for already finalized message ${messageId}. Ignoring.`);
        return;
    }
    
    // Extract text from chunk
    // 如果检测到 JSON 解析错误，直接过滤掉，不显示给用户
    if (chunkData?.error === 'json_parse_error') {
        console.warn(`[StreamManager] 过滤掉 JSON 解析错误的 chunk for messageId: ${messageId}`, chunkData.raw);
        return;
    }
    
    let textToAppend = "";
    if (chunkData?.choices?.[0]?.delta?.content) {
        textToAppend = chunkData.choices[0].delta.content;
    } else if (chunkData?.delta?.content) {
        textToAppend = chunkData.delta.content;
    } else if (typeof chunkData?.content === 'string') {
        textToAppend = chunkData.content;
    } else if (typeof chunkData === 'string') {
        textToAppend = chunkData;
    } else if (chunkData?.raw && !chunkData?.error) {
        // 只有在没有错误标记时才显示 raw 数据
        textToAppend = chunkData.raw;
    }
    
    if (!textToAppend) return;
    
    // Always maintain accumulated text
    let currentAccumulated = accumulatedStreamText.get(messageId) || "";
    currentAccumulated += textToAppend;
    accumulatedStreamText.set(messageId, currentAccumulated);
    
    // Update context if provided
    if (context) {
        const storedContext = messageContextMap.get(messageId);
        if (storedContext) {
            if (context.agentName) storedContext.agentName = context.agentName;
            if (context.agentId) storedContext.agentId = context.agentId;
            messageContextMap.set(messageId, storedContext);
        }
    }
    
    if (shouldEnableSmoothStreaming()) {
        const queue = streamingChunkQueues.get(messageId);
        if (queue) {
            // 🟢 新代码：智能分块
            const semanticChunks = intelligentChunkSplit(textToAppend);
            for (const chunk of semanticChunks) {
                queue.push(chunk);
            }
        } else {
            renderChunkDirectlyToDOM(messageId, textToAppend);
            return;
        }
        
        // 🟢 使用全局循环替代单独的定时器
        if (!streamingTimers.has(messageId)) {
            streamingTimers.set(messageId, true); // 只是标记，不存储实际的 timerId
            startGlobalRenderLoop(); // 启动或确保全局循环正在运行
        }
    } else {
        renderChunkDirectlyToDOM(messageId, textToAppend);
    }
}

export async function finalizeStreamedMessage(messageId, finishReason, context, finalPayload = null) {
    // With the global render loop, we no longer need to manually drain the queue here or clear timers.
    // The loop will continue to process chunks until the queue is empty and the message is finalized, then clean itself up.
    if (activeStreamingMessageId === messageId) {
        activeStreamingMessageId = null;
    }
    
    // 🟢 清理节流定时器
    const scrollTimer = scrollThrottleTimers.get(messageId);
    if (scrollTimer) {
        clearTimeout(scrollTimer);
        scrollThrottleTimers.delete(messageId);
    }
    
    messageInitializationStatus.set(messageId, 'finalized');
    
    // Get the stored context for this message
    const storedContext = messageContextMap.get(messageId) || context;
    if (!storedContext) {
        console.error(`[StreamManager] No context available for message ${messageId}`);
        return;
    }
    
    const { chatMessagesDiv, markedInstance, uiHelper } = refs;
    const isForCurrentView = isMessageForCurrentView(storedContext);
    
    // Get the correct history
    let historyForThisMessage;
    // For assistant chat, always use the in-memory history from the ref
    if (storedContext.topicId === 'assistant_chat' || storedContext.topicId?.startsWith('voicechat_')) {
        historyForThisMessage = refs.currentChatHistoryRef.get();
    } else {
        // For all other chats, always fetch the latest history from the source of truth
        // to avoid race conditions with the UI state (currentChatHistoryRef).
        historyForThisMessage = await getHistoryForContext(storedContext);
        if (!historyForThisMessage) {
            console.error(`[StreamManager] Could not load history for finalization`, storedContext);
            return;
        }
    }
    
    // Find and update the message
    const accumulatedText = accumulatedStreamText.get(messageId) || "";
    const payloadFullResponse = typeof finalPayload?.fullResponse === 'string' ? finalPayload.fullResponse : "";
    const payloadError = typeof finalPayload?.error === 'string' ? finalPayload.error.trim() : "";
    const streamedTextIsUsable = accumulatedText.trim() !== "" && !isThinkingPlaceholderText(accumulatedText);
    const payloadResponseIsUsable = payloadFullResponse.trim() !== "" && !isThinkingPlaceholderText(payloadFullResponse);

    let finalFullText = accumulatedText;
    if (storedContext.isGroupMessage === true && !streamedTextIsUsable) {
        if (payloadResponseIsUsable) {
            finalFullText = payloadFullResponse;
        } else if (payloadError) {
            finalFullText = `[错误] ${payloadError}`;
        } else if (isThinkingPlaceholderText(finalFullText)) {
            finalFullText = "";
        }
    }
    const messageIndex = historyForThisMessage.findIndex(msg => msg.id === messageId);
    
    if (messageIndex === -1) {
        // If it's an assistant chat and the message is not found,
        // it's likely the window was reset. Ignore gracefully.
        if (storedContext && storedContext.topicId === 'assistant_chat') {
            console.warn(`[StreamManager] Message ${messageId} not found in assistant history, likely due to reset. Ignoring.`);
            // Clean up just in case
            streamingChunkQueues.delete(messageId);
            accumulatedStreamText.delete(messageId);
            return;
        }
        console.error(`[StreamManager] Message ${messageId} not found in history`, storedContext);
        return;
    }
    
    const message = historyForThisMessage[messageIndex];
    message.content = finalFullText;
    message.finishReason = finishReason;
    message.isThinking = false;
    if (message.isGroupMessage && storedContext) {
        message.name = storedContext.agentName || message.name;
        message.agentId = storedContext.agentId || message.agentId;
    }
    
    // Update UI if it's the current view
    if (isForCurrentView) {
        refs.currentChatHistoryRef.set([...historyForThisMessage]);
        
        const messageItem = chatMessagesDiv.querySelector(`.message-item[data-message-id="${messageId}"]`);
        if (messageItem) {
            messageItem.classList.remove('streaming', 'thinking');
            
            const contentDiv = messageItem.querySelector('.md-content');
            if (contentDiv) {
                const globalSettings = refs.globalSettingsRef.get();
                // Use the more thorough preprocessFullContent for the final render
                const processedFinalText = refs.preprocessFullContent(finalFullText, globalSettings);
                const rawHtml = markedInstance.parse(processedFinalText);
                
                // Perform the final, high-quality render using the original global refresh method.
                // This ensures images, KaTeX, code highlighting, etc., are all processed correctly.
                refs.setContentAndProcessImages(contentDiv, rawHtml, messageId);
                
                // Step 1: Run synchronous processors (KaTeX, hljs, etc.)
                refs.processRenderedContent(contentDiv);

                // Step 2: Defer TreeWalker-based highlighters to ensure DOM is stable
                setTimeout(() => {
                    if (contentDiv && contentDiv.isConnected) {
                        refs.runTextHighlights(contentDiv);
                    }
                }, 0);

                // Step 3: Process animations, scripts, and 3D scenes
                if (refs.processAnimationsInContent) {
                    refs.processAnimationsInContent(contentDiv);
                }
            }
            
            const nameTimeBlock = messageItem.querySelector('.name-time-block');
            if (nameTimeBlock && !nameTimeBlock.querySelector('.message-timestamp')) {
                const timestampDiv = document.createElement('div');
                timestampDiv.classList.add('message-timestamp');
                timestampDiv.textContent = new Date(message.timestamp || Date.now()).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
                nameTimeBlock.appendChild(timestampDiv);
            }
            
            messageItem.addEventListener('contextmenu', (e) => {
                e.preventDefault();
                refs.showContextMenu(e, messageItem, message);
            });
            
            uiHelper.scrollToBottom();
        }
    }
    
    // 🟢 使用防抖保存
    if (storedContext.topicId !== 'assistant_chat') {
        debouncedSaveHistory(storedContext, historyForThisMessage);
    }
    
    // Cleanup
    streamingChunkQueues.delete(messageId);
    accumulatedStreamText.delete(messageId);
    
    // Delayed cleanup
    setTimeout(() => {
        messageDomCache.delete(messageId);
        messageInitializationStatus.delete(messageId);
        preBufferedChunks.delete(messageId);
        messageContextMap.delete(messageId);
        viewContextCache.delete(messageId);
    }, 5000);
}

// Expose to global scope for classic scripts
window.streamManager = {
    initStreamManager,
    startStreamingMessage,
    appendStreamChunk,
    finalizeStreamedMessage,
    getActiveStreamingMessageId: () => activeStreamingMessageId,
    isMessageInitialized: (messageId) => {
        // Check if message is being tracked by streamManager
        return messageInitializationStatus.has(messageId);
    }
};
