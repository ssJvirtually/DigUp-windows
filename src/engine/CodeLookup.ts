// Code symbol and line definition resolver matching DigUpKit specifications

export class CodeLookup {
  private static readonly definers = new Set([
    'def', 'class', 'function', 'func', 'fn', 'struct', 'enum', 'interface', 'type',
    'val', 'var', 'let', 'const', 'pub', 'private', 'public', 'protected', 'static', 'async'
  ]);

  public static identifier(text: string): string[] | null {
    const trimmed = text.trim().replace(/\(\)$/, '');
    // C/Python/JS identifier or camelCase/snake_case
    if (/^[A-Za-z_][A-Za-z0-9_]*$/.test(trimmed) && (trimmed.includes('_') || /[A-Z]/.test(trimmed))) {
      return [trimmed];
    }
    return null;
  }

  public static phrase(identifier: string[]): string {
    return `"${identifier.join(' ')}"`;
  }

  public static definesSomething(word: string): boolean {
    return this.definers.has(word.toLowerCase());
  }

  public static definition(
    name: string,
    text: string,
    firstLine: number
  ): { line: number; strength: number } | null {
    const lines = text.split(/\r?\n/);
    const lowerName = name.toLowerCase();

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      const lowerLine = line.toLowerCase();
      if (!lowerLine.includes(lowerName)) continue;

      const words = line.trim().split(/\s+/);
      const isDef = words.some((w) => this.definesSomething(w));

      if (isDef) {
        return {
          line: firstLine + i,
          strength: 2 // Strong definition
        };
      }
    }

    return null;
  }

  public static firstLine(
    words: string[],
    text: string,
    firstLine: number
  ): number | null {
    const lines = text.split(/\r?\n/);

    for (let i = 0; i < lines.length; i++) {
      const lower = lines[i].toLowerCase();
      if (words.some((w) => lower.includes(w.toLowerCase()))) {
        return firstLine + i;
      }
    }

    return null;
  }
}
