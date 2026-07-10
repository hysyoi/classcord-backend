package com.hys.classcord.message.entity;

import com.hys.classcord.auth.entity.User;
import com.hys.classcord.channel.entity.Channel;
import com.hys.classcord.core.entity.AuditableBaseEntity;
import com.hys.classcord.material.entity.Material;
import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(
        name = "messages",
        indexes = {
            @Index(
                    name = "idx_messages_channel_created_at",
                    columnList = "channel_id, created_at DESC"),
            @Index(name = "idx_messages_user_id", columnList = "user_id")
        })
@SQLDelete(sql = "UPDATE messages SET deleted = true WHERE id = ?")
@SQLRestriction("deleted = false")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Message extends AuditableBaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "channel_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_messages_channel"))
    private Channel channel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_messages_user"))
    private User user;

    @Setter // 僅開放修改訊息內容
    @Column(columnDefinition = "TEXT")
    private String content;

    @Builder.Default
    @Column(nullable = false)
    private boolean deleted = false;

    @Builder.Default
    @BatchSize(size = 25)
    @OneToMany(
            mappedBy = "message",
            fetch = FetchType.LAZY,
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    private List<Material> materials = new ArrayList<>();
}
