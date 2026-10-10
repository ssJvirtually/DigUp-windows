// Text normalization and keyword search utilities matching DigUpKit's SearchText

export class SearchText {
  /**
   * Normalizes text for FTS: NFKC decomposition, stripping invisible formatting characters,
   * normalizing Arabic alefs, removing harakat/tatweel.
   */
  public static normalized(text: string): string {
    const nfkc = text.normalize('NFKC');
    let out = '';

    for (let i = 0; i < nfkc.length; i++) {
      const code = nfkc.charCodeAt(i);

      if (code === 0x200b) {
        out += ' '; // zero-width space -> space
        continue;
      }

      // Invisible characters, joiners, direction marks, BOM
      if (
        code === 0x00ad ||
        code === 0x061c ||
        (code >= 0x200c && code <= 0x200f) ||
        (code >= 0x202a && code <= 0x202e) ||
        (code >= 0x2060 && code <= 0x2064) ||
        (code >= 0x2066 && code <= 0x2069) ||
        code === 0xfeff
      ) {
        continue;
      }

      // Arabic tatweel, harakat, dagger alef
      if (code === 0x0640 || (code >= 0x064b && code <= 0x065f) || code === 0x0670) {
        continue;
      }

      // Arabic alef variants -> plain alef
      if (code === 0x0622 || code === 0x0623 || code === 0x0625 || code === 0x0671) {
        out += '\u0627';
        continue;
      }

      out += nfkc[i];
    }

    return out;
  }

  /**
   * True for scripts that attach endings (Indic scripts: Bengali, Devanagari; Arabic)
   */
  public static inflects(word: string): boolean {
    for (let i = 0; i < word.length; i++) {
      const code = word.charCodeAt(i);
      // Arabic (0x0600 - 0x06FF) or Indic (0x0900 - 0x0DFF, Devanagari, Bengali, etc.)
      if ((code >= 0x0600 && code <= 0x06ff) || (code >= 0x0900 && code <= 0x0dff)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Checks if text actually contains words (at least 20% letters), not purely numbers or data dump
   */
  public static hasWords(text: string): boolean {
    let letters = 0;
    let others = 0;

    for (let i = 0; i < text.length; i++) {
      const ch = text[i];
      if (/\s/.test(ch)) continue;
      if (/\p{L}/u.test(ch)) {
        letters++;
      } else {
        others++;
      }
    }

    return letters > 0 && letters * 4 >= others;
  }

  /**
   * Extracts one line snippet around the first matched term, highlighted with « and »
   */
  public static snippet(text: string, terms: string[], maxWords = 12): string | null {
    if (!text || terms.length === 0) return null;

    const lower = text.toLowerCase();
    let firstIdx = -1;
    let matchedTerm = '';

    for (const term of terms) {
      const idx = lower.indexOf(term.toLowerCase());
      if (idx !== -1 && (firstIdx === -1 || idx < firstIdx)) {
        firstIdx = idx;
        matchedTerm = term;
      }
    }

    if (firstIdx === -1) return null;

    // Expand words before and after
    const startPos = Math.max(0, firstIdx - 60);
    const endPos = Math.min(text.length, firstIdx + matchedTerm.length + 80);

    let snippet = text.slice(startPos, endPos).trim();
    if (startPos > 0) snippet = '…' + snippet;
    if (endPos < text.length) snippet = snippet + '…';

    // Highlight terms
    for (const term of terms) {
      if (!term) continue;
      const regex = new RegExp(`(${this.escapeRegExp(term)})`, 'gi');
      snippet = snippet.replace(regex, '«$1»');
    }

    return snippet.replace(/\s+/g, ' ');
  }

  private static escapeRegExp(string: string): string {
    return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }
}
