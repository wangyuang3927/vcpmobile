window.filterManager = (() => {
    // --- Private Variables ---
    let _electronAPI;
    let _uiHelper;
    let _globalSettingsRef;

    // --- Helper Functions to access refs ---
    const getGlobalSettings = () => _globalSettingsRef.get();
    const setGlobalSettings = (newSettings) => _globalSettingsRef.set(newSettings);

    /**
     * 过滤规则数据结构
     * @typedef {Object} FilterRule
     * @property {string} id - 规则唯一标识符
     * @property {string} name - 规则名称
     * @property {string} type - 规则类型：'whitelist'
     * @property {string} pattern - 匹配模式（正则表达式字符串）
     * @property {string[]} matchPositions - 匹配位置：['start', 'end', 'contain']
     * @property {number} duration - 消息停留时间（秒），0表示立即消失
     * @property {boolean} durationInfinite - 是否永久显示
     * @property {boolean} enabled - 是否启用此规则
     * @property {number} order - 规则顺序（数字越小优先级越高）
     */

    /**
     * 打开过滤规则设置模态框
     */
    function openFilterRulesModal() {
        _uiHelper.openModal('filterRulesModal');
        
        const modal = document.getElementById('filterRulesModal');
        if (!modal) {
            console.error("[FilterManager] Modal elements not found even after openModal!");
            return;
        }

        // 更新状态显示
        updateFilterStatusDisplay();

        // 渲染规则列表
        renderFilterRulesList();
    }

    /**
     * 更新过滤状态显示
     */
    function updateFilterStatusDisplay() {
        const statusElement = document.getElementById('filterStatus');
        if (!statusElement) return;

        const settings = getGlobalSettings();
        const isEnabled = settings.filterEnabled;
        const ruleCount = settings.filterRules.filter(rule => rule.enabled).length;

        if (isEnabled) {
            statusElement.textContent = `已启用 - ${ruleCount}条活跃规则`;
            statusElement.style.color = 'var(--success-color, #28a745)';
        } else {
            statusElement.textContent = '已禁用';
            statusElement.style.color = 'var(--text-secondary)';
        }
    }

    /**
     * 渲染过滤规则列表
     */
    function renderFilterRulesList() {
        const rulesList = document.getElementById('filterRulesList');
        if (!rulesList) return;
        
        rulesList.innerHTML = '';
        const settings = getGlobalSettings();

        if (settings.filterRules.length === 0) {
            rulesList.innerHTML = '<div style="text-align: center; color: var(--text-secondary); padding: 20px;">暂无过滤规则，点击上方按钮添加规则</div>';
            return;
        }

        // 按顺序排序规则
        const sortedRules = [...settings.filterRules].sort((a, b) => a.order - b.order);

        sortedRules.forEach(rule => {
            const ruleElement = createFilterRuleElement(rule);
            rulesList.appendChild(ruleElement);
        });
    }

    /**
     * 创建过滤规则元素
     * @param {FilterRule} rule
     */
    function createFilterRuleElement(rule) {
        const ruleDiv = document.createElement('div');
        ruleDiv.className = `filter-rule-item ${rule.enabled ? 'enabled' : 'disabled'}`;
        ruleDiv.dataset.ruleId = rule.id;

        const ruleHeader = document.createElement('div');
        ruleHeader.className = 'filter-rule-header';

        const ruleTitle = document.createElement('div');
        ruleTitle.className = 'filter-rule-title';
        ruleTitle.innerHTML = `
            <strong>${rule.name}</strong>
            <span class="rule-type ${rule.type}">白名单</span>
        `;

        const ruleActions = document.createElement('div');
        ruleActions.className = 'filter-rule-actions';

        const editBtn = document.createElement('button');
        editBtn.className = 'small-button';
        editBtn.textContent = '编辑';
        editBtn.onclick = () => editFilterRule(rule.id);

        const deleteBtn = document.createElement('button');
        deleteBtn.className = 'small-button danger-button';
        deleteBtn.textContent = '删除';
        deleteBtn.onclick = () => deleteFilterRule(rule.id);

        const toggleBtn = document.createElement('button');
        toggleBtn.className = `small-button ${rule.enabled ? 'success-button' : 'secondary-button'}`;
        toggleBtn.textContent = rule.enabled ? '启用' : '禁用';
        toggleBtn.onclick = () => toggleFilterRule(rule.id);

        ruleActions.appendChild(editBtn);
        ruleActions.appendChild(deleteBtn);
        ruleActions.appendChild(toggleBtn);

        ruleHeader.appendChild(ruleTitle);
        ruleHeader.appendChild(ruleActions);

        const ruleDetails = document.createElement('div');
        ruleDetails.className = 'filter-rule-details';
        ruleDetails.innerHTML = `
            <div class="rule-pattern">匹配模式: ${rule.pattern}</div>
            <div class="rule-positions">匹配位置: ${rule.matchPositions.join(', ')}</div>
            <div class="rule-duration">停留时间: ${rule.durationInfinite ? '永久' : rule.duration + '秒'}</div>
        `;

        ruleDiv.appendChild(ruleHeader);
        ruleDiv.appendChild(ruleDetails);

        return ruleDiv;
    }

    /**
     * 添加新的过滤规则
     */
    function addFilterRule() {
        openFilterRuleEditor();
    }

    /**
     * 编辑过滤规则
     * @param {string} ruleId
     */
    function editFilterRule(ruleId) {
        const rule = getGlobalSettings().filterRules.find(r => r.id === ruleId);
        if (rule) {
            openFilterRuleEditor(rule);
        }
    }

    /**
     * 删除过滤规则
     * @param {string} ruleId
     */
    async function deleteFilterRule(ruleId) {
        if (confirm('确定要删除这条过滤规则吗？')) {
            const settings = getGlobalSettings();
            settings.filterRules = settings.filterRules.filter(r => r.id !== ruleId);
            setGlobalSettings(settings);
            await saveFilterSettings();
            renderFilterRulesList();
            updateFilterStatusDisplay();
        }
    }

    /**
     * 切换过滤规则启用状态
     * @param {string} ruleId
     */
    async function toggleFilterRule(ruleId) {
        const settings = getGlobalSettings();
        const rule = settings.filterRules.find(r => r.id === ruleId);
        if (rule) {
            rule.enabled = !rule.enabled;
            setGlobalSettings(settings);
            await saveFilterSettings();
            renderFilterRulesList();
            updateFilterStatusDisplay();
        }
    }

    /**
     * 打开过滤规则编辑器
     * @param {FilterRule|null} ruleToEdit
     */
    function openFilterRuleEditor(ruleToEdit = null) {
        _uiHelper.openModal('filterRuleEditorModal');
        
        const modal = document.getElementById('filterRuleEditorModal');
        const form = document.getElementById('filterRuleEditorForm');
        const title = document.getElementById('filterRuleEditorTitle');

        if (ruleToEdit) {
            title.textContent = '编辑过滤规则';
            document.getElementById('editingFilterRuleId').value = ruleToEdit.id;
            document.getElementById('filterRuleName').value = ruleToEdit.name;
            document.querySelector(`input[name="ruleType"][value="whitelist"]`).checked = true;
            document.getElementById('filterRulePattern').value = ruleToEdit.pattern;

            document.querySelectorAll('input[name="matchPosition"]').forEach(checkbox => {
                checkbox.checked = ruleToEdit.matchPositions.includes(checkbox.value);
            });

            document.getElementById('filterRuleDuration').value = ruleToEdit.duration;
            document.getElementById('filterRuleDurationInfinite').checked = ruleToEdit.durationInfinite;
            document.getElementById('filterRuleEnabled').checked = ruleToEdit.enabled;
        } else {
            title.textContent = '添加过滤规则';
            document.getElementById('editingFilterRuleId').value = '';
            form.reset();
            document.querySelector('input[name="ruleType"][value="whitelist"]').checked = true;
            document.getElementById('filterRuleDuration').value = 7;
            document.getElementById('filterRuleDurationInfinite').checked = false;
            document.getElementById('filterRuleEnabled').checked = true;
        }

        _uiHelper.openModal('filterRuleEditorModal');
    }

    /**
     * 保存过滤规则
     */
    async function saveFilterRule() {
        const form = document.getElementById('filterRuleEditorForm');
        const ruleId = document.getElementById('editingFilterRuleId').value;
        const settings = getGlobalSettings();

        const ruleData = {
            name: document.getElementById('filterRuleName').value.trim(),
            type: 'whitelist',
            pattern: document.getElementById('filterRulePattern').value.trim(),
            matchPositions: Array.from(document.querySelectorAll('input[name="matchPosition"]:checked')).map(cb => cb.value),
            duration: parseInt(document.getElementById('filterRuleDuration').value) || 0,
            durationInfinite: document.getElementById('filterRuleDurationInfinite').checked,
            enabled: document.getElementById('filterRuleEnabled').checked,
            order: ruleId ? settings.filterRules.find(r => r.id === ruleId)?.order : Date.now()
        };

        if (!ruleData.name || !ruleData.pattern || ruleData.matchPositions.length === 0) {
            _uiHelper.showToastNotification('请填写所有必填字段', 'error');
            return;
        }
        if (ruleData.duration < 0 || ruleData.duration > 300) {
            _uiHelper.showToastNotification('停留时间必须在0到300秒之间', 'error');
            return;
        }

        if (ruleId) {
            const ruleIndex = settings.filterRules.findIndex(r => r.id === ruleId);
            if (ruleIndex !== -1) {
                settings.filterRules[ruleIndex] = { ...settings.filterRules[ruleIndex], ...ruleData };
            }
        } else {
            const newRule = {
                id: `rule_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
                ...ruleData
            };
            settings.filterRules.push(newRule);
        }
        
        setGlobalSettings(settings);
        await saveFilterSettings();
        _uiHelper.closeModal('filterRuleEditorModal');
        renderFilterRulesList();
        updateFilterStatusDisplay();
    }

    /**
     * 保存过滤设置到文件
     */
    async function saveFilterSettings() {
        const result = await _electronAPI.saveSettings({
            ...getGlobalSettings(),
            filterRules: getGlobalSettings().filterRules
        });

        if (!result.success) {
            _uiHelper.showToastNotification(`保存过滤设置失败: ${result.error}`, 'error');
        }
    }

    /**
     * 检查消息是否匹配过滤规则
     * @param {string} messageTitle - 消息标题
     * @returns {Object|null} 匹配的规则，如果过滤未启用则返回null，如果匹配白名单则返回show，否则返回hide
     */
    function checkMessageFilter(messageTitle) {
        const settings = getGlobalSettings();
        if (!settings.filterEnabled) {
            return null;
        }

        for (const rule of settings.filterRules) {
            if (!rule.enabled) continue;

            let matches = false;
            for (const position of rule.matchPositions) {
                if (position === 'contain' && messageTitle.includes(rule.pattern)) {
                    matches = true; break;
                } else if (position === 'start' && messageTitle.startsWith(rule.pattern)) {
                    matches = true; break;
                } else if (position === 'end' && messageTitle.endsWith(rule.pattern)) {
                    matches = true; break;
                }
            }

            if (matches) {
                return {
                    rule: rule,
                    action: 'show',
                    duration: rule.durationInfinite ? 0 : rule.duration
                };
            }
        }

        return {
            rule: null,
            action: 'hide',
            duration: 0
        };
    }

    function init(dependencies) {
        _electronAPI = dependencies.electronAPI;
        _uiHelper = dependencies.uiHelper;
        _globalSettingsRef = dependencies.refs.globalSettingsRef;

        const doNotDisturbBtn = document.getElementById('doNotDisturbBtn');

        if (doNotDisturbBtn) {
            // 左键点击：切换过滤总开关
            doNotDisturbBtn.addEventListener('click', async (e) => {
                e.preventDefault();
                const isActive = doNotDisturbBtn.classList.toggle('active');
                const settings = getGlobalSettings();
                settings.filterEnabled = isActive;
                setGlobalSettings(settings);

                // Also save to localStorage as backup
                localStorage.setItem('filterEnabled', isActive.toString());

                // Save the setting immediately
                const result = await _electronAPI.saveSettings({
                    ...settings, // Send all settings to avoid overwriting
                    filterEnabled: isActive
                });

                if (result.success) {
                    updateFilterStatusDisplay();
                    _uiHelper.showToastNotification(`过滤模式已${isActive ? '开启' : '关闭'}`, 'info');
                } else {
                    _uiHelper.showToastNotification(`设置过滤模式失败: ${result.error}`, 'error');
                    // Revert UI on failure
                    doNotDisturbBtn.classList.toggle('active', !isActive);
                    settings.filterEnabled = !isActive;
                    setGlobalSettings(settings);
                    localStorage.setItem('filterEnabled', (!isActive).toString());
                }
            });

            // 右键点击：打开过滤规则设置页面
            doNotDisturbBtn.addEventListener('contextmenu', (e) => {
                e.preventDefault();
                openFilterRulesModal();
            });
        }

        // 🟢 监听模态框就绪事件，动态绑定延迟加载的元素
        document.addEventListener('modal-ready', (e) => {
            const { modalId } = e.detail;
            if (modalId === 'filterRulesModal') {
                const addFilterRuleBtn = document.getElementById('addFilterRuleBtn');
                if (addFilterRuleBtn) {
                    addFilterRuleBtn.addEventListener('click', addFilterRule);
                }
                const closeFilterRulesBtn = document.getElementById('closeFilterRulesModal');
                if (closeFilterRulesBtn) {
                    closeFilterRulesBtn.addEventListener('click', () => {
                        _uiHelper.closeModal('filterRulesModal');
                    });
                }
            }
            if (modalId === 'filterRuleEditorModal') {
                const filterRuleEditorForm = document.getElementById('filterRuleEditorForm');
                if (filterRuleEditorForm) {
                    filterRuleEditorForm.addEventListener('submit', (e) => {
                        e.preventDefault();
                        saveFilterRule();
                    });
                }
                const cancelFilterRuleEditorBtn = document.getElementById('cancelFilterRuleEditor');
                if (cancelFilterRuleEditorBtn) {
                    cancelFilterRuleEditorBtn.addEventListener('click', () => {
                        _uiHelper.closeModal('filterRuleEditorModal');
                    });
                }
                const closeFilterRuleEditorBtn = document.getElementById('closeFilterRuleEditorModal');
                if (closeFilterRuleEditorBtn) {
                    closeFilterRuleEditorBtn.addEventListener('click', () => {
                        _uiHelper.closeModal('filterRuleEditorModal');
                    });
                }
            }
        });

        // 移除了 globalFilterCheckbox 的事件监听器，因为现在通过左键点击 doNotDisturbBtn 来切换总开关
    }

    // --- Public API ---
    return {
        init,
        openFilterRulesModal,
        checkMessageFilter
    };
})();