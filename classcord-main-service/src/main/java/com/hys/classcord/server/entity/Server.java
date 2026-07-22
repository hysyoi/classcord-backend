package com.hys.classcord.server.entity;

import com.hys.classcord.auth.entity.User;
import com.hys.classcord.core.entity.AuditableBaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "servers",
        indexes = {@Index(name = "idx_servers_owner_id", columnList = "owner_id")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Server extends AuditableBaseEntity {

    @Setter
    @Column(nullable = false, length = 100)
    private String name;

    @Setter
    @Column(name = "used_storage", nullable = false)
    @Builder.Default
    private long usedStorage = 0L;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "owner_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_servers_owner"))
    private User owner;
}
