import fs from 'fs';
import path from 'path';
import { getAppDataDir } from '../shared/constants.js';

export interface AppSettings {
  hotkey: string;
  roots: string[];
  excludedFolders: string[];
  excludedTypes: string[];
  codeRoots: string[];
  codeEditor: string;
  indexOnBattery: boolean;
  launchAtLogin: boolean;
  onboarded: boolean;
}

export const defaultSettings: AppSettings = {
  hotkey: 'Alt+Space',
  roots: [],
  excludedFolders: [],
  excludedTypes: [],
  codeRoots: [],
  codeEditor: 'vscode',
  indexOnBattery: true,
  launchAtLogin: false,
  onboarded: false
};

export class SettingsStore {
  private static filePath = path.join(getAppDataDir(), 'settings.json');

  public static load(): AppSettings {
    try {
      if (fs.existsSync(this.filePath)) {
        const raw = fs.readFileSync(this.filePath, 'utf8');
        return { ...defaultSettings, ...JSON.parse(raw) };
      }
    } catch {}
    return { ...defaultSettings };
  }

  public static save(settings: Partial<AppSettings>): AppSettings {
    const current = this.load();
    const updated = { ...current, ...settings };
    const dir = path.dirname(this.filePath);
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }
    fs.writeFileSync(this.filePath, JSON.stringify(updated, null, 2), 'utf8');
    return updated;
  }
}
