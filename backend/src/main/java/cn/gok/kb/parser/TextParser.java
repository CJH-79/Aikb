package cn.gok.kb.parser;

import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 纯文本 / Markdown 解析：整份作为一个逻辑页，页号固定为 1。
 */
@Component
public class TextParser implements DocumentParser {

    @Override
    public boolean supports(String ext) {
        return "md".equalsIgnoreCase(ext)
                || "markdown".equalsIgnoreCase(ext)
                || "txt".equalsIgnoreCase(ext);
    }

    @Override
    public List<ParsedPage> parse(InputStream in) throws Exception {
        String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        // 去掉 UTF-8 BOM，否则第一个关键词永远匹配不上
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        return List.of(new ParsedPage(1, text));
    }
}
