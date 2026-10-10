import { contextBridge, ipcRenderer } from 'electron';
import { SearchHit, FileKind } from '../shared/types.js';
import { EngineStatus } from '../engine/Engine.js';
import { AppSettings } from './SettingsStore.js';
import { ModelDownloadProgress } from '../worker/ModelFiles.js';

export interface DigUpAPI {
  search: (query: string, kinds?: FileKind[]) => Promise<SearchHit[]>;
  getStatus: () => Promise<EngineStatus>;
  onStatus: (callback: (status: EngineStatus) => void) => () => void;
  getSettings: () => Promise<AppSettings>;
  saveSettings: (settings: Partial<AppSettings>) => Promise<AppSettings>;
  chooseFolder: () => Promise<string | null>;
  openResult: (hit: SearchHit) => Promise<void>;
  revealInExplorer: (filePath: string) => Promise<void>;
  copyToClipboard: (text: string) => Promise<void>;
  startModelDownload: () => Promise<void>;
  onModelDownloadProgress: (callback: (p: ModelDownloadProgress) => void) => () => void;
  hideSearchPanel: () => void;
  openSearchWindow: () => void;
  openSettingsWindow: (tab?: string) => void;
}

const api: DigUpAPI = {
  search: (query: string, kinds?: FileKind[]) => ipcRenderer.invoke('engine:search', query, kinds),
  getStatus: () => ipcRenderer.invoke('engine:getStatus'),
  onStatus: (callback: (status: EngineStatus) => void) => {
    const handler = (_event: any, status: EngineStatus) => callback(status);
    ipcRenderer.on('engine:status', handler);
    return () => ipcRenderer.removeListener('engine:status', handler);
  },
  getSettings: () => ipcRenderer.invoke('settings:get'),
  saveSettings: (settings: Partial<AppSettings>) => ipcRenderer.invoke('settings:save', settings),
  chooseFolder: () => ipcRenderer.invoke('dialog:chooseFolder'),
  openResult: (hit: SearchHit) => ipcRenderer.invoke('engine:openResult', hit),
  revealInExplorer: (filePath: string) => ipcRenderer.invoke('engine:revealInExplorer', filePath),
  copyToClipboard: (text: string) => ipcRenderer.invoke('engine:copyToClipboard', text),
  startModelDownload: () => ipcRenderer.invoke('model:download'),
  onModelDownloadProgress: (callback: (p: ModelDownloadProgress) => void) => {
    const handler = (_event: any, progress: ModelDownloadProgress) => callback(progress);
    ipcRenderer.on('model:progress', handler);
    return () => ipcRenderer.removeListener('model:progress', handler);
  },
  hideSearchPanel: () => ipcRenderer.send('window:hideSearchPanel'),
  openSearchWindow: () => ipcRenderer.send('window:openSearchWindow'),
  openSettingsWindow: (tab?: string) => ipcRenderer.send('window:openSettingsWindow', tab)
};

contextBridge.exposeInMainWorld('api', api);
