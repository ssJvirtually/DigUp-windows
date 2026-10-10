import path from 'path';
import os from 'os';

export const APP_NAME = 'DigUp';
export const DIMENSION = 768; // EmbeddingGemma 2 dimension

export const MODEL_REPO = 'ggml-org/embeddinggemma-2-GGUF';
export const MODEL_REVISION = 'bfcd298762cc34d0357ece5ebdd31791a3a374d8';

export const TEXT_MODEL_FILE = {
  name: 'embeddinggemma-2-Q8_0.gguf',
  bytes: 309_855_456,
  sha256: '2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135'
};

export const MEDIA_MODEL_FILE = {
  name: 'mmproj-embeddinggemma-2-Q8_0.gguf',
  bytes: 554_821_024,
  sha256: 'c4a8a52691ecef40618438928bdf9e68379b854e24166f292592353db0aab64f'
};

export const TOTAL_MODEL_BYTES = TEXT_MODEL_FILE.bytes + MEDIA_MODEL_FILE.bytes;

// Default Application Support directory on Windows (%APPDATA%\DigUp)
export function getAppDataDir(): string {
  const appData = process.env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming');
  return path.join(appData, 'DigUp');
}

export function getModelsDir(): string {
  return path.join(getAppDataDir(), 'Models');
}

export function getIndexDir(): string {
  return path.join(getAppDataDir(), 'index');
}
