# VCPChat 提示词模块改进说明

## 改进日期
2025-11-15

## 改进概述
本次改进主要针对模块化提示词系统的"积木块"功能，增强了用户体验和功能完整性。

---

## 主要改进功能

### 1. 双向拖拽功能 ✅

#### 从小仓拖入提示词框（原有功能保持）
- 从小仓拖拽积木块到提示词编辑区（**复制模式**）
- 小仓中的原积木块保留
- 支持精确的插入位置指示

#### 从提示词框拖入小仓（新增功能）
- 从提示词编辑区拖拽积木块到小仓（**移动模式**）
- 积木块从提示词中移除并添加到小仓
- 支持拖拽到空的小仓区域
- 视觉反馈：小仓区域显示虚线边框高亮

**实现位置：**
- [`modular-prompt-module.js:712-774`](Promptmodules/modular-prompt-module.js:712)
- [`modular-prompt-module.js:780-804`](Promptmodules/modular-prompt-module.js:780)

---

### 2. 防止重复积木块 ✅

#### 智能去重逻辑
当尝试将积木块移动到小仓时（无论通过拖拽还是右键菜单），系统会自动检测：

1. **检查内容是否重复**
   - 比较类型（text/newline）
   - 比较名称（name）
   - 比较内容条目（variants）
   - 比较单一内容（content）

2. **处理策略**
   - **如果已存在相同积木块**：直接删除当前积木块，不添加到小仓
   - **如果不存在**：正常移动到小仓

#### 去重规则
- 换行块（newline）都视为相同
- 文本块比较所有属性（名称、内容条目、单一内容）
- 确保小仓中不会出现完全相同的积木块

**实现位置：**
- [`modular-prompt-module.js:586-634`](Promptmodules/modular-prompt-module.js:586) - 右键移动
- [`modular-prompt-module.js:780-804`](Promptmodules/modular-prompt-module.js:780) - 拖拽移动
- [`modular-prompt-module.js:614-634`](Promptmodules/modular-prompt-module.js:614) - 比较函数

---

### 3. 自定义名称积木块的视觉标识 ✅

#### 格子背景图案
为使用了自定义名称的积木块添加了独特的视觉标识：

**特征：**
- 45度角的格子衫图案背景
- 半透明黑色格子（opacity: 0.05）
- 在提示词框和小仓中都显示
- 便于快速识别已命名的积木块

**CSS实现：**
```css
.prompt-block.has-custom-name,
.hidden-block.has-custom-name {
    background-image: 
        linear-gradient(45deg, rgba(0, 0, 0, 0.05) 25%, transparent 25%),
        linear-gradient(-45deg, rgba(0, 0, 0, 0.05) 25%, transparent 25%),
        linear-gradient(45deg, transparent 75%, rgba(0, 0, 0, 0.05) 75%),
        linear-gradient(-45deg, transparent 75%, rgba(0, 0, 0, 0.05) 75%);
    background-size: 8px 8px; /* 提示词框中 */
    background-size: 6px 6px; /* 小仓中，更细密 */
}
```

**实现位置：**
- [`modular-prompt-module.js:234-236`](Promptmodules/modular-prompt-module.js:234) - 提示词框
- [`modular-prompt-module.js:738-740`](Promptmodules/modular-prompt-module.js:738) - 小仓
- [`prompt-modules.css:195-202`](Promptmodules/prompt-modules.css:195) - 提示词框样式
- [`prompt-modules.css:585-593`](Promptmodules/prompt-modules.css:585) - 小仓样式

---

### 4. 积木块内容保持正确 ✅

#### 显示逻辑优化
积木块在提示词框和小仓之间移动时，确保内容正确显示：

**显示规则：**
1. **有自定义名称**：显示名称（而非内容）
2. **无自定义名称**：显示实际内容
3. **有多个内容条目（variants）**：显示当前选中的条目
4. **空积木块**：显示"[空积木块]"

**数据保持：**
- 移动时保留所有属性：`name`, `content`, `variants`, `selectedVariant`
- 拖拽复制时深拷贝variants数组
- 编辑对话框中正确显示和编辑所有内容

**实现位置：**
- [`modular-prompt-module.js:246`](Promptmodules/modular-prompt-module.js:246) - 显示逻辑
- [`modular-prompt-module.js:743`](Promptmodules/modular-prompt-module.js:743) - 小仓显示
- [`modular-prompt-module.js:1066-1071`](Promptmodules/modular-prompt-module.js:1066) - 深拷贝

---

## 用户体验改进

### 视觉反馈增强
1. **拖拽指示器**
   - 提示词框：左/右侧蓝色动画线条
   - 小仓：左/右侧蓝色动画线条
   - 小仓整体：虚线边框高亮

2. **悬停效果**
   - 积木块悬停：边框高亮 + 阴影
   - 小仓区域悬停：背景色变化

3. **拖拽状态**
   - 正在拖拽的元素：半透明（opacity: 0.5）
   - 可放置区域：明显的视觉提示

### 交互改进
1. **右键菜单更新**
   - "隐藏到小仓" → "移到小仓"（更准确的描述）
   - 自动去重，避免重复添加

2. **空状态提示**
   - 小仓为空时显示："此仓库为空\n拖拽积木块到这里"
   - 提供明确的操作指引

---

## 技术实现细节

### 拖拽系统架构

#### 拖拽状态管理
```javascript
// 拖拽状态变量
this.draggedBlock = null;        // 编辑区拖拽的积木块
this.draggedIndex = null;        // 编辑区拖拽的索引
this.draggedHiddenBlock = null;  // 小仓拖拽的积木块
```

#### 事件处理流程
1. **dragstart**: 设置拖拽数据和状态
2. **dragover**: 显示插入指示器
3. **drop**: 执行移动/复制操作
4. **dragend**: 清理状态和视觉效果

### 去重算法
```javascript
areBlocksEqual(block1, block2) {
    // 1. 类型检查
    if (block1.type !== block2.type) return false;
    if (block1.type === 'newline') return true;
    
    // 2. 名称检查
    if (block1.name !== block2.name) return false;
    
    // 3. 内容条目检查
    if (block1.variants && block2.variants) {
        // 比较每个条目
    }
    
    // 4. 单一内容检查
    return block1.content === block2.content;
}
```

---

## 兼容性说明

### 向后兼容
- 所有原有功能保持不变
- 数据结构完全兼容旧版本
- 未命名的积木块显示和行为与之前一致

### 渐进增强
- 新增的视觉标识不影响现有功能
- 拖拽功能扩展，不改变现有拖拽行为
- 去重机制静默工作，不影响正常使用

---

## 测试建议

### 功能测试
1. ✅ 从提示词框拖拽到小仓
2. ✅ 小仓内拖拽排序
3. ✅ 重复积木块的去重（拖拽和右键）
4. ✅ 自定义名称积木块的格子背景显示
5. ✅ 积木块内容在移动过程中保持正确

### 边界测试
1. ✅ 空小仓时的拖拽
2. ✅ 只有一个积木块时的拖拽
3. ✅ 多个内容条目（variants）的积木块移动
4. ✅ 有名称的积木块在小仓和提示词间移动
5. ✅ 换行块的去重处理

### 性能测试
- 大量积木块（100+）的拖拽性能
- 多个仓库间的切换速度
- 频繁拖拽操作的响应性

---

## 已知限制

1. **拖拽方向**
   - 只能在同一个小仓内排序
   - 不支持跨仓库拖拽（需要先切换仓库）

2. **视觉效果**
   - 格子背景在某些主题下可能不够明显
   - 可通过调整opacity值来优化

3. **去重策略**
   - 换行块都视为相同（这是设计决策）
   - 不考虑disabled状态的差异

---

## 未来改进方向

1. **跨仓库拖拽**
   - 支持直接拖拽积木块到其他仓库
   - 提供仓库间的快速移动

2. **批量操作**
   - 支持选中多个积木块一起移动
   - 批量删除和批量命名

3. **智能建议**
   - 根据使用频率自动推荐常用积木块
   - 智能去重提示

4. **视觉主题**
   - 支持自定义格子背景颜色
   - 支持多种视觉标识样式

---

## 文件修改清单

### JavaScript文件
- [`Promptmodules/modular-prompt-module.js`](Promptmodules/modular-prompt-module.js)
  - 新增：`moveBlockToWarehouseByDrag()` - 拖拽移动到小仓
  - 新增：`areBlocksEqual()` - 积木块比较函数
  - 新增：`handleWarehouseBlockDragOver()` - 小仓排序拖拽经过
  - 新增：`handleWarehouseBlockDrop()` - 小仓排序拖拽放置
  - 新增：`removeWarehouseDropIndicator()` - 清理小仓指示器
  - 修改：`moveBlockToWarehouse()` - 添加去重逻辑
  - 修改：`createBlockElement()` - 添加格子背景类
  - 修改：`createHiddenBlockElement()` - 添加拖拽排序和格子背景
  - 修改：`renderWarehouse()` - 添加拖拽接收功能

### CSS文件
- [`Promptmodules/prompt-modules.css`](Promptmodules/prompt-modules.css)
  - 新增：`.has-custom-name` - 格子背景样式
  - 新增：`.warehouse-drop-left/right` - 小仓排序指示器
  - 新增：`.warehouse-drag-over` - 小仓拖拽悬停效果
  - 修改：`.hidden-blocks-list` - 添加过渡效果
  - 修改：`.warehouse-empty` - 优化空状态样式

---

## 版本信息

- **版本号**: v1.3.1
- **基于版本**: v1.3.0 (全局仓库功能)
- **改进类型**: 功能增强 + 用户体验优化
- **兼容性**: 完全向后兼容

---

## 联系方式

如有问题或建议，请在VCPChat项目中提issue。