// modules/ipc/forumHandlers.js
const { ipcMain } = require('electron');
const fs = require('fs-extra');
const path = require('path');

/**
 * Initializes forum related IPC handlers.
 * @param {object} paths - An object containing required paths.
 * @param {string} paths.USER_DATA_DIR - The path to the user data directory.
 */
function initialize(paths) {
    const { USER_DATA_DIR } = paths;
    const FORUM_CONFIG_FILE = path.join(USER_DATA_DIR, 'forum.config.json');

    /**
     * Load forum configuration
     */
    ipcMain.handle('load-forum-config', async () => {
        try {
            if (await fs.pathExists(FORUM_CONFIG_FILE)) {
                const config = await fs.readJson(FORUM_CONFIG_FILE);
                console.log('[Forum] Configuration loaded successfully');
                // Ensure all fields are present
                return {
                    username: config.username || '',
                    password: config.password || '',
                    replyUsername: config.replyUsername || '',
                    rememberCredentials: config.rememberCredentials || false
                };
            } else {
                console.log('[Forum] No configuration file found, returning defaults');
                return {
                    username: '',
                    password: '',
                    replyUsername: '',
                    rememberCredentials: false
                };
            }
        } catch (error) {
            console.error('[Forum] Error loading configuration:', error);
            return {
                error: error.message,
                username: '',
                password: '',
                replyUsername: '',
                rememberCredentials: false
            };
        }
    });

    /**
     * Save forum configuration
     */
    ipcMain.handle('save-forum-config', async (event, config) => {
        try {
            // 确保目录存在
            await fs.ensureDir(USER_DATA_DIR);
            
            // 只保存需要持久化的字段
            const configToSave = {
                username: config.username || '',
                password: config.rememberCredentials ? (config.password || '') : '',
                replyUsername: config.replyUsername || '',
                rememberCredentials: config.rememberCredentials || false
            };
            
            console.log('[Forum] Attempting to save configuration to:', FORUM_CONFIG_FILE);
            await fs.writeJson(FORUM_CONFIG_FILE, configToSave, { spaces: 2 });
            console.log('[Forum] Configuration saved successfully');
            return { success: true };
        } catch (error) {
            console.error('[Forum] Error saving configuration:', error);
            return { success: false, error: error.message };
        }
    });

    /**
     * Load agents list for avatar matching
     */
    ipcMain.handle('load-agents-list', async () => {
        try {
            // Agents folder is in AppData, not AppData/UserData
            const appDataDir = path.dirname(USER_DATA_DIR);
            const agentsDir = path.join(appDataDir, 'Agents');
            
            if (!await fs.pathExists(agentsDir)) {
                return [];
            }

            const folders = await fs.readdir(agentsDir);
            const agents = [];

            for (const folder of folders) {
                const folderPath = path.join(agentsDir, folder);
                const configPath = path.join(folderPath, 'config.json');
                
                if (await fs.pathExists(configPath)) {
                    try {
                        const config = await fs.readJson(configPath);
                        if (config.name) {
                            agents.push({
                                name: config.name,
                                folder: folder
                            });
                        }
                    } catch (err) {
                        console.error(`[Forum] Error reading agent config in ${folder}:`, err);
                    }
                }
            }

            console.log(`[Forum] Loaded ${agents.length} agents for avatar matching`);
            return agents;
        } catch (error) {
            console.error('[Forum] Error loading agents list:', error);
            return [];
        }
    });

    /**
     * Load user avatar
     */
    ipcMain.handle('load-user-avatar', async () => {
        try {
            const avatarPath = path.join(USER_DATA_DIR, 'user_avatar.png');
            if (await fs.pathExists(avatarPath)) {
                return `file://${avatarPath.replace(/\\/g, '/')}`;
            }
            return null;
        } catch (error) {
            console.error('[Forum] Error loading user avatar:', error);
            return null;
        }
    });

    /**
     * Load agent avatar by folder name
     */
    ipcMain.handle('load-agent-avatar', async (event, folderName) => {
        try {
            // Agent avatars are in AppData/Agents
            const appDataDir = path.dirname(USER_DATA_DIR);
            const avatarPath = path.join(appDataDir, 'Agents', folderName, 'avatar.png');
            
            if (await fs.pathExists(avatarPath)) {
                return `file://${avatarPath.replace(/\\/g, '/')}`;
            }
            return null;
        } catch (error) {
            console.error(`[Forum] Error loading agent avatar for ${folderName}:`, error);
            return null;
        }
    });
}

module.exports = {
    initialize
};