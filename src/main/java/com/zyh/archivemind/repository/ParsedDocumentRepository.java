package com.zyh.archivemind.repository;

import com.zyh.archivemind.model.ParsedDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ParsedDocumentRepository extends JpaRepository<ParsedDocument, Long> {

    Optional<ParsedDocument> findFirstByFileMd5AndParserTypeAndParserVersionAndParserConfigHashOrderByUpdatedAtDesc(
            String fileMd5, String parserType, String parserVersion, String parserConfigHash);

    Optional<ParsedDocument> findFirstByFileMd5AndParserTypeAndParserVersionAndParserConfigHashAndParseStatusOrderByUpdatedAtDesc(
            String fileMd5, String parserType, String parserVersion, String parserConfigHash, String parseStatus);

    long countByFileMd5AndParseStatus(String fileMd5, String parseStatus);
}
