package com.hys.classcord.quiz.repository;

import com.hys.classcord.quiz.entity.QuizGenerationJob;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface QuizGenerationJobRepository extends JpaRepository<QuizGenerationJob, UUID> {}
