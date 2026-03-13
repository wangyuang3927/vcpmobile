const path = require('path');

let browser = null;
let page = null;
let isProcessing = false; // State lock to prevent race conditions
let textCallback = null; // Store the callback function globally within the module

// --- Private Functions ---

async function initializeBrowser() {
    if (browser) return; // Already initialized

    console.log('[SpeechRecognizer] Initializing Puppeteer browser...');
    const puppeteer = require('puppeteer'); // Lazy load
    
    // Try to find system Chrome as Chromium doesn't support WebSpeech API well
    let executablePath = puppeteer.executablePath();
    const platform = process.platform;
    if (platform === 'win32') {
        const chromePaths = [
            'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
            'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
            path.join(process.env.LOCALAPPDATA, 'Google\\Chrome\\Application\\chrome.exe')
        ];
        for (const p of chromePaths) {
            if (require('fs').existsSync(p)) {
                executablePath = p;
                console.log(`[SpeechRecognizer] Using system Chrome: ${p}`);
                break;
            }
        }
    }

    browser = await puppeteer.launch({
        executablePath: executablePath,
        headless: true, // Set to false for debugging
        args: [
            '--no-sandbox',
            '--disable-setuid-sandbox',
            '--use-fake-ui-for-media-stream',
            '--disable-gpu',
        ],
    });

    page = await browser.newPage();
    
    // Grant microphone permissions
    const context = browser.defaultBrowserContext();
    try {
        // Use 'file://' as origin for local files
        await context.overridePermissions('file://', ['microphone']);
    } catch (e) {
        console.warn('[SpeechRecognizer] Failed to override permissions for file://, trying specific path', e);
        await context.overridePermissions(`file://${path.join(__dirname, '..')}`, ['microphone']);
    }

    // Expose the callback function once
    await page.exposeFunction('sendTextToElectron', (text) => {
        if (textCallback && typeof textCallback === 'function') {
            textCallback(text);
        }
    });

    await page.exposeFunction('sendErrorToElectron', (error) => {
        console.error('[SpeechRecognizer] Browser Error:', error);
    });

    console.log('[SpeechRecognizer] Functions exposed.');

    const recognizerPath = `file://${path.join(__dirname, '..', 'Voicechatmodules', 'recognizer.html')}`;
    console.log(`[SpeechRecognizer] Loading recognizer page: ${recognizerPath}`);
    await page.goto(recognizerPath);
    
    console.log('[SpeechRecognizer] Browser and page initialized.');
}


// --- Public API ---

async function start(callback) {
    if (isProcessing) {
        console.log('[SpeechRecognizer] Already processing a request.');
        return;
    }
    isProcessing = true;
    
    try {
        // Store the callback
        if (callback) {
            textCallback = callback;
        }

        // Initialize browser if it's not already running
        await initializeBrowser();

        // Start recognition on the page
        if (page) {
            await page.evaluate(() => window.startRecognition());
            console.log('[SpeechRecognizer] Recognition started on page.');
        } else {
            throw new Error("Page is not available.");
        }

    } catch (error) {
        console.error('[SpeechRecognizer] Failed to start recognition:', error);
        await shutdown(); // If start fails catastrophically, shut down everything.
    } finally {
        isProcessing = false;
    }
}

async function stop() {
    if (isProcessing || !page) {
        console.log('[SpeechRecognizer] Not running or already processing.');
        return;
    }
    isProcessing = true;

    console.log('[SpeechRecognizer] Stopping recognition on page...');
    try {
        if (page && !page.isClosed()) {
            await page.evaluate(() => window.stopRecognition());
            console.log('[SpeechRecognizer] Recognition stopped on page.');
        }
    } catch (error) {
        console.error('[SpeechRecognizer] Error stopping recognition on page:', error);
    } finally {
        isProcessing = false;
    }
}

async function shutdown() {
    console.log('[SpeechRecognizer] Shutting down Puppeteer browser...');
    if (browser) {
        try {
            await browser.close();
        } catch (error) {
            console.error('[SpeechRecognizer] Error closing browser:', error);
        }
    }
    browser = null;
    page = null;
    textCallback = null;
    isProcessing = false;
    console.log('[SpeechRecognizer] Puppeteer shut down.');
}

module.exports = {
    start,
    stop,
    shutdown // Expose the new shutdown function
};