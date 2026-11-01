package com.hys.classcord.server.enums;

public enum ServerRole {
    TEACHER,
    STUDENT,
    TA;

    /** 老師與助教可以管理班級內容（頻道、教材、題庫、他人訊息等） */
    public boolean canManage() {
        return this == TEACHER || this == TA;
    }
}
