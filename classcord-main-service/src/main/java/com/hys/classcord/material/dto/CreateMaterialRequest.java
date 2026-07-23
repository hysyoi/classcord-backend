package com.hys.classcord.material.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateMaterialRequest(
        @Size(max = 2000, message = "訊息內容不能超過 2000 個字元") String content, // 選填文字
        @NotBlank(message = "File Key 不能為空")
                String fileKey, // 改為只傳 fileKey，例如 materials/class_123/uuid_講義.pdf
        @NotBlank(message = "檔案類型不能為空") @Size(max = 20, message = "檔案類型不能超過 20 個字元")
                String fileType,
        @NotBlank(message = "原始檔名不能為空") @Size(max = 255, message = "原始檔名不能超過 255 個字元")
                String originalName,
        @NotNull(message = "檔案大小不能為空")
                @Min(value = 1, message = "檔案大小必須大於 0")
                @Max(value = 104857600, message = "單一檔案大小不能超過 100MB")
                Long fileSize // 檔案大小，加入 Max/Min 限制
        ) {}
