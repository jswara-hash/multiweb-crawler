-- ==========================================================
-- WEB CRAWLER DATABASE SCHEMA FOR MYSQL / XAMPP PHPMYADMIN
-- ==========================================================
-- You can either:
--   1. Run this file directly in phpMyAdmin (SQL tab or Import tab)
--   2. OR simply run the Java application! It automatically creates
--      the database 'crawler_db' and all required tables for you.
-- ==========================================================

CREATE DATABASE IF NOT EXISTS crawler_db
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE crawler_db;

-- ----------------------------------------------------------
-- 1. Table: pages
-- Stores all crawled or simulated web pages, titles, and excerpts
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS pages (
    page_id VARCHAR(255) NOT NULL,
    title VARCHAR(500),
    excerpt TEXT,
    url TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (page_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------------------------------------
-- 2. Table: links
-- Stores directed hyperlinks (graph edges) between pages
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS links (
    id INT AUTO_INCREMENT PRIMARY KEY,
    from_page VARCHAR(255) NOT NULL,
    to_page VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_from_page (from_page),
    INDEX idx_to_page (to_page),
    UNIQUE KEY uq_link (from_page(191), to_page(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------------------------------------
-- 3. Table: crawl_runs
-- Summarizes each crawl session (single or multi-threaded)
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS crawl_runs (
    run_id INT AUTO_INCREMENT PRIMARY KEY,
    mode VARCHAR(50),
    thread_count INT,
    total_time_ms INT,
    pages_visited INT,
    run_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ----------------------------------------------------------
-- 4. Table: crawl_log
-- Audit log of each page visit event during a crawl run
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS crawl_log (
    id INT AUTO_INCREMENT PRIMARY KEY,
    run_id INT NOT NULL,
    page_id VARCHAR(255) NOT NULL,
    thread_name VARCHAR(100),
    depth INT,
    elapsed_ms BIGINT,
    status_code INT,
    discovered_links MEDIUMTEXT,
    page_url TEXT,
    visited_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_run_id (run_id),
    INDEX idx_page_id (page_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
