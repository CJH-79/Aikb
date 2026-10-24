package cn.gok.kb.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 解析：逐页抽取文本，页号直接来自 PDF 结构，溯源最准确。
 */
@Slf4j
@Component
public class PdfParser implements DocumentParser {

    @Override
    public boolean supports(String ext) {
        return "pdf".equalsIgnoreCase(ext);
    }

    @Override
    public List<ParsedPage> parse(InputStream in) throws Exception {
        List<ParsedPage> pages = new ArrayList<>();
        // Loader.load 需要 RandomAccessRead，PDFBox 内部会做整份加载；30MB 上限内可接受
        try (PDDocument doc = Loader.loadPDF(in.readAllBytes())) {
            int total = doc.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int i = 1; i <= total; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(doc);
                if (text != null && !text.isBlank()) {
                    pages.add(new ParsedPage(i, text));
                }
            }
            log.info("PDF 解析完成，共 {} 页，有文字 {} 页", total, pages.size());
        }
        return pages;
    }
}
