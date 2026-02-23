class DebugLogger {
  constructor() {
    this.logs = []
    this.maxLogs = 500
    this.enabled = true
  }

  log(level, category, message, data = null) {
    if (!this.enabled) return

    const entry = {
      timestamp: new Date().toISOString(),
      level,
      category,
      message,
      data: data ? JSON.stringify(data, null, 2) : null
    }

    this.logs.push(entry)

    if (this.logs.length > this.maxLogs) {
      this.logs.shift()
    }

    const prefix = `[${level.toUpperCase()}][${category}]`
    if (level === 'error') {
      console.error(prefix, message, data || '')
    } else if (level === 'warn') {
      console.warn(prefix, message, data || '')
    } else {
      console.log(prefix, message, data || '')
    }
  }

  info(category, message, data) {
    this.log('info', category, message, data)
  }

  warn(category, message, data) {
    this.log('warn', category, message, data)
  }

  error(category, message, data) {
    this.log('error', category, message, data)
  }

  getFormattedLogs() {
    return this.logs.map(entry => {
      const time = new Date(entry.timestamp).toLocaleTimeString('zh-CN', { hour12: false })
      let line = `[${time}][${entry.level.toUpperCase()}][${entry.category}] ${entry.message}`
      if (entry.data) {
        line += `\n${entry.data}`
      }
      return line
    }).join('\n\n')
  }

  getSystemInfo() {
    const userAgent = navigator.userAgent
    const androidMatch = userAgent.match(/Android\s+([\d.]+)/)
    const androidVersion = androidMatch ? androidMatch[1] : 'Unknown'
    
    return {
      vcpMobileVersion: '1.3.0',
      androidVersion,
      userAgent,
      screenSize: `${window.screen.width}x${window.screen.height}`,
      language: navigator.language,
      timestamp: new Date().toISOString()
    }
  }

  exportLogs() {
    const systemInfo = this.getSystemInfo()
    const header = `=== VCPMobile Debug Log ===
Version: ${systemInfo.vcpMobileVersion}
Android: ${systemInfo.androidVersion}
Screen: ${systemInfo.screenSize}
Language: ${systemInfo.language}
Export Time: ${new Date().toLocaleString('zh-CN')}
User Agent: ${systemInfo.userAgent}

=== Logs (${this.logs.length} entries) ===

`
    return header + this.getFormattedLogs()
  }

  clear() {
    this.logs = []
    this.info('Logger', 'Logs cleared')
  }

  setEnabled(enabled) {
    this.enabled = enabled
  }
}

export const logger = new DebugLogger()
