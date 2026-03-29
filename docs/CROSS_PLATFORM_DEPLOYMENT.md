# VCP 三端打通跨平台部署指南

本文档说明如何在 **Mac、Windows、Mobile** 三端部署 VCP 生态，实现无缝同步。

---

## 架构概览

```
┌─────────────┐         ┌──────────────────┐         ┌─────────────┐
│  VCPChat    │◄────────┤   VCPToolBox     │────────►│  VCPMobile  │
│  (Mac/Win)  │ AppData │  (中间层服务端)   │  HTTP   │  (Android)  │
│  桌面端      │         │  + ChatSync插件  │  + WS   │  手机端      │
└─────────────┘         └──────────────────┘         └─────────────┘
      ▲                         ▲
      │                         │
      └─────── 共享数据 ─────────┘
         (Agent配置、聊天历史)
```

**核心原理**：
- VCPToolBox 作为中间层，提供 HTTP API 和 WebSocket 服务
- VCPChat 桌面端（Mac/Win）与 VCPToolBox 共享 `AppData` 目录
- VCPMobile 手机端通过网络连接 VCPToolBox
- 消息双向同步：手机 ↔ VCPToolBox ↔ 桌面端

---

## 方案一：Mac 打包 → Windows 部署（推荐）

### 在 Mac 上准备部署包

#### 1. 克隆并配置 VCPToolBox

```bash
cd /Users/jiaozi/Documents/vcp
git clone https://github.com/wangyuang3927/VCPToolBox.git VCPToolBox-for-windows
cd VCPToolBox-for-windows

# 安装依赖
npm install
pip3 install -r requirements.txt

# 复制配置模板
cp config.env.example config.env
```

#### 2. 编辑 `config.env`（关键配置）

打开 `config.env`，填写以下必要配置：

```bash
# API 密钥（必填）
OPENAI_API_KEY=your-api-key-here
OPENAI_BASE_URL=https://api.openai.com/v1

# 管理面板认证（必填，用于手机端同步）
ADMIN_USERNAME=admin
ADMIN_PASSWORD=your-secure-password

# VCPChat 数据目录（Windows 路径，稍后在 Windows 上修改）
VCPCHAT_APPDATA_PATH=C:\Users\YourUsername\AppData\Roaming\VCPChat

# 服务端口（默认 6005）
PORT=6005

# API 密钥（内网穿透场景必填）
VCP_KEY=your-vcp-key-for-mobile
```

#### 3. 创建 Windows 部署脚本

创建 `deploy-windows.bat`：

```batch
@echo off
chcp 65001 >nul
echo ========================================
echo VCPToolBox Windows 部署脚本
echo ========================================
echo.

REM 检查 Node.js
where node >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未检测到 Node.js，请先安装 Node.js 18+
    echo 下载地址: https://nodejs.org/
    pause
    exit /b 1
)

REM 检查 Python
where python >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 未检测到 Python，请先安装 Python 3.8+
    echo 下载地址: https://www.python.org/downloads/
    pause
    exit /b 1
)

echo [1/4] 安装 Node.js 依赖...
call npm install
if %errorlevel% neq 0 (
    echo [错误] npm install 失败
    pause
    exit /b 1
)

echo.
echo [2/4] 安装 Python 依赖...
pip install -r requirements.txt
if %errorlevel% neq 0 (
    echo [错误] pip install 失败
    pause
    exit /b 1
)

echo.
echo [3/4] 检查配置文件...
if not exist "config.env" (
    echo [警告] config.env 不存在，从模板复制...
    copy config.env.example config.env
    echo [重要] 请编辑 config.env 填写 API 密钥和管理员密码！
    pause
)

echo.
echo [4/4] 构建 Rust 向量引擎...
cd rust-vexus-lite
call npm run build
cd ..

echo.
echo ========================================
echo 部署完成！
echo ========================================
echo.
echo 下一步：
echo 1. 编辑 config.env 填写配置（API 密钥、管理员密码、VCPChat 路径）
echo 2. 运行 start-server.bat 启动服务
echo.
pause
```

创建 `start-server.bat`：

```batch
@echo off
chcp 65001 >nul
echo ========================================
echo 启动 VCPToolBox 服务端
echo ========================================
echo.

REM 检查配置文件
if not exist "config.env" (
    echo [错误] config.env 不存在，请先运行 deploy-windows.bat
    pause
    exit /b 1
)

echo [启动] VCPToolBox 服务端...
echo [提示] 按 Ctrl+C 停止服务
echo.

node server.js

pause
```

创建 `README-WINDOWS.md`：

```markdown
# VCPToolBox Windows 部署说明

## 前置要求

1. **Node.js 18+**：https://nodejs.org/
2. **Python 3.8+**：https://www.python.org/downloads/
3. **VCPChat 桌面端**：已安装并运行过至少一次（生成 AppData 目录）

## 部署步骤

### 1. 解压部署包

将 `VCPToolBox-windows.zip` 解压到任意目录，如：
```
C:\Users\YourName\VCPToolBox
```

### 2. 运行部署脚本

双击 `deploy-windows.bat`，等待依赖安装完成。

### 3. 配置 config.env

用文本编辑器打开 `config.env`，填写以下配置：

```bash
# API 密钥（必填）
OPENAI_API_KEY=sk-your-api-key
OPENAI_BASE_URL=https://api.openai.com/v1

# 管理面板认证（必填，手机端同步需要）
ADMIN_USERNAME=admin
ADMIN_PASSWORD=your-password-123

# VCPChat 数据目录（必填，修改为你的用户名）
VCPCHAT_APPDATA_PATH=C:\Users\YourUsername\AppData\Roaming\VCPChat

# API 密钥（内网穿透场景必填）
VCP_KEY=your-vcp-key
```

**如何找到 VCPChat 路径？**
1. 按 `Win+R`，输入 `%APPDATA%`，回车
2. 进入 `VCPChat` 文件夹
3. 复制地址栏路径，粘贴到 `VCPCHAT_APPDATA_PATH`

### 4. 启动服务

双击 `start-server.bat`，看到以下输出表示成功：

```
VCPToolBox 服务端已启动
监听端口: 6005
管理面板: http://localhost:6005/AdminPanel
```

### 5. 配置手机端

在 VCPMobile App 中：
1. 设置 → 接口地址：`http://your-ip:6005`
2. API 密钥：填写 `config.env` 中的 `VCP_KEY`
3. 启用跨设备同步 → 填写管理员用户名/密码
4. 保存

## 常见问题

### Q: 提示 "未检测到 Node.js"
A: 安装 Node.js 后需要重启命令行窗口。

### Q: 提示 "VCPCHAT_APPDATA_PATH 不存在"
A: 确保 VCPChat 已运行过至少一次，并检查路径是否正确。

### Q: 手机端连接失败
A: 
1. 检查防火墙是否允许 6005 端口
2. 确认手机和电脑在同一局域网
3. 用浏览器访问 `http://your-ip:6005/AdminPanel` 测试

### Q: Rust 向量引擎构建失败
A: Windows 需要安装 Visual Studio Build Tools。可以跳过此步骤，不影响核心功能。
```

#### 4. 打包部署文件

```bash
cd /Users/jiaozi/Documents/vcp

# 创建部署包目录
mkdir -p VCPToolBox-windows-deploy

# 复制必要文件（排除 node_modules 和 .git）
rsync -av --exclude='node_modules' \
          --exclude='.git' \
          --exclude='dailynote' \
          --exclude='image' \
          --exclude='*.log' \
          VCPToolBox-for-windows/ VCPToolBox-windows-deploy/

# 复制部署脚本
cp deploy-windows.bat VCPToolBox-windows-deploy/
cp start-server.bat VCPToolBox-windows-deploy/
cp README-WINDOWS.md VCPToolBox-windows-deploy/

# 打包为 zip
cd VCPToolBox-windows-deploy
zip -r ../VCPToolBox-windows.zip .
cd ..

echo "部署包已创建: VCPToolBox-windows.zip"
```

### 在 Windows 上部署

1. 将 `VCPToolBox-windows.zip` 传输到 Windows 电脑
2. 解压到任意目录
3. 按照 `README-WINDOWS.md` 的步骤操作

---

## 方案二：使用 AI IDE 辅助部署（推荐给 Windows 用户）

### 准备智能部署包

在 Mac 上创建 `AI_DEPLOYMENT_GUIDE.md`：

```markdown
# VCPToolBox Windows 智能部署指南

> 本文档专为 AI IDE（如 Cursor、Windsurf）设计，可直接理解并执行部署任务。

## 任务目标

在 Windows 系统上部署 VCPToolBox，实现 VCP 三端（Mac/Win/Mobile）打通。

## 部署清单

### 1. 环境检查

- [ ] Node.js 18+ 已安装
- [ ] Python 3.8+ 已安装
- [ ] VCPChat 桌面端已安装并运行过

### 2. 文件结构

```
VCPToolBox/
├── server.js                    # 主服务入口
├── Plugin.js                    # 插件管理器
├── WebSocketServer.js           # WebSocket 服务
├── config.env                   # 配置文件（需编辑）
├── routes/
│   ├── adminPanelRoutes.js      # 管理面板路由
│   └── vcpchatMobileRoutes.js   # 手机端同步路由（关键）
├── Plugin/
│   ├── ChatSync/                # 聊天同步插件（关键）
│   └── VCPChatDesktopSync/      # 桌面端同步插件（关键）
└── rust-vexus-lite/             # Rust 向量引擎（可选）
```

### 3. 配置项说明

`config.env` 必填项：

| 配置项 | 说明 | 示例 |
|--------|------|------|
| `OPENAI_API_KEY` | OpenAI API 密钥 | `sk-...` |
| `OPENAI_BASE_URL` | API 基础 URL | `https://api.openai.com/v1` |
| `ADMIN_USERNAME` | 管理员用户名 | `admin` |
| `ADMIN_PASSWORD` | 管理员密码 | `your-password` |
| `VCPCHAT_APPDATA_PATH` | VCPChat 数据目录 | `C:\Users\YourName\AppData\Roaming\VCPChat` |
| `VCP_KEY` | 手机端 API 密钥 | `your-vcp-key` |

### 4. 部署命令序列

```bash
# 1. 安装 Node.js 依赖
npm install

# 2. 安装 Python 依赖
pip install -r requirements.txt

# 3. 复制配置模板
cp config.env.example config.env

# 4. 编辑 config.env（AI IDE 可提示用户填写）

# 5. 构建 Rust 向量引擎（可选）
cd rust-vexus-lite && npm run build && cd ..

# 6. 启动服务
node server.js
```

### 5. 验证步骤

- [ ] 服务启动成功，监听 6005 端口
- [ ] 访问 `http://localhost:6005/AdminPanel` 可打开管理面板
- [ ] 访问 `http://localhost:6005/admin_api/agents/mobile-list` 返回 Agent 列表

### 6. 手机端配置

在 VCPMobile App 中：
1. 接口地址：`http://your-windows-ip:6005`
2. API 密钥：`config.env` 中的 `VCP_KEY`
3. 管理员用户名/密码：`config.env` 中的 `ADMIN_USERNAME` 和 `ADMIN_PASSWORD`

## AI IDE 执行建议

1. **自动检测环境**：检查 Node.js 和 Python 是否已安装
2. **交互式配置**：提示用户输入 API 密钥等敏感信息
3. **路径自动检测**：尝试自动找到 VCPChat AppData 路径
4. **错误诊断**：如果启动失败，检查端口占用、配置错误等
5. **防火墙提示**：提醒用户允许 6005 端口通过防火墙

## 常见错误处理

| 错误信息 | 原因 | 解决方案 |
|---------|------|---------|
| `VCPCHAT_APPDATA_PATH not found` | VCPChat 路径错误 | 检查路径是否存在，确保 VCPChat 已运行过 |
| `Port 6005 already in use` | 端口被占用 | 修改 `config.env` 中的 `PORT` |
| `ADMIN_USERNAME or ADMIN_PASSWORD not set` | 缺少管理员配置 | 在 `config.env` 中填写 |
```

### 打包智能部署包

```bash
cd /Users/jiaozi/Documents/vcp

# 创建智能部署包
mkdir -p VCPToolBox-AI-Deploy
cp -r VCPToolBox-windows-deploy/* VCPToolBox-AI-Deploy/
cp AI_DEPLOYMENT_GUIDE.md VCPToolBox-AI-Deploy/

# 打包
zip -r VCPToolBox-AI-Deploy.zip VCPToolBox-AI-Deploy/
```

---

## 方案三：Docker 部署（最简单，跨平台）

### 在任意平台部署

```bash
# 1. 克隆仓库
git clone https://github.com/wangyuang3927/VCPToolBox.git
cd VCPToolBox

# 2. 编辑 config.env

# 3. 使用 Docker Compose 启动
docker-compose up -d

# 4. 查看日志
docker-compose logs -f
```

**优点**：
- 无需安装 Node.js、Python
- 跨平台一致性
- 一键启动/停止

**缺点**：
- 需要安装 Docker
- VCPChat AppData 需要通过卷挂载

---

## 三端同步验证

### 1. 桌面端（Mac/Win）

在 VCPChat 中创建一个新话题，发送消息。

### 2. 手机端

打开 VCPMobile，刷新 Agent 列表，应该能看到桌面端的话题和消息。

### 3. 双向同步

在手机端发送消息，回到桌面端刷新，应该能看到手机端的消息。

---

## 故障排查

### 手机端无法连接

1. **检查网络**：手机和电脑是否在同一局域网？
2. **检查防火墙**：Windows 防火墙是否允许 6005 端口？
3. **检查服务**：VCPToolBox 是否正常运行？访问 `http://localhost:6005/AdminPanel`
4. **检查配置**：手机端的接口地址是否正确？

### 消息不同步

1. **检查 AppData 路径**：`config.env` 中的 `VCPCHAT_APPDATA_PATH` 是否正确？
2. **检查权限**：VCPToolBox 是否有读写 VCPChat 目录的权限？
3. **检查插件**：ChatSync 插件是否正确加载？查看日志

### 服务启动失败

1. **检查端口**：6005 端口是否被占用？
2. **检查配置**：`config.env` 是否有语法错误？
3. **检查依赖**：Node.js 和 Python 依赖是否完整安装？

---

## 进阶配置

### 内网穿透（远程访问）

使用 frp、ngrok 等工具将 VCPToolBox 暴露到公网：

```bash
# 使用 frp
frpc -c frpc.ini
```

`frpc.ini` 示例：

```ini
[common]
server_addr = your-frp-server.com
server_port = 7000

[vcptoolbox]
type = tcp
local_ip = 127.0.0.1
local_port = 6005
remote_port = 6005
```

### HTTPS 配置

使用 Nginx 反向代理 + Let's Encrypt 证书：

```nginx
server {
    listen 443 ssl;
    server_name your-domain.com;

    ssl_certificate /path/to/cert.pem;
    ssl_certificate_key /path/to/key.pem;

    location / {
        proxy_pass http://localhost:6005;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

---

## 总结

- **方案一**：适合手动部署，完全控制
- **方案二**：适合使用 AI IDE，智能辅助
- **方案三**：适合快速部署，跨平台一致

选择最适合你的方案，开始三端打通之旅！
