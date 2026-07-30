package com.zyh.archivemind.eval.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CitedChunk {
    private Integer chunkId;
    private String fileMd5;
    private String content;
    private Double score;
}
