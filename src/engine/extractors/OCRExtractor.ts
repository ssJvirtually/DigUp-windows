import { execFile } from 'child_process';
import path from 'path';

export class OCRExtractor {
  /**
   * Recognizes text in an image file on Windows using Windows.Media.Ocr
   */
  public static async recognizeText(imagePath: string): Promise<string> {
    return new Promise((resolve) => {
      // Windows.Media.Ocr via PowerShell
      const script = `
        Add-Type -AssemblyName System.Drawing
        [Windows.Media.Ocr.OcrEngine, Windows.Foundation.UniversalApiContract, ContentType = WindowsRuntime] | Out-Null
        [Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation.UniversalApiContract, ContentType = WindowsRuntime] | Out-Null
        [Windows.Storage.StorageFile, Windows.Foundation.UniversalApiContract, ContentType = WindowsRuntime] | Out-Null

        $file = [Windows.Storage.StorageFile]::GetFileFromPathAsync("${imagePath.replace(/\\/g, '\\\\')}").GetAwaiter().GetResult()
        $stream = $file.OpenAsync([Windows.Storage.FileAccessMode]::Read).GetAwaiter().GetResult()
        $decoder = [Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream).GetAwaiter().GetResult()
        $bitmap = $decoder.GetSoftwareBitmapAsync().GetAwaiter().GetResult()
        $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
        if ($engine) {
          $result = $engine.RecognizeAsync($bitmap).GetAwaiter().GetResult()
          $result.Text
        }
      `;

      execFile(
        'powershell.exe',
        ['-NoProfile', '-NonInteractive', '-Command', script],
        { timeout: 8000 },
        (err, stdout) => {
          if (err || !stdout) {
            resolve('');
          } else {
            resolve(stdout.trim());
          }
        }
      );
    });
  }
}
