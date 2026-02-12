---
description: VCPMobile 发布推送 SOP - 每次 push 前执行此流程
---

# VCPMobile Push SOP

每次发布新版本时，按以下步骤执行：

## 1. 确定版本号

- 查看 `docs/changelogs/` 目录下已有的版本日志，确定本次版本号（语义化版本 x.y.z）
- 大功能新增 → 次版本号 +1（如 1.0.0 → 1.1.0）
- Bug 修复/小改动 → 补丁版本号 +1（如 1.0.0 → 1.0.1）

## 2. 编写更新日志

在 `docs/changelogs/` 下创建 `vX.Y.Z.md`，格式如下：

```markdown
# VCPMobile vX.Y.Z 更新日志

**发布日期**: YYYY-MM-DD

## 一、功能变更

### 新增功能
- **功能名称**: 功能描述，如何使用，具有怎样的意义

### 修复问题
- **问题描述**: 修复了什么，原因是什么

## 二、技术实现

### 架构变更
- 描述架构层面的改动

### 代码变更
- 列出修改的关键文件及改动摘要

## 三、依赖项目变更

### VCPToolBox 需要的改动
- 列出需要新增/修改的文件及原因
- 对应的修改后文件已打包在 `vcp-mobile-release/patch/vcptoolbox/` 中

### VCPChat 需要的改动
- 列出需要新增/修改的文件及原因
- 对应的修改后文件已打包在 `vcp-mobile-release/patch/vcpchat/` 中

### 无需改动
- 如果本次不需要改动 VCPToolBox/VCPChat，注明"本次无需改动"
```

## 3. 对比 VCPToolBox / VCPChat 差异

// turbo
- 确保 `/tmp/vcp-origin/VCPToolBox` 和 `/tmp/vcp-origin/VCPChat` 存在（原版仓库）
  - 不存在则执行:
    ```bash
    git clone --depth 1 https://github.com/lioensky/VCPToolBox.git /tmp/vcp-origin/VCPToolBox
    git clone --depth 1 https://github.com/lioensky/VCPChat.git /tmp/vcp-origin/VCPChat
    ```
  - 已存在则拉取最新:
    ```bash
    git -C /tmp/vcp-origin/VCPToolBox pull
    git -C /tmp/vcp-origin/VCPChat pull
    ```

- 对比用户的 VCPToolBox（`/Users/jiaozi/Documents/vcp/VCPToolBox`）与原版的差异：
  ```bash
  diff -rq /Users/jiaozi/Documents/vcp/VCPToolBox /tmp/vcp-origin/VCPToolBox --exclude='.git' --exclude='node_modules' --exclude='.DS_Store' --exclude='__pycache__'
  ```
- 对比用户的 VCPChat（`/Users/jiaozi/Documents/vcp/VCPChat`）与原版的差异：
  ```bash
  diff -rq /Users/jiaozi/Documents/vcp/VCPChat /tmp/vcp-origin/VCPChat --exclude='.git' --exclude='node_modules' --exclude='.DS_Store' --exclude='__pycache__'
  ```
- 将差异文件（用户版本的修改后文件）复制到 `vcp-mobile-release/patch/vcptoolbox/` 和 `vcp-mobile-release/patch/vcpchat/`，保持相对路径结构
- 在更新日志中记录这些差异

## 4. 构建 APK

```bash
cd /Users/jiaozi/Documents/vcp/vcp-mobile
npm run build
npx cap sync android
./android/gradlew assembleDebug -p android
```

// turbo
- 将编译好的 APK 复制到发布目录：
  ```bash
  cp android/app/build/outputs/apk/debug/app-debug.apk vcp-mobile-release/vcpmobile-vX.Y.Z.apk
  ```

## 5. 打包发布文件

// turbo
- 确认 `vcp-mobile-release/` 目录包含：
  - `docs/changelogs/vX.Y.Z.md` — 更新日志
  - `patch/vcptoolbox/` — VCPToolBox 差异文件（如有）
  - `patch/vcpchat/` — VCPChat 差异文件（如有）
  - `vcpmobile-vX.Y.Z.apk` — 最新编译的 APK
  - `VCP_MOBILE_GUIDE.md` — 用户指南（如有更新则同步）

## 6. Git 提交与推送

```bash
cd /Users/jiaozi/Documents/vcp/vcp-mobile
git add .
git commit -m "release: vX.Y.Z - 简要描述"
git tag vX.Y.Z
git push origin main --tags
```

## 7. 确认

- 确认 GitHub 上 https://github.com/wangyuang3927/vcpmobile 已更新
- 向用户汇报发布完成
