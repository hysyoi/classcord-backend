package com.hys.classcord.channel.entity;

import com.hys.classcord.channel.enums.ChannelType;
import com.hys.classcord.core.entity.BaseEntity;
import com.hys.classcord.server.entity.Server;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "channels",
        indexes = {@Index(name = "idx_channels_server_id", columnList = "server_id")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Channel extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "server_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_channels_server"))
    private Server server;

    @Setter // 僅開放修改名稱
    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChannelType type;

    @Setter // 僅開放修改排序
    @Column(nullable = false)
    @Builder.Default
    private Integer position = 0;
}
