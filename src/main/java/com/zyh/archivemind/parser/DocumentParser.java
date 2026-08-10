package com.zyh.archivemind.parser;

import org.apache.commons.codec.digest.DigestUtils;

public interface DocumentParser {

    boolean supports(ParseRequest request);

    ParseResult parse(ParseRequest request);

    String parserType();

    String parserVersion();

    default String parserConfigHash(ParseRequest request) {
        return DigestUtils.sha256Hex(parserType() + ":" + parserVersion());
    }
}
