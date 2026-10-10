# DigUp for Windows

<p align="center"><b>Describe it. Dig it up.</b><br>
Search the files on your Windows PC by what's in them, with Google DeepMind's
<a href="https://huggingface.co/google/embeddinggemma-2">EmbeddingGemma 2</a>.</p>

<p align="center"><b>Native Windows Desktop App</b> · Windows 10/11 · Free and open source</p>

EmbeddingGemma 2 is the multimodal embedding model Google DeepMind released in October 2026. It puts text, images, audio, and video into one shared 768-dimensional space: a photo of a dog on a beach lands next to the words "a dog on the beach", and a recording of someone talking about sleep lands next to "where they talk about sleep". It understands more than 100 languages.

**DigUp for Windows** brings this full capability to your Windows PC with 100% functionality parity with the original macOS version.

---

## What You Can Find

- **"zebra in a video"** opens the clip at the moment the zebra appears.
- **"where they talk about sleep"** jumps to that exact minute of a podcast or recording.
- **"the clause about pets in the lease"** shows the PDF page, with matching words highlighted.
- **"a dog on the beach"** finds the photo, and **"payment declined error"** finds the screenshot.
- A search in English can find notes written in Bengali, Arabic, or any of the 100+ supported languages.
- **"code: retry a failed call with backoff"** finds the function in your code repositories and opens it at the exact line in VS Code, Cursor, Visual Studio, JetBrains IDEs, or Sublime Text.

Exact keywords, invoice numbers, error codes, and names are matched via SQLite FTS5 full-text indexing, fusing meaning and exact strings into a unified hybrid ranking score.

---

## Privacy First

- **100% Offline & Private**: Everything indexed stays on your PC. No cloud uploads, no telemetry, no accounts.
- Goes online only once to download the open-source model weights (865 MB).

---

## Keyboard Shortcuts & Using DigUp

| Key | Action |
|---|---|
| **Alt+Space** (or **Ctrl+Shift+Space**) | Opens the Spotlight-like floating search panel from any app |
| **↓ ↑** | Moves through results |
| **Enter** | Opens result (videos/recordings at timestamp, PDFs at page, code in editor at line) |
| **Ctrl+Enter** | Reveals file in Windows File Explorer |
| **Ctrl+C** | Copies file path to clipboard |
| **Ctrl+O** | Opens the Full Search Window (Gallery grid with kind filters) |
| **Esc** | Closes search panel |

---

## System Architecture

```
chosen folders ─► readers (Image, PDF, Audio, Video, Windows OCR, Code Extractor)
               ─► EmbeddingGemma 2 Worker process (stdio JSON lines, quits on idle)
               ─► SQLite (WAL mode): 768-d float16 vectors + FTS5 BM25 keyword index
search query   ─► Query Encoder (on-demand) ─► Z-score normalized cosine similarity + BM25 fusion
```

### Windows Enhancements & Tech Stack
- **Desktop Shell & UI**: Electron + React + Tailwind CSS with dark glassmorphism and acrylic blur.
- **Database**: `better-sqlite3` with compiled-in FTS5 and WAL mode.
- **File Watching**: `chokidar` for instant incremental sync.
- **Media Previews**: HTML5 Video/Audio players with instant timestamp jumping, PDF page viewer, syntax-highlighted code viewer.
- **Code Editors**: VS Code, Cursor, Visual Studio, Sublime Text, JetBrains (IntelliJ, PyCharm, WebStorm, CLion, Rider), Notepad++.
- **System Tray**: Live status menu with pause/resume and quick search.

---

## Build and Run

### Prerequisites
- Node.js (v18+)
- Python (3.10+)

### Commands
```powershell
# Install dependencies
npm install

# Build frontend and backend
npm run build

# Run DigUp
npm start

# Run verification test suite
node dist/engine/test.js
```

---

## License

MIT
