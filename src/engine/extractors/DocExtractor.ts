import fs from 'fs';
import path from 'path';
import mammoth from 'mammoth';

export class DocExtractor {
  public static async text(filePath: string): Promise<string> {
    const ext = path.extname(filePath).slice(1).toLowerCase();

    if (ext === 'docx') {
      try {
        const result = await mammoth.extractRawText({ path: filePath });
        return result.value || '';
      } catch (err) {
        throw new Error(`Cannot parse docx: ${err}`);
      }
    }

    if (ext === 'html' || ext === 'htm') {
      const raw = fs.readFileSync(filePath, 'utf8');
      return this.plainTextFromHTML(raw);
    }

    if (ext === 'rtf') {
      const raw = fs.readFileSync(filePath, 'utf8');
      return this.stripRTF(raw);
    }

    // txt, md, etc.
    try {
      return fs.readFileSync(filePath, 'utf8');
    } catch {
      return fs.readFileSync(filePath, 'latin1');
    }
  }

  public static plainTextFromHTML(html: string): string {
    let text = html;
    text = text.replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, ' ');
    text = text.replace(/<style\b[^<]*(?:(?!<\/style>)<[^<]*)*<\/style>/gi, ' ');
    text = text.replace(/<!--[\s\S]*?-->/g, ' ');
    text = text.replace(/<head\b[^<]*(?:(?!<\/head>)<[^<]*)*<\/head>/gi, ' ');
    text = text.replace(/<(?:br|\/p|\/div|\/li|\/h[1-6]|\/tr|\/section|\/article)\b[^>]*>/gi, '\n');
    text = text.replace(/<[^>]+>/g, ' ');

    const entities: Record<string, string> = {
      '&nbsp;': ' ',
      '&amp;': '&',
      '&lt;': '<',
      '&gt;': '>',
      '&quot;': '"',
      '&#39;': "'",
      '&apos;': "'",
      '&mdash;': '—',
      '&ndash;': '–',
      '&hellip;': '…'
    };

    for (const [k, v] of Object.entries(entities)) {
      text = text.replaceAll(k, v);
    }

    // Decode numeric entities (&#123; or &#x1a;)
    text = text.replace(/&#(x[0-9a-fA-F]+|[0-9]+);/g, (_, code) => {
      const val = code.startsWith('x') ? parseInt(code.slice(1), 16) : parseInt(code, 10);
      return isNaN(val) ? ' ' : String.fromCodePoint(val);
    });

    return text.replace(/[ \t]+/g, ' ').replace(/\s*\n\s*/g, '\n').trim();
  }

  public static stripRTF(rtf: string): string {
    return rtf
      .replace(/\\par[d]?/g, '\n')
      .replace(/\{\\*?\\[^{}]+;\}|\\(?:[A-Za-z]+[-]?\d*|[^\r\n \t{}[\]\\])/g, ' ')
      .replace(/[{}]/g, '')
      .replace(/[ \t]+/g, ' ')
      .replace(/\s*\n\s*/g, '\n')
      .trim();
  }

  public static chunks(text: string, size = 1800, overlap = 200, limit = 5000): string[] {
    const clean = text
      .replace(/[ \t\u00A0]+/g, ' ')
      .replace(/\n\s*\n\s*\n+/g, '\n\n')
      .trim();

    if (!clean) return [];

    const chunks: string[] = [];
    let start = 0;

    while (start < clean.length && chunks.length < limit) {
      let end = Math.min(clean.length, start + size);

      if (end < clean.length) {
        const lastSpace = clean.lastIndexOf(' ', end);
        if (lastSpace > start + Math.floor(size / 2)) {
          end = lastSpace;
        }
      }

      const chunk = clean.slice(start, end).trim();
      if (chunk.length > 0) {
        chunks.push(chunk);
      }

      if (end >= clean.length) break;

      let next = Math.max(start + 1, end - overlap);
      while (next < end && !/\s/.test(clean[next])) {
        next++;
      }
      start = next < end ? next : end;
    }

    return chunks;
  }

  public static excerpt(text: string, limit = 240): string {
    const line = text.replace(/\s+/g, ' ').trim();
    return line.length > limit ? line.slice(0, limit) + '…' : line;
  }
}
