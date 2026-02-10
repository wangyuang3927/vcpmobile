/**
 * IndexedDB 消息缓存服务
 * 用于缓存 VCPChat 聊天记录，避免每次切换话题都重新下载
 * 
 * 存储结构：
 *   store: messages  key: {agentDirId}_{topicId}
 *   value: { messages: [...], lastModified: number, cachedAt: number }
 */

const DB_NAME = 'vcpMobileCache'
const DB_VERSION = 1
const STORE_NAME = 'messages'

let dbPromise = null

function openDB() {
  if (dbPromise) return dbPromise
  dbPromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = (e) => {
      const db = e.target.result
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME)
      }
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => {
      console.warn('[MessageCache] IndexedDB open failed:', request.error)
      dbPromise = null
      reject(request.error)
    }
  })
  return dbPromise
}

function makeKey(agentDirId, topicId) {
  return `${agentDirId}_${topicId}`
}

/**
 * 获取缓存的消息
 * @returns {Promise<{messages: Array, lastModified: number} | null>}
 */
export async function getCachedMessages(agentDirId, topicId) {
  try {
    const db = await openDB()
    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readonly')
      const store = tx.objectStore(STORE_NAME)
      const request = store.get(makeKey(agentDirId, topicId))
      request.onsuccess = () => resolve(request.result || null)
      request.onerror = () => resolve(null)
    })
  } catch {
    return null
  }
}

/**
 * 保存消息到缓存
 */
export async function setCachedMessages(agentDirId, topicId, messages, lastModified) {
  try {
    const db = await openDB()
    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      const store = tx.objectStore(STORE_NAME)
      store.put(
        { messages, lastModified, cachedAt: Date.now() },
        makeKey(agentDirId, topicId)
      )
      tx.oncomplete = () => resolve(true)
      tx.onerror = () => resolve(false)
    })
  } catch {
    return false
  }
}

/**
 * 删除指定话题的缓存
 */
export async function removeCachedMessages(agentDirId, topicId) {
  try {
    const db = await openDB()
    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      const store = tx.objectStore(STORE_NAME)
      store.delete(makeKey(agentDirId, topicId))
      tx.oncomplete = () => resolve(true)
      tx.onerror = () => resolve(false)
    })
  } catch {
    return false
  }
}

/**
 * 清除所有缓存
 */
export async function clearAllCache() {
  try {
    const db = await openDB()
    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      const store = tx.objectStore(STORE_NAME)
      store.clear()
      tx.oncomplete = () => resolve(true)
      tx.onerror = () => resolve(false)
    })
  } catch {
    return false
  }
}
