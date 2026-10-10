import { execFile, spawn } from 'child_process';
import { shell } from 'electron';
import fs from 'fs';
import path from 'path';

export interface CodeEditorInfo {
  id: string;
  name: string;
  command: string;
}

export class CodeEditorService {
  public static readonly knownEditors: CodeEditorInfo[] = [
    { id: 'vscode', name: 'Visual Studio Code', command: 'code' },
    { id: 'cursor', name: 'Cursor', command: 'cursor' },
    { id: 'sublime', name: 'Sublime Text', command: 'subl' },
    { id: 'idea', name: 'IntelliJ IDEA', command: 'idea' },
    { id: 'pycharm', name: 'PyCharm', command: 'pycharm' },
    { id: 'webstorm', name: 'WebStorm', command: 'webstorm' },
    { id: 'clion', name: 'CLion', command: 'clion' },
    { id: 'rider', name: 'Rider', command: 'rider' },
    { id: 'notepad++', name: 'Notepad++', command: 'notepad++' }
  ];

  public static open(filePath: string, line = 1, editorId = 'vscode'): void {
    const editor = this.knownEditors.find((e) => e.id === editorId) || this.knownEditors[0];

    // Try URL scheme first for VS Code / Cursor
    if (editorId === 'vscode') {
      const url = `vscode://file/${encodeURIComponent(filePath)}:${line}:1`;
      shell.openExternal(url).catch(() => {
        this.runEditorCommand('code', ['--goto', `${filePath}:${line}`], filePath);
      });
      return;
    }

    if (editorId === 'cursor') {
      const url = `cursor://file/${encodeURIComponent(filePath)}:${line}:1`;
      shell.openExternal(url).catch(() => {
        this.runEditorCommand('cursor', ['--goto', `${filePath}:${line}`], filePath);
      });
      return;
    }

    if (editorId === 'sublime') {
      this.runEditorCommand('subl', [`${filePath}:${line}`], filePath);
      return;
    }

    if (['idea', 'pycharm', 'webstorm', 'clion', 'rider'].includes(editorId)) {
      this.runEditorCommand(editor.command, ['--line', String(line), filePath], filePath);
      return;
    }

    if (editorId === 'notepad++') {
      this.runEditorCommand('notepad++', ['-n' + line, filePath], filePath);
      return;
    }

    // Default plain open
    shell.openPath(filePath);
  }

  private static runEditorCommand(cmd: string, args: string[], fallbackPath: string): void {
    const proc = spawn(cmd, args, { shell: true, detached: true, stdio: 'ignore' });
    proc.on('error', () => {
      shell.openPath(fallbackPath);
    });
    proc.unref();
  }
}
