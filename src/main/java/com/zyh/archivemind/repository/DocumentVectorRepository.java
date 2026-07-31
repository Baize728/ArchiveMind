package com.zyh.archivemind.repository;

import com.zyh.archivemind.model.DocumentVector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface DocumentVectorRepository extends JpaRepository<DocumentVector, Long> {
    List<DocumentVector> findByFileMd5(String fileMd5); // 查询某文件的所有分块

    /**
     * Small-to-Big 检索：按 fileMd5 + chunkId 批量查询父块文本。
     * 检索命中子块后，通过此方法回取父块完整上下文给 LLM。
     */
    @Query("SELECT dv FROM DocumentVector dv WHERE dv.fileMd5 IN :md5s AND dv.chunkId IN :chunkIds")
    List<DocumentVector> findByFileMd5InAndChunkIdIn(
            @Param("md5s") List<String> md5s,
            @Param("chunkIds") List<Integer> chunkIds);

    /**
     * 删除指定文件MD5的所有文档向量记录
     *
     * @param fileMd5 文件MD5
     */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM document_vectors WHERE file_md5 = ?1", nativeQuery = true)
    void deleteByFileMd5(String fileMd5);
}
