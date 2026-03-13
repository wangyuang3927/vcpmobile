# Promptmodules - 系统提示词模块

VCPChat 的系统提示词管理模块，提供三种不同的提示词编辑模式，满足从简单到复杂的各种使用场景。

## 📋 目录

- [功能概述](#功能概述)
- [文件结构](#文件结构)
- [三种模式详解](#三种模式详解)
  - [原始富文本模式](#1-原始富文本模式)
  - [模块化积木块模式](#2-模块化积木块模式)
  - [临时与预制模式](#3-临时与预制模式)
- [数据结构](#数据结构)
- [使用方法](#使用方法)
- [API 接口](#api-接口)
- [集成说明](#集成说明)
- [样式定制](#样式定制)

---

## 功能概述

Promptmodules 将系统提示词功能扩展为三个独立的模块，每个模块都有独特的使用场景和功能特点：

| 模式 | 存储字段 | 适用场景 | 核心特性 |
|------|---------|---------|---------|
| **原始富文本** | `originalSystemPrompt` | 简单直接的提示词编辑 | 传统文本域，自动调整高度 |
| **模块化积木块** | `advancedSystemPrompt` | 复杂的、需要频繁调整组合的提示词 | 拖拽排序、多内容条目、小仓管理 |
| **临时与预制** | `presetSystemPrompt` | 使用预制模板或快速切换场景 | 预设文件夹、占位符替换 |

---

## 文件结构

```
Promptmodules/
├── prompt-manager.js              # 主管理器，负责三种模式的切换和协调
├── original-prompt-module.js      # 原始富文本模块实现
├── modular-prompt-module.js       # 模块化积木块模块实现
├── preset-prompt-module.js        # 临时与预制模块实现
├── prompt-modules.css             # 统一样式文件（支持主题自适应）
└── README.md                      # 本文档
```

---

## 三种模式详解

### 1. 原始富文本模式

**类名：** `OriginalPromptModule`  
**存储字段：** `originalSystemPrompt`

#### 特点
- 保持与原有系统完全一致的富文本编辑体验
- 简单直观的文本域输入
- 自动高度调整功能
- 向后兼容旧的 `systemPrompt` 字段

#### 使用场景
适合简单、直接的提示词编辑，无需复杂的组合和管理。

#### 主要方法
- [`render(container)`](Promptmodules/original-prompt-module.js:16) - 渲染模块 UI
- [`save()`](Promptmodules/original-prompt-module.js:49) - 保存数据
- [`getPrompt()`](Promptmodules/original-prompt-module.js:63) - 获取提示词内容

---

### 2. 模块化积木块模式

**类名：** `ModularPromptModule`  
**存储字段：** `advancedSystemPrompt`

#### 核心特性

##### 积木块类型
1. **文本积木块** ([`type: 'text'`](Promptmodules/modular-prompt-module.js:528))
   - 可编辑内容（双击进入编辑模式）
   - 支持多内容条目（variants）功能
   - 支持禁用/启用状态
   - 可隐藏到小仓库
   - 右键菜单操作

2. **换行积木块** ([`type: 'newline'`](Promptmodules/modular-prompt-module.js:208))
   - 特殊的圆形样式标记（显示 `\n`）
   - 强制换行功能
   - 不透明度 60%
   - 固定高度 20px

##### 多内容条目功能（Variants）
- 一个积木块可以包含多个可选内容（[`variants`](Promptmodules/modular-prompt-module.js:244)）
- 通过右键菜单快速切换当前显示的内容
- 积木块右上角显示圆点指示器
- 支持添加、删除、编辑多个内容条目

##### 小仓库系统
- **全局仓库 (🌐 global)**: 一个特殊的、所有Agent共享的仓库。存入此处的积木块可以在任何Agent的设置界面中看到和使用。它始终存在且位于列表最顶端，不可重命名或删除。
- **私有仓库**:
- **默认仓库 (default)**: 每个Agent私有的默认仓库，始终存在，不可重命名或删除。
- **自定义仓库**: 用户为当前Agent创建的私有仓库，可以自由重命名、删除和排序。


##### 拖拽功能
- **编辑区内部拖拽**：调整积木块顺序（[`handleDragStart`](Promptmodules/modular-prompt-module.js:899)）
- **从小仓拖入**：复制积木块到编辑区（[`draggedHiddenBlock`](Promptmodules/modular-prompt-module.js:680)）
- **拖拽指示器**：左右侧动画指示插入位置
- **仓库拖拽**：调整仓库顺序（除 default 外）

##### 编辑操作
- **双击积木块**：进入内容编辑模式
- **Shift+Enter**：积木块内换行
- **Enter**：结束编辑
- **Escape**：取消编辑

##### 右键菜单
- 切换内容条目（如果有多个）
- 启用/禁用积木块
- 编辑内容（打开编辑对话框）
- 隐藏到小仓
- 删除

##### 预览模式
- 切换 View 模式查看格式化后的最终文本
- 隐藏小仓库和编辑功能
- 以 `<pre>` 标签显示格式化结果

#### 格式化规则
[`formatBlocks()`](Promptmodules/modular-prompt-module.js:1058) 方法按以下规则格式化：
1. 跳过所有 `disabled: true` 的积木块
2. 换行块转换为 `\n`
3. 文本块使用当前选中的内容条目（[`selectedVariant`](Promptmodules/modular-prompt-module.js:1068)）
4. 按顺序拼接所有内容，不添加额外间隔

#### 数据结构
```javascript
{
  blocks: [
    {
      id: "block_1234567890_abc123",  // 唯一ID
      type: "text",                    // "text" | "newline"
      content: "主要内容",             // 主内容（向后兼容）
      name: "积木块名称",              // 可选的名称
      disabled: false,                 // 是否禁用
      variants: [                      // 多内容条目数组
        "内容选项1",
        "内容选项2",
        "内容选项3"
      ],
      selectedVariant: 0               // 当前选中的内容条目索引
    }
  ],
  hiddenBlocks: {
    "default": [],                     // 默认仓库
    "常用模板": [],                    // 自定义仓库
    "实验性内容": []
  },
  warehouseOrder: [                    // 仓库显示顺序
    "default",
    "常用模板",
    "实验性内容"
  ]
}
```

#### 主要方法
- [`render(container)`](Promptmodules/modular-prompt-module.js:74) - 渲染模块 UI
- [`addBlock(type, position)`](Promptmodules/modular-prompt-module.js:525) - 添加积木块
- [`deleteBlock(index)`](Promptmodules/modular-prompt-module.js:546) - 删除积木块
- [`hideBlock(index)`](Promptmodules/modular-prompt-module.js:564) - 隐藏积木块到小仓
- [`restoreBlock(index)`](Promptmodules/modular-prompt-module.js:879) - 从小仓恢复积木块
- [`editBlock(block, index)`](Promptmodules/modular-prompt-module.js:409) - 编辑积木块内容
- [`toggleViewMode(enabled)`](Promptmodules/modular-prompt-module.js:1027) - 切换预览模式
- [`formatBlocks()`](Promptmodules/modular-prompt-module.js:1058) - 格式化积木块为文本
- [`createWarehouse()`](Promptmodules/modular-prompt-module.js:1103) - 创建新仓库
- [`renameWarehouse(oldName)`](Promptmodules/modular-prompt-module.js:1232) - 重命名仓库
- [`deleteWarehouse(warehouseName)`](Promptmodules/modular-prompt-module.js:1321) - 删除仓库

---

### 3. 临时与预制模式

**类名：** `PresetPromptModule`  
**存储字段：** `presetSystemPrompt`, `presetPromptPath`, `selectedPreset`

#### 特点
- 从预设文件夹加载预制提示词模板
- 默认路径：`./AppData/systemPromptPresets`
- 支持 `.md` 和 `.txt` 格式文件
- 支持自定义预设路径（可浏览选择目录）
- 支持刷新预设列表
- 文本域支持占位符（如 `{{AgentName}}`）

#### 预设文件结构
预设文件应放置在指定的预设文件夹中：
```
AppData/systemPromptPresets/
├── 角色扮演模板.md
├── 代码助手.txt
├── 翻译专家.md
└── 更多预设...
```

#### 占位符功能
在预设内容中可使用占位符，系统会自动替换：
- `{{AgentName}}` - 替换为当前 Agent 名称
- 更多占位符可按需扩展

#### 主要方法
- [`render(container)`](Promptmodules/preset-prompt-module.js:49) - 渲染模块 UI
- [`loadPresets()`](Promptmodules/preset-prompt-module.js:31) - 加载预设列表
- [`loadSelectedPreset()`](Promptmodules/preset-prompt-module.js:242) - 加载选中的预设
- [`save()`](Promptmodules/preset-prompt-module.js:285) - 保存数据
- [`getPrompt()`](Promptmodules/preset-prompt-module.js:300) - 获取提示词内容

---

## 数据结构

### 数据存储模型

模块化积木块的数据现在被分为两部分存储：
1.  **Agent私有配置 (`agent.json`)**: 存储每个Agent独有的积木块布局和私有仓库内容。
2.  **全局共享配置 (`global_prompt_warehouse.json`)**: 存储所有Agent共享的全局仓库内容。

### Agent 配置新增字段

```javascript
{
  // 模式选择
  "promptMode": "original|modular|preset",  // 当前激活的模式
  
  // 原始富文本模式
  "originalSystemPrompt": "直接的文本内容...",
  
  // 模块化积木块模式
  "advancedSystemPrompt": {
    "blocks": [
      {
        "id": "block_1234567890_abc123",
        "type": "text",
        "content": "积木块内容",
        "name": "积木块名称（可选）",
        "disabled": false,
        "variants": ["选项1", "选项2", "选项3"],
        "selectedVariant": 0
      },
      {
        "id": "block_1234567891_def456",
        "type": "newline",
        "content": "",
        "disabled": false
      }
    ],
    "hiddenBlocks": {
      "default": [],           // Agent私有的默认仓库
      "自定义仓库名": []    // Agent私有的自定义仓库
    },
    "warehouseOrder": ["default", "自定义仓库名"] // Agent私有的仓库顺序（不包含global）
  },
  
  // 临时与预制模式
  "presetSystemPrompt": "预设或临时提示词内容...",
  "presetPromptPath": "./AppData/systemPromptPresets",
  "selectedPreset": "/path/to/preset.md"
}
```
### 全局仓库配置文件

文件路径: VCPChat-main/AppData/global_prompt_warehouse.json
这是一个独立的全局文件，用于存储所有共享的积木块。它是一个包含积木块对象的JSON数组。
```javascript
// global_prompt_warehouse.json
[
  {
    "id": "global_block_1668556800_abc123",
    "type": "text",
    "content": "系统信息：...",
    "name": "系统信息块",
    "disabled": false,
    "variants": ["版本1", "版本2"],
    "selectedVariant": 0
  }
]
```
---

## 使用方法

### 前端集成

#### 1. 在 HTML 中引入必要的文件

```html
<!-- 样式文件 -->
<link rel="stylesheet" href="Promptmodules/prompt-modules.css">

<!-- 脚本文件（按顺序加载） -->
<script src="Promptmodules/original-prompt-module.js"></script>
<script 
src="Promptmodules/modular-prompt-module.js"></script>
<script src="Promptmodules/preset-prompt-module.js"></script>
<script src="Promptmodules/prompt-manager.js"></script>
```

#### 2. 在 HTML 中准备容器元素

```html
<div>
    <label for="systemPromptContainer">系统提示词:</label>
    <div id="systemPromptContainer" class="system-prompt-container">
        <!-- Promptmodules 将在这里初始化 -->
    </div>
</div>
```

#### 3. 初始化 PromptManager

在 [`settingsManager.js`](modules/settingsManager.js:131) 中初始化：

```javascript
// 初始化 PromptManager
const systemPromptContainer = document.getElementById('systemPromptContainer');
if (systemPromptContainer && window.PromptManager) {
    if (promptManager) {
        // 保存当前状态
        await promptManager.saveCurrentModeData();
    }
    
    promptManager = new window.PromptManager();
    promptManager.init({
        agentId: agentId,
        config: agentConfig,
        containerElement: systemPromptContainer,
        electronAPI: window.electronAPI
    });
}
```

#### 4. 获取当前激活的系统提示词

在发送消息时调用：

```javascript
// 获取当前激活的系统提示词
const systemPrompt = await promptManager.getCurrentSystemPrompt();
```

---

## API 接口

### PromptManager（主管理器）

#### 初始化方法

**[`init(options)`](Promptmodules/prompt-manager.js:20)**

初始化提示词管理器。

**参数：**
```javascript
{
    agentId: string,              // Agent ID
    config: object,               // Agent 配置对象
    containerElement: HTMLElement, // 容器元素
    electronAPI: object           // Electron API 对象
}
```

#### 公共方法

**[`getCurrentSystemPrompt()`](Promptmodules/prompt-manager.js:222)**  
获取当前激活模式的格式化系统提示词。

```javascript
const systemPrompt = await promptManager.getCurrentSystemPrompt();
// 返回: string - 格式化后的提示词文本
```

**[`setMode(mode)`](Promptmodules/prompt-manager.js:239)**  
切换到指定模式（用于插件调用）。

```javascript
await promptManager.setMode('modular'); // 'original' | 'modular' | 'preset'
```

**[`getMode()`](Promptmodules/prompt-manager.js:249)**  
获取当前模式。

```javascript
const currentMode = promptManager.getMode();
// 返回: 'original' | 'modular' | 'preset'
```

**[`saveCurrentModeData()`](Promptmodules/prompt-manager.js:198)**  
保存当前模式的数据。

```javascript
await promptManager.saveCurrentModeData();
```

### 三个子模块的公共接口

每个子模块都实现了以下方法：

- **`render(container)`** - 渲染模块 UI
- **`save()`** - 保存数据到配置
- **`getPrompt()` / `getFormattedPrompt()`** - 获取格式化后的提示词

---

## 集成说明

### 后端 IPC 处理器

需要在 `modules/ipc/promptHandlers.js` 中实现以下处理器：

1.  **`get-global-warehouse`** - 读取并返回全局仓库的内容
2.  **`save-global-warehouse`** - 将全局仓库的内容写入文件
3. **`load-preset-prompts`** - 加载预设列表
4. **`load-preset-content`** - 加载预设内容
5. **`select-directory`** - 选择目录对话框
6. **`update-agent-config`** - 更新 Agent 配置

### 前端调用示例

```javascript
// [新增] 读写全局仓库
const globalBlocks = await electronAPI.getGlobalWarehouse();
await electronAPI.saveGlobalWarehouse(newGlobalBlocksArray);

// 加载预设列表
const presets = await electronAPI.loadPresetPrompts('./AppData/systemPromptPresets');

// 加载预设内容
const content = await electronAPI.loadPresetContent('/path/to/preset.md');

// 更新 Agent 配置
await electronAPI.updateAgentConfig(agentId, {
    promptMode: 'modular',
    advancedSystemPrompt: data
});

// 选择目录
const result = await electronAPI.selectDirectory();
if (result.success && result.path) {
    // 处理选中的路径
}
```

### 保存触发

在 [`settingsManager.js`](modules/settingsManager.js:766) 中提供了 `triggerAgentSave()` 方法，用于在模式切换或预设选择时自动触发保存：

```javascript
// 在切换模式后自动保存
if (window.settingsManager && typeof window.settingsManager.triggerAgentSave === 'function') {
    await window.settingsManager.triggerAgentSave();
}
```

### 数据兼容性

- **向后兼容**：模块会自动检测并兼容旧的 `systemPrompt` 字段
- **数据迁移**：首次使用会自动将旧数据迁移到 `originalSystemPrompt`
- **默认模式**：未设置时默认使用 `original` 模式

---

## 样式定制

### CSS 变量支持

[`prompt-modules.css`](Promptmodules/prompt-modules.css) 完全适配 VCPChat 的主题系统，使用以下 CSS 变量：

```css
--primary-text       /* 主要文字颜色 */
--secondary-text     /* 次要文字颜色 */
--border-color       /* 边框颜色 */
--input-bg           /* 输入框背景 */
--secondary-bg       /* 次要背景 */
--button-bg          /* 按钮背景 */
--button-hover-bg    /* 按钮悬停背景 */
--user-bubble-bg     /* 用户气泡背景（强调色） */
--accent-bg          /* 强调背景 */
--danger-color       /* 危险操作颜色 */
--panel-bg           /* 面板背景 */
```

### 主题自适应

- **亮色/暗色主题**：自动切换
- **响应式设计**：支持移动端适配（@media 断点：768px, 500px）
- **自定义样式**：可通过覆盖 CSS 类来定制外观

### 主要 CSS 类

#### 模式选择器
- `.prompt-mode-selector` - 模式选择器容器
- `.prompt-mode-button` - 模式按钮
- `.prompt-mode-button.active` - 激活的模式按钮

#### 模块化积木块
- `.blocks-container` - 积木块容器
- `.prompt-block` - 积木块
- `.prompt-block.text-block` - 文本积木块
- `.prompt-block.newline-block` - 换行积木块
- `.prompt-block.disabled` - 禁用的积木块
- `.block-content` - 积木块内容
- `.variant-indicator` - 多内容条目指示器
- `.warehouse-container` - 小仓容器
- `.hidden-block` - 隐藏的积木块

#### 预览模式
- `.preview-container` - 预览容器
- `.preview-text` - 预览文本

#### 预设模式
- `.preset-prompt-container` - 预设模式容器
- `.preset-path-section` - 路径设置区域
- `.preset-select` - 预设选择器

---

## 注意事项

### 使用建议

1. **模式选择**：
   - 简单场景使用原始富文本模式
   - 需要频繁调整组合时使用模块化模式
   - 有固定模板库时使用预设模式

2. **积木块命名**：
   - 为积木块添加有意义的名称，便于在小仓中识别
   - 多内容条目建议命名不同的变体版本

3. **小仓管理**：
 - **使用场景区分**:
     - **🌐 全局仓库**: 用于存放所有Agent都可能用到的通用指令，如角色扮演框架、工具列表、通用规则等。
     - **🏠 私有仓库 (default 及自定义)**: 用于存放特定Agent专用的指令，如针对某个角色的特定记忆、针对某个任务的专用提示等。
   - 合理分类创建多个**私有**仓库。
   - 定期清理不再使用的积木块。

4. **预设文件**：
   - 使用 UTF-8 编码
   - 文件名清晰表达用途
   - 可使用 Markdown 格式增强可读性

### 性能优化

- 积木块数量建议控制在 100 个以内
- 避免单个积木块内容过长（建议小于 1000 字符）
- 小仓中的积木块总数建议不超过 200 个

### 已知限制

1. 预设模式目前仅支持 `{{AgentName}}` 占位符
2. 积木块拖拽在移动端体验有限
3. 换行块在某些极端布局下可能显示异常

---

## 开发状态

- ✅ 核心功能已完成
- ✅ 后端 IPC 处理器已实现
- ✅ 前端三个模块已实现
- ✅ 样式文件已创建并适配主题
- ✅ 前端集成已完成（已集成到 settingsManager.js 和 main.html）
- ✅ 多内容条目（Variants）功能已实现
- ✅ 多仓库系统已实现
- ✅ 预览模式已实现

---

## 更新日志

### v1.3.0
- ✨ 新增全局仓库：在模块化模式中增加了“全局”仓库，实现了跨Agent共享和复用积木块的功能。
- 🔧 架构优化：重构了模块化模式的数据流，将积木块数据拆分为Agent私有配置和全局共享配置，并新增了对应的IPC处理器。

### v1.2.0（当前版本）
- ✨ 新增多内容条目（Variants）功能
- ✨ 新增多仓库系统
- ✨ 优化拖拽体验，添加动画指示器
- 🐛 修复小仓拖拽复制逻辑
- 🎨 优化 UI 样式和交互体验

### v1.1.0
- ✨ 完成三个模块的基础实现
- ✨ 实现模式切换功能
- ✨ 集成到 settingsManager
- 🎨 完善样式和主题适配

### v1.0.0
- 🎉 初始版本发布
- ✨ 实现基础架构

---

## 许可证

与 VCPChat 主项目相同

---

## 贡献指南

欢迎提交 Issue 和 Pull Request 来改进此模块！

### 开发环境

- Node.js 14+
- Electron 相关依赖
- 遵循项目代码规范

### 测试

在修改代码后，请确保：
1. 三种模式都能正常切换
2. 数据保存和加载正常
3. 主题切换后样式正常
4. 不影响其他功能模块

---

## 常见问题

**Q: “全局”仓库和“默认”仓库有什么区别？**  
A: 全局 (global) 仓库是所有Agent共享的，你在任何一个Agent里把积木块存入全局仓库，就可以在所有其他Agent里看到并使用它。默认 (default) 仓库是每个Agent私有的，你在A-Agent的默认仓库里存放的积木块，在B-Agent里是看不到的。

**Q: 如何在积木块中使用换行？**  
A: 双击进入编辑模式后，使用 Shift+Enter 可以在积木块内换行。

**Q: 小仓的积木块会被删除吗？**  
A: 不会，从小仓拖拽到编辑区是复制操作，原积木块保留在小仓中。

**Q: 如何快速创建多个相似的积木块？**  
A: 可以创建一个积木块并添加多个内容条目，或者将积木块隐藏到小仓后多次拖拽复制。

**Q: 预设文件支持哪些格式？**  
A: 目前支持 .md（Markdown）和 .txt（纯文本）格式。

**Q: 切换模式后原来的数据会丢失吗？**  
A: 不会，每种模式的数据独立保存，切换模式不影响其他模式的数据。
