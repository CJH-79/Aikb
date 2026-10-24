package cn.gok.kb.parser;

import java.io.InputStream;
import java.util.List;

/**
 * 文档解析器。按页/段返回文本，页码是后续引用溯源的唯一依据。
 */
public interface DocumentParser {

    /** 支持的文件扩展名，小写，不含点 */
    boolean supports(String ext);

    /**
     * @param in 文件流（由调用方负责关闭）
     * @return 按页组织的纯文本，至少 1 页
     */
    List<ParsedPage> parse(InputStream in) throws Exception;
}
