package com.hys.classcord.auth.dto;

import com.hys.classcord.auth.entity.User;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
public record UserProfileResponse(
        UUID id, String username, String email, String avatarUrl, Instant createdAt) {

    // ** <改為在前端處理> **
    // // 精簡建構子：如果 avatarUrl 為 null，給予預設值
    // public UserProfileResponse {
    //     if (avatarUrl == null) {
    //         avatarUrl = "https://classcord.hys-lab.com/default-avatar.png";
    //     }
    // }

    // 靜態工廠方法：將 User Entity 轉換成這個 DTO
    public static UserProfileResponse fromEntity(User user) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .avatarUrl(user.getAvatarUrl())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
