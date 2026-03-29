# 迁移到 Windows 作为 VCP 服务端

## 场景说明

**当前状态**：
- Mac 上有 VCPChat + VCPToolBox（之前作为服务端）
- Windows 上有 VCPChat + VCPToolBox
- 手机上有 VCPMobile（连接到 Mac）

**目标状态**：
- **Windows 作为服务端**（内网穿透）
- Mac 作为客户端（连接到 Windows）
- 手机作为客户端（连接到 Windows）
- 三端数据同步

---

## 第一步：在 Windows VCPToolBox 中添加手机端支持

### 1.1 需要添加的文件

从 Mac 的 VCPToolBox 复制以下文件到 Windows：

```
VCPToolBox/
├── routes/
│   └── vcpchatMobileRoutes.js          # 新增：手机端 API 路由
├── Plugin/
│   ├── ChatSync/                       # 新增：聊天同步插件
│   │   ├── ChatSync.js
│   │   └── plugin-manifest.json
│   └── VCPChatDesktopSync/             # 新增：桌面端同步插件
│       ├── VCPChatDesktopSync.js
│       └── plugin-manifest.json
```

**文件位置（Mac）**：
```
/Users/jiaozi/Documents/vcp/VCPToolBox/routes/vcpchatMobileRoutes.js
/Users/jiaozi/Documents/vcp/VCPToolBox/Plugin/ChatSync/
/Users/jiaozi/Documents/vcp/VCPToolBox/Plugin/VCPChatDesktopSync/
```

### 1.2 修改 Windows VCPToolBox 的现有文件

#### 修改 1：`routes/adminPanelRoutes.js`

在文件末尾的 `};` 之前，添加：

```javascript
    // --- VCPChat Mobile 同步路由（Agent 列表 + 聊天记录） ---
    require("./vcpchatMobileRoutes")(adminApiRouter, AGENT_MAP_FILE, parseAgentAssistantConfig);
```

**具体位置**：约第 847 行，在 `module.exports = function(app, ...` 函数的最后一个路由定义之后。

#### 修改 2：`WebSocketServer.js`

**位置 1**：约第 70 行，添加移动端路径正则：

```javascript
const desktopPathRegex = /^\/VCP_Key=(.+)$/;
const mobilePathRegex = /^\/vcp-mobile\/VCP_Key=(.+)$/;  // 新增
```

**位置 2**：约第 78 行，添加移动端匹配：

```javascript
const desktopMatch = pathname.match(desktopPathRegex);
const mobileMatch = pathname.match(mobilePathRegex);    // 新增

if (desktopMatch) {
    // 桌面端逻辑...
} else if (mobileMatch) {                                // 新增
    const vcpKey = mobileMatch[1];                       // 新增
    // 移动端逻辑（与桌面端相同）                         // 新增
}
```

**位置 3**：约第 108-110 行，添加移动端 WebSocket 处理：

```javascript
} else if (mobileMatch) {
    handleMobileClient(ws, mobileMatch[1]);
}
```

并添加 `handleMobileClient` 函数（与 `handleDesktopClient` 类似）。

#### 修改 3：`server.js`

在插件注册区域（约第 50-100 行），确保 ChatSync 插件被加载：

```javascript
// 注册 ChatSync 插件（如果存在）
const chatSyncPath = path.join(__dirname, 'Plugin', 'ChatSync', 'plugin-manifest.json');
if (fs.existsSync(chatSyncPath)) {
    console.log('[Server] ChatSync 插件已加载');
}
```

### 1.3 快速复制脚本（在 Mac 上运行）

创建 `sync-to-windows.sh`：

```bash
#!/bin/bash
# 将 VCPMobile 支持文件同步到 Windows VCPToolBox

# Windows VCPToolBox 路径（通过网络共享或 U 盘）
WIN_VCPTOOLBOX="/Volumes/SharedDrive/VCPToolBox"  # 修改为实际路径

# 检查 Windows 路径是否可访问
if [ ! -d "$WIN_VCPTOOLBOX" ]; then
    echo "错误：Windows VCPToolBox 路径不存在: $WIN_VCPTOOLBOX"
    echo "请先挂载 Windows 共享文件夹或使用 U 盘"
    exit 1
fi

echo "开始同步文件到 Windows..."

# 1. 复制 vcpchatMobileRoutes.js
echo "[1/3] 复制 vcpchatMobileRoutes.js..."
cp /Users/jiaozi/Documents/vcp/VCPToolBox/routes/vcpchatMobileRoutes.js \
   "$WIN_VCPTOOLBOX/routes/"

# 2. 复制 ChatSync 插件
echo "[2/3] 复制 ChatSync 插件..."
cp -r /Users/jiaozi/Documents/vcp/VCPToolBox/Plugin/ChatSync \
      "$WIN_VCPTOOLBOX/Plugin/"

# 3. 复制 VCPChatDesktopSync 插件
echo "[3/3] 复制 VCPChatDesktopSync 插件..."
cp -r /Users/jiaozi/Documents/vcp/VCPToolBox/Plugin/VCPChatDesktopSync \
      "$WIN_VCPTOOLBOX/Plugin/"

echo "✓ 文件同步完成！"
echo ""
echo "下一步："
echo "1. 在 Windows 上手动修改 adminPanelRoutes.js、WebSocketServer.js、server.js"
echo "2. 参考 MIGRATE_TO_WINDOWS_SERVER.md 的修改说明"
```

---

## 第二步：配置 Windows VCPToolBox

### 2.1 编辑 `config.env`

```bash
# VCPChat 数据目录（Windows 路径）
VCPCHAT_APPDATA_PATH=C:\Users\YourUsername\AppData\Roaming\VCPChat

# 管理面板认证（Mac 和手机连接需要）
ADMIN_USERNAME=admin
ADMIN_PASSWORD=your-secure-password

# API 密钥（手机端连接需要）
VCP_KEY=your-vcp-key

# 端口（默认 6005）
PORT=6005
```

### 2.2 重启 Windows VCPToolBox

```bash
# 停止现有服务
Ctrl+C

# 重新启动
node server.js
```

### 2.3 验证服务

在 Windows 浏览器访问：
- 管理面板：`http://localhost:6005/AdminPanel`
- 手机端 API：`http://localhost:6005/admin_api/agents/mobile-list`

应该能正常访问。

---

## 第三步：配置 Windows 内网穿透

### 方案 1：使用 frp

#### 在公网服务器上部署 frps

```bash
# frps.ini
[common]
bind_port = 7000
```

#### 在 Windows 上运行 frpc

下载 frp Windows 版本：https://github.com/fatedier/frp/releases

创建 `frpc.ini`：

```ini
[common]
server_addr = your-server.com
server_port = 7000

[vcptoolbox]
type = tcp
local_ip = 127.0.0.1
local_port = 6005
remote_port = 6005
```

运行：
```bash
frpc.exe -c frpc.ini
```

### 方案 2：使用 Cloudflare Tunnel（推荐）

**优点**：免费、安全、无需公网 IP

#### 安装 cloudflared

下载：https://developers.cloudflare.com/cloudflare-one/connections/connect-apps/install-and-setup/installation/

#### 登录并创建隧道

```bash
cloudflared tunnel login
cloudflared tunnel create vcptoolbox
```

#### 配置隧道

创建 `config.yml`：

```yaml
tunnel: <tunnel-id>
credentials-file: C:\Users\YourName\.cloudflared\<tunnel-id>.json

ingress:
  - hostname: vcp.yourdomain.com
    service: http://localhost:6005
  - service: http_status:404
```

#### 运行隧道

```bash
cloudflared tunnel run vcptoolbox
```

现在可以通过 `https://vcp.yourdomain.com` 访问 VCPToolBox。

---

## 第四步：配置 Mac VCPChat 连接到 Windows

### 4.1 Mac 不再运行 VCPToolBox

停止 Mac 上的 VCPToolBox 服务（如果正在运行）。

### 4.2 Mac VCPChat 配置

Mac VCPChat 本身**不需要修改**，它只读写本地 `AppData` 目录。

但如果你想让 Mac VCPChat 的数据同步到 Windows，有两种方案：

#### 方案 A：Mac VCPChat 独立运行（推荐）

- Mac VCPChat 继续使用本地数据
- 通过 VCPMobile 手机端作为桥梁同步数据
- Mac 和 Windows 的 Agent 独立管理

#### 方案 B：Mac VCPChat 数据同步到 Windows

需要在 Mac 上运行一个轻量级同步脚本，定期将 Mac VCPChat 的 `AppData` 同步到 Windows VCPToolBox。

**同步脚本示例**：

```bash
#!/bin/bash
# sync-mac-to-windows.sh

MAC_APPDATA="$HOME/Library/Application Support/VCPChat"
WIN_APPDATA="/Volumes/SharedDrive/VCPChat-AppData"  # Windows 共享文件夹

rsync -av --delete \
    "$MAC_APPDATA/" \
    "$WIN_APPDATA/"

echo "✓ Mac VCPChat 数据已同步到 Windows"
```

定时运行（每 5 分钟）：

```bash
# 添加到 crontab
*/5 * * * * /path/to/sync-mac-to-windows.sh
```

---

## 第五步：配置手机端连接到 Windows

### 5.1 修改 VCPMobile 设置

打开 VCPMobile，进入设置：

| 配置项 | 修改为 |
|--------|--------|
| **接口地址** | `https://vcp.yourdomain.com`（内网穿透后的地址）<br>或 `http://windows-local-ip:6005`（局域网） |
| **API 密钥** | Windows `config.env` 中的 `VCP_KEY` |
| **管理员用户名** | Windows `config.env` 中的 `ADMIN_USERNAME` |
| **管理员密码** | Windows `config.env` 中的 `ADMIN_PASSWORD` |

### 5.2 测试连接

在 VCPMobile 中：
1. 设置 → 连接诊断 → 测试连接
2. 应该显示 HTTP、WebSocket、同步状态均为正常

---

## 第六步：验证三端同步

### 测试 1：Windows → 手机

1. 在 Windows VCPChat 中创建新话题，发送消息
2. 在手机 VCPMobile 中刷新 Agent 列表
3. 应该能看到 Windows 的话题和消息

### 测试 2：手机 → Windows

1. 在手机 VCPMobile 中发送消息
2. 在 Windows VCPChat 中刷新话题
3. 应该能看到手机的消息

### 测试 3：Mac → Windows（如果使用方案 B）

1. 在 Mac VCPChat 中发送消息
2. 等待同步脚本运行（或手动运行）
3. 在 Windows VCPChat 中应该能看到 Mac 的消息

---

## 文件传输方式

### 方式 1：网络共享（推荐）

**在 Windows 上**：
1. 右键 `VCPToolBox` 文件夹 → 属性 → 共享
2. 设置共享权限

**在 Mac 上**：
1. Finder → 前往 → 连接服务器
2. 输入 `smb://windows-ip/VCPToolBox`
3. 挂载后路径为 `/Volumes/VCPToolBox`

### 方式 2：U 盘

1. 将 Mac 上的文件复制到 U 盘
2. U 盘插入 Windows
3. 复制到 Windows VCPToolBox 目录

### 方式 3：Git（推荐给开发者）

**在 Mac 上**：
```bash
cd /Users/jiaozi/Documents/vcp/VCPToolBox
git add routes/vcpchatMobileRoutes.js Plugin/ChatSync Plugin/VCPChatDesktopSync
git commit -m "Add VCPMobile support files"
git push
```

**在 Windows 上**：
```bash
cd C:\path\to\VCPToolBox
git pull
```

---

## 故障排查

### 手机连接不上 Windows

1. **检查内网穿透**：在手机浏览器访问 `https://vcp.yourdomain.com/AdminPanel`
2. **检查防火墙**：Windows 防火墙是否允许 6005 端口
3. **检查配置**：`config.env` 中的 `VCP_KEY`、`ADMIN_USERNAME`、`ADMIN_PASSWORD` 是否正确

### Mac 数据不同步

1. **检查同步脚本**：是否正常运行
2. **检查网络共享**：Windows 共享文件夹是否可访问
3. **检查权限**：Mac 是否有写入 Windows 共享文件夹的权限

### Windows VCPToolBox 启动失败

1. **检查文件**：`vcpchatMobileRoutes.js` 是否存在
2. **检查插件**：`Plugin/ChatSync` 和 `Plugin/VCPChatDesktopSync` 是否存在
3. **检查修改**：`adminPanelRoutes.js`、`WebSocketServer.js` 是否正确修改

---

## 总结

迁移到 Windows 作为服务端的核心步骤：

1. ✅ 在 Windows VCPToolBox 中添加 3 个文件（1 个路由 + 2 个插件）
2. ✅ 修改 Windows VCPToolBox 的 3 个现有文件
3. ✅ 配置 Windows 内网穿透（frp 或 Cloudflare Tunnel）
4. ✅ 手机端修改接口地址指向 Windows
5. ✅ Mac 端选择独立运行或同步到 Windows
6. ✅ 验证三端同步

现在你的 VCP 生态以 Windows 为中心，Mac 和手机都连接到 Windows！
