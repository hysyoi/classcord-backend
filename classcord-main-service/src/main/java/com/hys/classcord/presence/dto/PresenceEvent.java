package com.hys.classcord.presence.dto;

import java.util.UUID;

/** 廣播至 /topic/presence 的在線狀態變化事件 */
public record PresenceEvent(UUID userId, boolean online) {}
