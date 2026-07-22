package com.hys.classcord.auth.entity;

import com.hys.classcord.auth.enums.AuthProvider;
import com.hys.classcord.core.entity.AuditableBaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "user_identities",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_provider_uid",
                    columnNames = {"provider", "provider_uid"})
        },
        indexes = {@Index(name = "idx_user_identities_user_id", columnList = "user_id")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class UserIdentity extends AuditableBaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_identities_user"))
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private AuthProvider provider;

    // 第三方唯一ID
    @Column(name = "provider_uid", length = 255)
    private String providerUid;

    // password_hash (本地登入才有值)
    @Setter
    @Column(name = "password_hash", length = 255)
    private String passwordHash;
}
