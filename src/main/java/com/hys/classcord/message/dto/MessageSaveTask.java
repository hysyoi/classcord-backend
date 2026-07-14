package com.hys.classcord.message.dto;

import java.time.Instant;
import java.util.UUID;

public record MessageSaveTask(
        UUID messageId, UUID userId, UUID channelId, String content, Instant createdAt) {}
