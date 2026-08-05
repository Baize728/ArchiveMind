package com.zyh.archivemind.parser;

import java.nio.file.Path;
import java.util.Locale;

public record ParseRequest(
        String fileMd5,
        String fileName,
        String contentType,
        Path localFile,
        String language,
        boolean forceReparse
) {
    public String normalizedFileName() {
        return (fileName == null || fileName.isBlank()) ? fileMd5 : fileName.trim();
    }

    public String extension() {
        String name = normalizedFileName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
