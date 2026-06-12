package com.hys.classcord.auth.entity;

import com.hys.classcord.core.entity.AuditableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class User extends AuditableBaseEntity {

    @Column(nullable = false, length = 50)
    private String username;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;
}
