// modules/emoticonManager.js

const emoticonManager = (() => {
    let userEmoticons = [];
    let isInitialized = false;
    let emoticonPanel = null;
    let messageInput = null; // For main chat window compatibility
    let currentTargetInput = null; // The currently active textarea

    async function initialize(elements) {
        if (isInitialized) return;

        emoticonPanel = elements.emoticonPanel;
        messageInput = elements.messageInput; // Optional, for backward compatibility

        if (!emoticonPanel) {
            console.error('[EmoticonManager] Emoticon panel element not provided.');
            return;
        }

        await loadUserEmoticons();
        isInitialized = true;
        console.log('[EmoticonManager] Initialized successfully.');
    }

    async function loadUserEmoticons() {
        try {
            const settings = await window.electronAPI.loadSettings();
            const userName = settings?.userName;
            if (!userName) {
                console.warn('[EmoticonManager] User name not found in settings.');
                userEmoticons = [];
                return;
            }

            const emoticonLibrary = await window.electronAPI.getEmoticonLibrary();
            if (!emoticonLibrary || !Array.isArray(emoticonLibrary)) {
                 console.error('[EmoticonManager] Failed to load or parse emoticon_library.json');
                 userEmoticons = [];
                 return;
            }
            
            const userCategory = `${userName}表情包`;
            userEmoticons = emoticonLibrary.filter(emoticon => emoticon.category === userCategory);
            
            console.log(`[EmoticonManager] Loaded ${userEmoticons.length} emoticons for user "${userName}".`);

        } catch (error) {
            console.error('[EmoticonManager] Error loading user emoticons:', error);
            userEmoticons = [];
        }
    }

    function populateAndShowPanel(x, y) {
        if (!emoticonPanel) return;

        emoticonPanel.innerHTML = ''; // Clear previous content

        // Add title
        const title = document.createElement('div');
        title.className = 'emoticon-panel-title';
        title.textContent = '- Vchat表情包系统 -';
        emoticonPanel.appendChild(title);

        // Create grid container
        const grid = document.createElement('div');
        grid.className = 'emoticon-grid';
        emoticonPanel.appendChild(grid);

        if (userEmoticons.length === 0) {
            grid.innerHTML = '<div class="emoticon-item-placeholder">没有找到您的表情包</div>';
        } else {
            userEmoticons.forEach(emoticon => {
                const img = document.createElement('img');
                img.src = emoticon.url;
                img.title = emoticon.filename;
                img.className = 'emoticon-item';
                img.onclick = () => insertEmoticon(emoticon);
                grid.appendChild(img);
            });
        }

        // Position and show the panel
        emoticonPanel.style.left = `${x}px`;
        emoticonPanel.style.top = `${y}px`;
        emoticonPanel.style.display = 'flex';

        // Add a one-time click listener to the document to hide the panel
        setTimeout(() => { // Use timeout to prevent immediate closing
            document.addEventListener('click', hidePanelOnClickOutside, { once: true });
        }, 100);
    }

    function hidePanel() {
        if (emoticonPanel) {
            emoticonPanel.style.display = 'none';
        }
        document.removeEventListener('click', hidePanelOnClickOutside);
        currentTargetInput = null; // Clear target when panel is hidden
    }
    
    function hidePanelOnClickOutside(event) {
        if (emoticonPanel && !emoticonPanel.contains(event.target) && event.target.id !== 'attachFileBtn') {
            hidePanel();
        } else {
             // Re-add listener if click was inside, so it can be closed on next outside click
            document.addEventListener('click', hidePanelOnClickOutside, { once: true });
        }
    }

    function insertEmoticon(emoticon) {
        if (!currentTargetInput) return;
        
        const decodedUrl = decodeURIComponent(emoticon.url);
        const imgTag = `<img src="${decodedUrl}" width="80">`;
        
        const currentValue = currentTargetInput.value;
        const separator = (currentValue.length > 0 && !/\s$/.test(currentValue)) ? ' ' : '';
        currentTargetInput.value += separator + imgTag;
        
        currentTargetInput.focus();
        currentTargetInput.dispatchEvent(new Event('input', { bubbles: true }));

        hidePanel();
    }
    
    function togglePanel(attachBtn, targetInput) {
        const input = targetInput || messageInput; // Fallback to main input
        if (!emoticonPanel || !input) {
            console.error('[EmoticonManager] No target input specified or found.');
            return;
        }

        // If the panel is already open for the same target, close it.
        if (emoticonPanel.style.display === 'flex' && input === currentTargetInput) {
            hidePanel();
            return;
        }
        
        currentTargetInput = input; // Set the new target for insertion

        const rect = attachBtn.getBoundingClientRect();
        const panelWidth = 270;
        const panelHeight = 240;
        let x = rect.left - panelWidth + rect.width;
        let y = rect.top - panelHeight - 10;
        
        // Ensure the panel stays within the viewport
        if (x < 0) x = 10;
        if (y < 0) y = rect.bottom + 10;

        populateAndShowPanel(x, y);
    }

    return {
        initialize,
        togglePanel,
        reload: loadUserEmoticons
    };
})();

window.emoticonManager = emoticonManager;