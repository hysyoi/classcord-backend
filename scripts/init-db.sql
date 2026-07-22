-- ClassCord 本地開發與 CI 測試環境 PostgreSQL 初始化腳本
CREATE EXTENSION IF NOT EXISTS vector;

-- 測試隔離資料庫 (供 mvn test / CI 整合測試使用)
CREATE DATABASE classcord_test;
\c classcord_test;
CREATE EXTENSION IF NOT EXISTS vector;
