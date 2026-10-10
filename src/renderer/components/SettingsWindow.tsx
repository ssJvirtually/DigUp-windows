import React, { useState, useEffect } from 'react';
import {
  Settings as GearIcon,
  Folder,
  Code,
  HardDrive,
  Plus,
  Trash2,
  RefreshCw,
  CheckCircle2,
  AlertCircle
} from 'lucide-react';
import { AppSettings } from '../../main/SettingsStore.js';
import { EngineStatus } from '../../engine/Engine.js';

export const SettingsWindow: React.FC = () => {
  const [activeTab, setActiveTab] = useState<'general' | 'folders' | 'code' | 'storage'>('general');
  const [settings, setSettings] = useState<AppSettings | null>(null);
  const [status, setStatus] = useState<EngineStatus | null>(null);
  const [isSaving, setIsSaving] = useState(false);

  useEffect(() => {
    window.api.getSettings().then(setSettings);
    window.api.getStatus().then(setStatus);

    const params = new URLSearchParams(window.location.hash.split('?')[1]);
    const tabParam = params.get('tab');
    if (tabParam && ['general', 'folders', 'code', 'storage'].includes(tabParam)) {
      setActiveTab(tabParam as any);
    }
  }, []);

  const updateSettings = async (patch: Partial<AppSettings>) => {
    setIsSaving(true);
    const updated = await window.api.saveSettings(patch);
    setSettings(updated);
    setIsSaving(false);
  };

  const addFolder = async () => {
    const selected = await window.api.chooseFolder();
    if (selected && settings) {
      if (!settings.roots.includes(selected)) {
        await updateSettings({ roots: [...settings.roots, selected] });
      }
    }
  };

  const removeFolder = async (folder: string) => {
    if (settings) {
      await updateSettings({ roots: settings.roots.filter((r) => r !== folder) });
    }
  };

  const addCodeFolder = async () => {
    const selected = await window.api.chooseFolder();
    if (selected && settings) {
      if (!settings.codeRoots.includes(selected)) {
        await updateSettings({ codeRoots: [...settings.codeRoots, selected] });
      }
    }
  };

  const removeCodeFolder = async (folder: string) => {
    if (settings) {
      await updateSettings({ codeRoots: settings.codeRoots.filter((r) => r !== folder) });
    }
  };

  if (!settings) {
    return <div className="p-8 text-gray-400">Loading settings...</div>;
  }

  return (
    <div className="w-screen h-screen flex flex-col bg-[#18191c] text-gray-200 select-none">
      {/* Settings Tab Navigation */}
      <div className="h-14 border-b border-white/10 px-6 flex items-center gap-6 bg-black/20 text-xs font-medium">
        {[
          { id: 'general', label: 'General', icon: GearIcon },
          { id: 'folders', label: 'Folders', icon: Folder },
          { id: 'code', label: 'Code', icon: Code },
          { id: 'storage', label: 'Storage', icon: HardDrive }
        ].map((tab) => {
          const Icon = tab.icon;
          const isActive = activeTab === tab.id;
          return (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id as any)}
              className={`flex items-center gap-2 py-2 px-3 rounded-lg transition-colors ${
                isActive
                  ? 'bg-blue-600/30 text-blue-400 font-semibold border border-blue-500/30'
                  : 'text-gray-400 hover:text-white hover:bg-white/5'
              }`}
            >
              <Icon className="w-4 h-4" />
              <span>{tab.label}</span>
            </button>
          );
        })}
      </div>

      {/* Tab Contents */}
      <div className="flex-1 p-6 overflow-y-auto custom-scrollbar">
        {activeTab === 'general' && (
          <div className="max-w-xl flex flex-col gap-6 text-sm">
            <div>
              <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider mb-2">
                Shortcut to open DigUp
              </label>
              <input
                type="text"
                value={settings.hotkey}
                onChange={(e) => updateSettings({ hotkey: e.target.value })}
                className="w-full bg-black/30 border border-white/15 rounded-lg px-3.5 py-2 text-white outline-none focus:border-blue-500 font-mono text-xs"
              />
              <p className="text-xs text-gray-400 mt-1.5">
                Default: <span className="font-mono text-gray-300">Alt+Space</span> or{' '}
                <span className="font-mono text-gray-300">Ctrl+Shift+Space</span>
              </p>
            </div>

            <div className="flex items-center justify-between p-3.5 rounded-xl bg-white/[0.03] border border-white/10">
              <div>
                <div className="font-medium text-white">Start DigUp at login</div>
                <div className="text-xs text-gray-400">Launch in the background on startup</div>
              </div>
              <input
                type="checkbox"
                checked={settings.launchAtLogin}
                onChange={(e) => updateSettings({ launchAtLogin: e.target.checked })}
                className="w-4 h-4 accent-blue-600 cursor-pointer"
              />
            </div>

            <div className="flex items-center justify-between p-3.5 rounded-xl bg-white/[0.03] border border-white/10">
              <div>
                <div className="font-medium text-white">Index on battery</div>
                <div className="text-xs text-gray-400">
                  Allow background indexing while on battery power
                </div>
              </div>
              <input
                type="checkbox"
                checked={settings.indexOnBattery}
                onChange={(e) => updateSettings({ indexOnBattery: e.target.checked })}
                className="w-4 h-4 accent-blue-600 cursor-pointer"
              />
            </div>
          </div>
        )}

        {activeTab === 'folders' && (
          <div className="max-w-xl flex flex-col gap-5 text-sm">
            <div className="flex items-center justify-between">
              <div>
                <h3 className="font-semibold text-white">Indexed Folders</h3>
                <p className="text-xs text-gray-400">Only the folders you choose are searched</p>
              </div>
              <button
                onClick={addFolder}
                className="px-3 py-1.5 rounded-lg bg-blue-600 hover:bg-blue-500 text-white text-xs font-medium flex items-center gap-1.5 shadow"
              >
                <Plus className="w-3.5 h-3.5" />
                Add Folder...
              </button>
            </div>

            <div className="flex flex-col gap-2">
              {settings.roots.length === 0 ? (
                <div className="p-6 rounded-xl border border-dashed border-white/15 text-center text-gray-500 text-xs">
                  No folders added yet. Click "Add Folder..." to start.
                </div>
              ) : (
                settings.roots.map((folder) => (
                  <div
                    key={folder}
                    className="flex items-center justify-between p-3 rounded-xl bg-white/[0.03] border border-white/10"
                  >
                    <div className="flex items-center gap-3 truncate">
                      <Folder className="w-4 h-4 text-blue-400 shrink-0" />
                      <span className="truncate text-xs font-medium text-gray-200">{folder}</span>
                    </div>
                    <button
                      onClick={() => removeFolder(folder)}
                      className="p-1.5 rounded hover:bg-red-500/20 text-gray-400 hover:text-red-400 transition-colors"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                ))
              )}
            </div>

            <div className="mt-4 pt-4 border-t border-white/10">
              <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider mb-2">
                Exclude File Types (comma-separated)
              </label>
              <input
                type="text"
                value={settings.excludedTypes.join(', ')}
                onChange={(e) =>
                  updateSettings({
                    excludedTypes: e.target.value.split(',').map((s) => s.trim()).filter(Boolean)
                  })
                }
                placeholder="e.g. log, tmp, bak"
                className="w-full bg-black/30 border border-white/15 rounded-lg px-3.5 py-2 text-white outline-none focus:border-blue-500 text-xs"
              />
            </div>
          </div>
        )}

        {activeTab === 'code' && (
          <div className="max-w-xl flex flex-col gap-5 text-sm">
            <div>
              <h3 className="font-semibold text-white">Code Search</h3>
              <p className="text-xs text-gray-400">
                Index source files, documentation, and notebooks across your projects
              </p>
            </div>

            <div>
              <label className="block text-xs font-semibold text-gray-400 uppercase tracking-wider mb-2">
                Open Code In
              </label>
              <select
                value={settings.codeEditor}
                onChange={(e) => updateSettings({ codeEditor: e.target.value })}
                className="w-full bg-black/30 border border-white/15 rounded-lg px-3.5 py-2 text-white outline-none focus:border-blue-500 text-xs"
              >
                <option value="vscode">Visual Studio Code</option>
                <option value="cursor">Cursor</option>
                <option value="sublime">Sublime Text</option>
                <option value="idea">IntelliJ IDEA</option>
                <option value="pycharm">PyCharm</option>
                <option value="webstorm">WebStorm</option>
                <option value="notepad++">Notepad++</option>
              </select>
            </div>

            <div className="flex items-center justify-between pt-2">
              <div>
                <h4 className="font-medium text-white text-xs">Code Repositories</h4>
                <p className="text-[11px] text-gray-400">Folders searched when using "code:"</p>
              </div>
              <button
                onClick={addCodeFolder}
                className="px-3 py-1.5 rounded-lg bg-blue-600 hover:bg-blue-500 text-white text-xs font-medium flex items-center gap-1.5 shadow"
              >
                <Plus className="w-3.5 h-3.5" />
                Add Code Repo...
              </button>
            </div>

            <div className="flex flex-col gap-2">
              {settings.codeRoots.length === 0 ? (
                <div className="p-6 rounded-xl border border-dashed border-white/15 text-center text-gray-500 text-xs">
                  No code folders added.
                </div>
              ) : (
                settings.codeRoots.map((repo) => (
                  <div
                    key={repo}
                    className="flex items-center justify-between p-3 rounded-xl bg-white/[0.03] border border-white/10"
                  >
                    <div className="flex items-center gap-3 truncate">
                      <Code className="w-4 h-4 text-cyan-400 shrink-0" />
                      <span className="truncate text-xs font-medium text-gray-200">{repo}</span>
                    </div>
                    <button
                      onClick={() => removeCodeFolder(repo)}
                      className="p-1.5 rounded hover:bg-red-500/20 text-gray-400 hover:text-red-400 transition-colors"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                ))
              )}
            </div>
          </div>
        )}

        {activeTab === 'storage' && (
          <div className="max-w-xl flex flex-col gap-5 text-sm">
            <div>
              <h3 className="font-semibold text-white">Model & Index Storage</h3>
              <p className="text-xs text-gray-400">EmbeddingGemma 2 AI model and vector index</p>
            </div>

            <div className="p-4 rounded-xl bg-white/[0.03] border border-white/10 flex flex-col gap-3">
              <div className="flex items-center justify-between">
                <span className="text-xs text-gray-400">AI Model</span>
                <span className="text-xs font-medium text-white">EmbeddingGemma 2 Q8_0 (865 MB)</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-xs text-gray-400">Total Searchable Files</span>
                <span className="text-xs font-mono text-white">{status?.searchable || 0}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-xs text-gray-400">Indexed Vectors</span>
                <span className="text-xs font-mono text-emerald-400">{status?.vectors || 0}</span>
              </div>
            </div>

            <div className="pt-2">
              <button
                onClick={() => {
                  // Re-index all files
                  window.api.saveSettings({ roots: settings.roots });
                }}
                className="px-4 py-2 rounded-lg bg-white/10 hover:bg-white/15 text-gray-200 text-xs font-medium flex items-center gap-2"
              >
                <RefreshCw className="w-4 h-4" />
                Re-index All Files
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
