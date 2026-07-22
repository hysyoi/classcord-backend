-- ClassCord 生產環境 PostgreSQL 初始化腳本
-- 僅為正式資料庫開啟 pgvector 向量擴充套件
CREATE EXTENSION IF NOT EXISTS vector;
