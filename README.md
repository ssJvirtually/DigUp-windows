# DigUp for Windows (Native Java Swing + Windows 11 Fluent)

A high-performance Windows desktop replica of [DigUp](https://github.com/ARahim3/DigUp), providing local multimodal AI search across screenshots, pictures, PDFs, documents, audio, video, and source code.

---

## 🌟 Key Features

1. **Native Windows 11 Look & Feel**:
   - Built entirely in **Java Swing** powered by [FlatLaf](https://www.formdev.com/flatlaf/) Windows 11 Fluent theme.
   - Clean dark/light mode with Segoe UI typography, rounded corners, and native window frames.
2. **Spotlight-Style Search Panel (`Alt + Space`)**:
   - Floating search bar accessible from anywhere in Windows via global hotkey.
   - Instant as-you-type keyword matching (FTS5) and debounced semantic query embedding.
   - Left-side grouped results by kind with category icons (Screenshots, Pictures, PDFs, Documents, Audio, Video, Code).
   - Rich right-side preview pane:
     - Scaled image thumbnails with dimensions and EXIF metadata.
     - Document and code syntax viewer with line numbers and matched lines highlighted.
     - PDF page excerpts and rendered scans.
     - Video / audio timestamp seeker.
3. **Full Gallery Window**:
   - Filter tabs across all categories with real-time hit counts.
   - Table and grid list with instant detail drawer.
4. **100% Mathematical & Architectural Parity with macOS DigUp**:
   - **Vectors**: 768-dimensional float32/float16 embeddings stored in SQLite WAL mode.
   - **Modality Z-Score Normalization**: Centering per modality (text, image, audio) and scaling by global standard deviation.
   - **Hybrid Rank Fusion**: `score = meaningWeight * max(z, 0) + wordsWeight * pow(coverage, 1.5) + kind_boost`.
   - **AST & Code Symbol Resolver**: Direct definition and identifier matching (`CodeLookup`).
5. **System Tray Integration**:
   - Native Windows system tray icon with background indexing status and context menu.

---

## 🚀 How to Launch the GUI App

### Option 1: Double-Click the Launcher
Double-click `run.bat` in this directory.

### Option 2: Command Line
```powershell
# Using the pre-built JAR:
java -jar target\digup-windows-1.0.0.jar

# Or to run in the background without a terminal window:
start javaw -jar target\digup-windows-1.0.0.jar
```

### Global Shortcut
- Press **`Alt + Space`** anytime to summon or dismiss the search panel!

---

## 🛠️ Build from Source
```powershell
# Compile and run unit tests:
mvn test

# Package JAR and dependencies:
mvn package -DskipTests
```
