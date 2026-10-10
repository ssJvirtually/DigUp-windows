import fs from 'fs';
import pdfParse from 'pdf-parse';

export interface PDFPageResult {
  number: number; // 1-based
  text: string;
  imagePath?: string | null;
  ocr?: boolean;
  garbled?: string | null;
}

export interface ExtractedPDF {
  pages: PDFPageResult[];
  total: number;
  title?: string | null;
}

export class PDFExtractor {
  public static async pages(
    filePath: string,
    pageRange: (total: number) => { start: number; end: number },
    minText = 200
  ): Promise<ExtractedPDF> {
    const dataBuffer = fs.readFileSync(filePath);

    const pagesData: string[] = [];
    let pageCount = 0;

    const renderPage = (pageData: any) => {
      return pageData.getTextContent().then((textContent: any) => {
        let lastY: number | null = null;
        let text = '';
        for (const item of textContent.items) {
          if (lastY === item.transform[5] || !lastY) {
            text += item.str;
          } else {
            text += '\n' + item.str;
          }
          lastY = item.transform[5];
        }
        pagesData.push(text);
        return text;
      });
    };

    const parseFn: any = (pdfParse as any).default || pdfParse;
    const parsed = await parseFn(dataBuffer, {
      pagerender: renderPage
    });

    pageCount = parsed.numpages || pagesData.length;
    const { start, end } = pageRange(pageCount);

    const results: PDFPageResult[] = [];
    const clampStart = Math.max(0, Math.min(pageCount, start));
    const clampEnd = Math.max(clampStart, Math.min(pageCount, end));

    for (let i = clampStart; i < clampEnd; i++) {
      const pageNum = i + 1;
      const text = (pagesData[i] || '').trim();

      results.push({
        number: pageNum,
        text: text.length >= minText ? text : text,
        imagePath: null
      });
    }

    return {
      pages: results,
      total: pageCount,
      title: parsed.info?.Title || null
    };
  }
}
