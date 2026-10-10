import {
  app,
  BrowserWindow,
  globalShortcut,
  ipcMain,
  Tray,
  Menu,
  dialog,
  shell,
  clipboard,
  powerMonitor,
  nativeImage
} from 'electron';
import path from 'path';
import fs from 'fs';
import { Engine, EngineStatus } from '../engine/Engine.js';
import { SettingsStore, AppSettings } from './SettingsStore.js';
import { CodeEditorService } from './CodeEditor.js';
import { ModelFiles } from '../worker/ModelFiles.js';
import { SearchHit, FileKind } from '../shared/types.js';

let engine: Engine;
let settings: AppSettings;
let tray: Tray | null = null;

let searchPanelWindow: BrowserWindow | null = null;
let searchWindow: BrowserWindow | null = null;
let settingsWindow: BrowserWindow | null = null;
let onboardingWindow: BrowserWindow | null = null;

const isDev = process.env.NODE_ENV === 'development' || !app.isPackaged;

// Ensure single instance
const gotTheLock = app.requestSingleInstanceLock();
if (!gotTheLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    toggleSearchPanel();
  });
}

function getPreloadPath(): string {
  return path.join(__dirname, 'preload.js');
}

function getRendererUrl(route = ''): string {
  if (isDev && process.env.VITE_DEV_SERVER_URL) {
    return `${process.env.VITE_DEV_SERVER_URL}#${route}`;
  }
  const htmlPath = path.join(__dirname, '../renderer/index.html');
  return `file://${htmlPath}#${route}`;
}

function createSearchPanel(): BrowserWindow {
  const win = new BrowserWindow({
    width: 780,
    height: 520,
    frame: false,
    transparent: true,
    resizable: false,
    show: false,
    skipTaskbar: true,
    alwaysOnTop: true,
    webPreferences: {
      preload: getPreloadPath(),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  win.loadURL(getRendererUrl('/panel'));

  win.on('blur', () => {
    win.hide();
  });

  return win;
}

function createSearchWindow(): BrowserWindow {
  const win = new BrowserWindow({
    width: 1040,
    height: 700,
    minWidth: 820,
    minHeight: 520,
    title: 'DigUp Search',
    show: false,
    autoHideMenuBar: true,
    webPreferences: {
      preload: getPreloadPath(),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  win.loadURL(getRendererUrl('/window'));

  win.on('close', (e) => {
    // Hide instead of destroying if user wants fast reopen
  });

  return win;
}

function createSettingsWindow(tab = 'general'): BrowserWindow {
  const win = new BrowserWindow({
    width: 720,
    height: 560,
    resizable: false,
    title: 'DigUp Settings',
    show: false,
    autoHideMenuBar: true,
    webPreferences: {
      preload: getPreloadPath(),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  win.loadURL(getRendererUrl(`/settings?tab=${tab}`));
  return win;
}

function createOnboardingWindow(): BrowserWindow {
  const win = new BrowserWindow({
    width: 680,
    height: 520,
    resizable: false,
    title: 'Welcome to DigUp',
    show: false,
    autoHideMenuBar: true,
    webPreferences: {
      preload: getPreloadPath(),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  win.loadURL(getRendererUrl('/onboarding'));
  return win;
}

function toggleSearchPanel(): void {
  if (!searchPanelWindow) {
    searchPanelWindow = createSearchPanel();
  }
  if (searchPanelWindow.isVisible()) {
    searchPanelWindow.hide();
  } else {
    searchPanelWindow.show();
    searchPanelWindow.focus();
  }
}

function setupTray(): void {
  // Create simple 16x16 icon for tray
  const iconCanvas = nativeImage.createFromBuffer(
    Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAYElEQVQ4T2NkoBAwUqifYdQAUhgYGBj+/fv3/8+fPwyMjIx4xRlRNeD/379/GRgYGBkYGBgYfvz4gVcDTB/eUDA4DCgwgHAYkG8AxoFAGhh1AJEMgBvgBgcGg+EAAAYXGBF2K66CAAAAAElFTkSuQmCC',
      'base64'
    )
  );

  tray = new Tray(iconCanvas);
  tray.setToolTip('DigUp - File Search by Meaning');

  const updateMenu = () => {
    const isPaused = engine ? engine.getStatus().paused : false;
    const contextMenu = Menu.buildFromTemplate([
      {
        label: `Search (${settings.hotkey})`,
        click: () => toggleSearchPanel()
      },
      {
        label: 'Open Search Window (Ctrl+O)',
        click: () => {
          if (!searchWindow) searchWindow = createSearchWindow();
          searchWindow.show();
          searchWindow.focus();
        }
      },
      {
        label: 'Settings...',
        click: () => {
          if (!settingsWindow) settingsWindow = createSettingsWindow();
          settingsWindow.show();
          settingsWindow.focus();
        }
      },
      { type: 'separator' },
      {
        label: isPaused ? 'Resume Indexing' : 'Pause Indexing',
        click: () => {
          engine.setPaused(!isPaused);
          updateMenu();
        }
      },
      { type: 'separator' },
      {
        label: 'Check for Updates...',
        click: () => {
          dialog.showMessageBox({
            type: 'info',
            title: 'DigUp Updates',
            message: 'You have the latest version of DigUp.'
          });
        }
      },
      {
        label: 'Quit DigUp',
        click: () => {
          app.quit();
        }
      }
    ]);

    tray!.setContextMenu(contextMenu);
  };

  updateMenu();
  tray.on('click', () => toggleSearchPanel());
}

function registerHotkey(combo: string): boolean {
  globalShortcut.unregisterAll();
  const registered = globalShortcut.register(combo, () => {
    toggleSearchPanel();
  });
  if (!registered) {
    console.warn(`Failed to register global shortcut: ${combo}`);
  }
  return registered;
}

app.whenReady().then(async () => {
  settings = SettingsStore.load();

  // Initialize Engine
  engine = new Engine(
    {
      excludedFolders: settings.excludedFolders,
      excludedExtensions: new Set(settings.excludedTypes)
    },
    (status: EngineStatus) => {
      // Broadcast status to windows
      if (searchPanelWindow) searchPanelWindow.webContents.send('engine:status', status);
      if (searchWindow) searchWindow.webContents.send('engine:status', status);
      if (settingsWindow) settingsWindow.webContents.send('engine:status', status);
    }
  );

  await engine.start();

  if (settings.roots.length > 0) {
    engine.setFolders(settings.roots, settings.excludedFolders);
  }
  if (settings.codeRoots.length > 0) {
    engine.setCodeFolders(settings.codeRoots);
  }

  // Register hotkey
  registerHotkey(settings.hotkey);

  // Setup Tray
  setupTray();

  // Create search panel window
  searchPanelWindow = createSearchPanel();

  // Show onboarding on first run
  if (!settings.onboarded) {
    onboardingWindow = createOnboardingWindow();
    onboardingWindow.show();
  }

  // Monitor battery power
  powerMonitor.on('on-battery', () => {
    if (!settings.indexOnBattery) {
      engine.setPaused(true);
    }
  });

  powerMonitor.on('on-ac', () => {
    if (!settings.indexOnBattery) {
      engine.setPaused(false);
    }
  });
});

app.on('will-quit', () => {
  globalShortcut.unregisterAll();
  if (engine) engine.close();
});

// IPC handlers
ipcMain.handle('engine:search', async (_event, query: string, kinds?: FileKind[]) => {
  const kindsSet = kinds && kinds.length > 0 ? new Set<FileKind>(kinds) : undefined;
  return engine.search(query, 20, kindsSet);
});

ipcMain.handle('engine:getStatus', async () => {
  return engine.getStatus();
});

ipcMain.handle('settings:get', async () => {
  return SettingsStore.load();
});

ipcMain.handle('settings:save', async (_event, newSettings: Partial<AppSettings>) => {
  settings = SettingsStore.save(newSettings);
  if (newSettings.hotkey) {
    registerHotkey(newSettings.hotkey);
  }
  if (newSettings.roots) {
    engine.setFolders(newSettings.roots, settings.excludedFolders);
  }
  if (newSettings.codeRoots) {
    engine.setCodeFolders(newSettings.codeRoots);
  }
  return settings;
});

ipcMain.handle('dialog:chooseFolder', async () => {
  const res = await dialog.showOpenDialog({
    properties: ['openDirectory']
  });
  if (res.canceled || res.filePaths.length === 0) return null;
  return res.filePaths[0];
});

ipcMain.handle('engine:openResult', async (_event, hit: SearchHit) => {
  if (hit.kind === 'code') {
    CodeEditorService.open(hit.path, Math.round(hit.focus || hit.loc || 1), settings.codeEditor);
    return;
  }
  // Open default app
  shell.openPath(hit.path);
});

ipcMain.handle('engine:revealInExplorer', async (_event, filePath: string) => {
  shell.showItemInFolder(filePath);
});

ipcMain.handle('engine:copyToClipboard', async (_event, text: string) => {
  clipboard.writeText(text);
});

ipcMain.handle('model:download', async (event) => {
  await ModelFiles.downloadAll((p) => {
    event.sender.send('model:progress', p);
  });
});

ipcMain.on('window:hideSearchPanel', () => {
  if (searchPanelWindow) searchPanelWindow.hide();
});

ipcMain.on('window:openSearchWindow', () => {
  if (!searchWindow) searchWindow = createSearchWindow();
  searchWindow.show();
  searchWindow.focus();
});

ipcMain.on('window:openSettingsWindow', (_event, tab = 'general') => {
  if (!settingsWindow) settingsWindow = createSettingsWindow(tab);
  settingsWindow.show();
  settingsWindow.focus();
});
