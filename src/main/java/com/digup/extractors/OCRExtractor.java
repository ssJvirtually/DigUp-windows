package com.digup.extractors;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Windows native OCR integration via Windows.Media.Ocr WinRT API.
 */
public final class OCRExtractor {

    private OCRExtractor() {}

    /**
     * Extracts text from an image file using Windows native OCR.
     */
    public static String recognizeText(File imageFile) {
        if (!imageFile.exists()) return "";
        try {
            // Fast PowerShell one-liner using Windows.Media.Ocr
            String script = String.format(
                "$path = '%s'; " +
                "[Windows.Globalization.Language,Windows.Globalization,ContentType=WindowsRuntime] | Out-Null; " +
                "[Windows.Graphics.Imaging.BitmapDecoder,Windows.Graphics.Imaging,ContentType=WindowsRuntime] | Out-Null; " +
                "[Windows.Media.Ocr.OcrEngine,Windows.Media.Ocr,ContentType=WindowsRuntime] | Out-Null; " +
                "$file = [Windows.Storage.StorageFile]::GetFileFromPathAsync($path).GetAwaiter().GetResult(); " +
                "$stream = $file.OpenAsync([Windows.Storage.FileAccessMode]::Read).GetAwaiter().GetResult(); " +
                "$decoder = [Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream).GetAwaiter().GetResult(); " +
                "$bitmap = $decoder.GetSoftwareBitmapAsync().GetAwaiter().GetResult(); " +
                "$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages(); " +
                "if ($engine) { $result = $engine.RecognizeAsync($bitmap).GetAwaiter().GetResult(); Write-Output $result.Text }",
                imageFile.getAbsolutePath().replace("'", "''")
            );

            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script);
            Process p = pb.start();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception ignored) {
            return "";
        }
    }
}
