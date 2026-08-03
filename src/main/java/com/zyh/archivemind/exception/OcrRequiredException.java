package com.zyh.archivemind.exception;

/**
 * 扫描件/图片 PDF 拦截异常。
 * 当 Tika 提取文本的双重检测（文本总长度 + 每页密度）任一未通过时抛出。
 * 调用方可据此拒收文件或转投异步 OCR 队列。
 */
public class OcrRequiredException extends RuntimeException {

    private final int extractedTextLength;
    private final int pageCount;
    private final double textPerPage;

    public OcrRequiredException(String message, int extractedTextLength, int pageCount, double textPerPage) {
        super(message);
        this.extractedTextLength = extractedTextLength;
        this.pageCount = pageCount;
        this.textPerPage = textPerPage;
    }

    public int getExtractedTextLength() {
        return extractedTextLength;
    }

    public int getPageCount() {
        return pageCount;
    }

    public double getTextPerPage() {
        return textPerPage;
    }
}
