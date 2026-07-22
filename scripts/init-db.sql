CREATE EXTENSION IF NOT EXISTS vector;

-- 測試隔離資料庫
CREATE DATABASE classcord_test;
\c classcord_test;
CREATE EXTENSION IF NOT EXISTS vector;

-- AI 專屬向量邏輯資料庫
CREATE DATABASE classcord_vector_db;
\c classcord_vector_db;
CREATE EXTENSION IF NOT EXISTS vector;
