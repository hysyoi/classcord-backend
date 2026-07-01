package com.hys.classcord.quiz.repository;

import com.hys.classcord.quiz.entity.MaterialQuestion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MaterialQuestionRepository extends JpaRepository<MaterialQuestion, UUID> {

    List<MaterialQuestion> findByMaterialIdAndIsDeletedFalse(UUID materialId);

    long countByMaterialIdAndIsDeletedFalse(UUID materialId);

    Optional<MaterialQuestion> findByIdAndIsDeletedFalse(UUID id);
}
