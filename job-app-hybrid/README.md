# Automated Job Application — HYBRID Monorepo

Chrome Extension (Manifest V3) + Spring Boot REST API with PostgreSQL.

## Structure

```
job-app-hybrid/
├── frontend/          # Chrome extension (MV3 popup + content script)
└── backend/           # Spring Boot API + docker-compose for PostgreSQL
```

## Quick start

### 1. Database

```bash
cd backend
docker compose up -d
```

### 2. Backend API

```bash
cd backend
mvnw.cmd spring-boot:run
```

API listens on `http://localhost:8080`.

| Method | Path        | Description                          |
|--------|-------------|--------------------------------------|
| POST   | `/api/files` | Multipart upload (`file` field)     |

### 3. Chrome extension

1. Open `chrome://extensions`
2. Enable **Developer mode**
3. **Load unpacked** → select the `frontend/` folder
4. Open the extension popup to manage your profile and upload a resume

## Features

- **Profile Management** — name, email, and phone persisted in `chrome.storage.local`
- **Resume Upload** — `fetch()` POST to `/api/files` with `FormData` field `file`
- **Auto-Fill** — content script injects an Auto-Fill button on job application forms
- **Modern UI** — Tailwind CSS via CDN, inline status toasts (no `alert()` / `prompt()`)
