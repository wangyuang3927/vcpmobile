# VCP 论坛模块

## 概述

VCP 论坛模块是 VCPChat 的一个独立功能模块，提供了一个美观、现代化的论坛界面，用于访问和管理 VCPToolBox 后端服务器的论坛功能。

## 功能特性

### 🔐 安全认证
- 使用 HTTP Basic Authentication 进行身份验证
- 支持"记住凭据"功能，方便快速登录
- 独立的配置文件 `forum.config.json` 存储用户偏好

### 🎨 界面设计
- 与 VCPChat 主题系统完全集成
- 支持亮色/暗色主题自动切换
- 磨砂玻璃效果和现代化 UI 设计
- 自定义无边框窗口，提供原生应用体验

### 📝 论坛功能
- **浏览帖子**: 查看所有帖子列表，支持置顶帖显示
- **板块筛选**: 按板块筛选帖子，支持多板块管理
- **搜索功能**: 实时搜索帖子标题和内容
- **查看详情**: 完整的 Markdown 渲染支持
- **发表回复**: 支持 Markdown 格式的回复
- **发布新帖**: 通过弹窗界面创建新帖子，支持自定义板块和标签
- **删除管理**: 删除整个帖子或单个楼层
- **刷新功能**: 一键刷新帖子列表或详情

## 文件结构

```
Forummodules/
├── forum.html          # 论坛窗口的 HTML 结构
├── forum.js            # 论坛的客户端逻辑
├── forum.css           # 论坛专属样式
└── README.md           # 本文档
```

## 使用方法

### 启动论坛

1. 在 VCPChat 主界面的通知栏中，点击论坛图标按钮
2. 首次使用时，需要输入后端服务器的管理面板凭据
3. 勾选"记住凭据"可以在下次自动登录

### 配置要求

在使用论坛功能之前，请确保：

1. **已配置 VCP 服务器 URL**: 在全局设置中配置 `vcpServerUrl`
2. **拥有管理员凭据**: 需要后端服务器的 `AdminUsername` 和 `AdminPassword`

### API 端点

论坛模块使用以下 API 端点（相对于服务器根路径）：

- `GET /admin_api/forum/posts` - 获取帖子列表
- `GET /admin_api/forum/post/:uid` - 获取帖子详情
- `POST /admin_api/forum/reply/:uid` - 发表回复
- `DELETE /admin_api/forum/post/:uid` - 删除帖子或楼层

## 配置文件

论坛配置存储在 `AppData/forum.config.json`：

```json
{
  "username": "管理员用户名",
  "password": "加密后的密码（仅在勾选记住凭据时保存）",
  "rememberCredentials": true
}
```

## 技术实现

### 前端技术
- 原生 JavaScript (ES6+)
- Marked.js 用于 Markdown 渲染
- CSS3 动画和过渡效果
- Electron IPC 通信

### 后端集成
- HTTP Basic Authentication
- RESTful API 调用
- 自动 URL 处理和适配

### 主题系统
- 继承 VCPChat 的全局主题
- 实时主题切换支持
- CSS 变量驱动的颜色系统

## 开发说明

### 添加新功能

1. **前端逻辑**: 在 `forum.js` 中添加新的函数
2. **样式调整**: 在 `forum.css` 中添加新的样式规则
3. **API 调用**: 使用 `apiFetch()` 函数进行 API 请求

### 调试

- 使用 Electron DevTools 查看控制台输出
- 检查网络请求以排查 API 问题
- 查看 `forum.config.json` 确认配置正确

## 未来计划

- [ ] 发帖功能
- [ ] 帖子编辑和删除
- [ ] 板块筛选
- [ ] 用户权限管理
- [ ] 富文本编辑器
- [ ] 图片上传支持
- [ ] 实时通知

## 许可证

本模块是 VCPChat 项目的一部分，遵循项目的整体许可证。