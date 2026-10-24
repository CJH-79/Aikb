package cn.gok.kb.parser;

import cn.gok.kb.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * 按扩展名挑选解析器。新增格式只需再写一个 DocumentParser 实现并注册为 Bean。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParserFactory {

    private final List<DocumentParser> parsers;

    public List<ParsedPage> parse(String ext, InputStream in) {
        DocumentParser parser = parsers.stream()
                .filter(p -> p.supports(ext))
                .findFirst()
                .orElseThrow(() -> new BizException(415, "不支持的格式：" + ext + "（仅支持 pdf / md / txt）"));
        try {
            List<ParsedPage> pages = parser.parse(in);
            if (pages.isEmpty()) {
                throw new BizException(422, "文档没有可提取的文字（可能是扫描版 PDF，需要 OCR）");
            }
            return pages;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("文档解析失败, ext={}", ext, e);
            throw new BizException(422, "文档解析失败：" + e.getMessage());
        }
    }
}
