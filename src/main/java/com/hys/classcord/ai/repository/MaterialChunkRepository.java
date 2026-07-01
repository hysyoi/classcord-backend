package com.hys.classcord.ai.repository;

import com.hys.classcord.ai.entity.MaterialChunk;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MaterialChunkRepository extends JpaRepository<MaterialChunk, UUID> {

    @Query(
            value =
                    "SELECT * FROM material_chunks WHERE metadata ->> 'material_id' = :materialId "
                            + "ORDER BY (metadata ->> 'chunk_index')::int",
            nativeQuery = true)
    List<MaterialChunk> findByMaterialId(@Param("materialId") String materialId);
}
