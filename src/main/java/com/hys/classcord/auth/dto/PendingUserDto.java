package com.hys.classcord.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// 為了jackson，所以不用record
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingUserDto {
    private String username;
    private String email;
    private String passwordHash; // 在放入 Redis 前就先加密好，確保資安
}
