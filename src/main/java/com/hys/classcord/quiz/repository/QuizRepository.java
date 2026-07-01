package com.hys.classcord.quiz.repository;

import com.hys.classcord.quiz.entity.Quiz;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface QuizRepository extends JpaRepository<Quiz, UUID> {

    List<Quiz> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
