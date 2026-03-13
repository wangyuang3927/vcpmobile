// modules/ipc/memoHandlers.js
const { ipcMain } = require('electron');
const fs = require('fs-extra');
const path = require('path');

/**
 * Initializes memo related IPC handlers.
 * @param {object} paths - An object containing required paths.
 * @param {string} paths.USER_DATA_DIR - The path to the user data directory.
 */
function initialize(paths) {
    const { USER_DATA_DIR } = paths;
    const MEMO_CONFIG_FILE = path.join(USER_DATA_DIR, 'memo.config.json');

    /**
     * Load memo configuration
     */
    ipcMain.handle('load-memo-config', async () => {
        try {
            if (await fs.pathExists(MEMO_CONFIG_FILE)) {
                const config = await fs.readJson(MEMO_CONFIG_FILE);
                console.log('[Memo] Configuration loaded successfully');
                return config;
            } else {
                console.log('[Memo] No configuration file found, returning defaults');
                return {};
            }
        } catch (error) {
            console.error('[Memo] Error loading configuration:', error);
            return { error: error.message };
        }
    });

    /**
     * Save memo configuration
     */
    ipcMain.handle('save-memo-config', async (event, config) => {
        try {
            await fs.ensureDir(USER_DATA_DIR);
            await fs.writeJson(MEMO_CONFIG_FILE, config, { spaces: 2 });
            console.log('[Memo] Configuration saved successfully');
            return { success: true };
        } catch (error) {
            console.error('[Memo] Error saving configuration:', error);
            return { success: false, error: error.message };
        }
    });
}

module.exports = {
    initialize
};