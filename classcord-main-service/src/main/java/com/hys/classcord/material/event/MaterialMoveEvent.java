package com.hys.classcord.material.event;

public record MaterialMoveEvent(
        String sourceKey, // 原始暫存 Key (temp/xxx.pdf)
        String targetKey // 目標正式 Key (materials/serverId/xxx.pdf)
        ) {}
