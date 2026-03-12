package com.zyh.archivemind.repository;



import com.zyh.archivemind.model.ChunkInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChunkInfoRepository extends JpaRepository<ChunkInfo, Long> {
    /**
     * 根据fileMd5查询所有分片的信息
     * @param fileMd5 大文件的Md5信息
     * @return 大文件的所有分片信息
     */
    List<ChunkInfo> findByFileMd5OrderByChunkIndexAsc(String fileMd5);
}
