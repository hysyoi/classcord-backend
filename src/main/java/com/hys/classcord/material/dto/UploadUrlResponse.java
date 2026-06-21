package com.hys.classcord.material.dto;

public record UploadUrlResponse(
        String uploadUrl, // 給前端直傳檔案用的預簽名 URL
        String fileKey // 檔案在 B2 中的唯一鍵
        ) {}
