package com.hys.classcord.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateServerRequest(
        @NotBlank(message = "伺服器名稱不可為空") @Size(max = 100, message = "伺服器名稱長度不可超過 100 字元")
                String name) {}
