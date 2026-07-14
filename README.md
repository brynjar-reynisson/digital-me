# Digital Me

A personal search engine that indexes your local files and browsing history so you can find anything you've read or worked on.

## What it does

- **Indexes local files** — watches configured folders (e.g. `Documents`, Google Drive) for changes and indexes their text content using Apache Lucene.
- **Indexes web pages you visit** — a Chrome extension sends the text of every page you browse to the backend, where it is indexed automatically.
- **Semantic search** — meaning-based search powered by Ollama (`nomic-embed-text` embeddings), showing results ranked by similarity rather than exact keyword match.
- **Full-text search** — keyword search across all indexed content using Apache Lucene.
- **React UI** — displays both semantic and keyword results in separate sections with paginated links back to the original file or URL.

## Components

### Backend — Spring Boot + Apache Camel
- REST API served on `http://localhost:8080`
- Apache Camel routes (`routes/`) drive file watching and content ingestion
- Ollama integration for semantic search via `nomic-embed-text` (runs locally on port 11434)
- Lucene full-text index stored in `lucene-index/`
- SQLite database (`digital-me.db`) tracks indexed entries and vector embeddings

### Frontend — React (Vite)
- Source in `frontend/`
- Built output served as static files by Spring Boot from `src/main/resources/static/`
- Search box that queries both semantic and keyword endpoints in parallel
- Results displayed in two labelled sections (Semantic / Keyword), paginated at 10 per page

### Chrome Extension
- Source in `chrome-extension/`
- Sends the text content of every visited page to `POST /addContent` on the local backend

## Prerequisites

- Java 19+
- Maven 3.x
- Node.js 20.19+
- [Ollama](https://ollama.com) with the `nomic-embed-text` model (`ollama pull nomic-embed-text`) — required for semantic search. The app starts without it but semantic results will be empty.

## Running

**Build and start:**
```bash
mvn package
java -jar target/digital-me-0.1.jar
```

The app will be available at [http://localhost:8080](http://localhost:8080).

**Frontend development** (hot reload, proxied to the running backend):
```bash
cd frontend
npm install
npm run dev
```

## Chrome Extension

Load the `chrome-extension/` folder as an unpacked extension in Chrome or Edge (`chrome://extensions` → Enable developer mode → Load unpacked). The extension will start sending page content to the local backend automatically.

## YouTube caption extraction

The source is copied from https://github.com/trldvix/youtube-transcript-api
as it's not available on Maven Central. It is used to extract captions from YouTube videos when a URL is indexed.

## Project Structure

```
├── frontend/               React search UI (Vite)
├── chrome-extension/       Browser extension for page ingestion
├── routes/                 Apache Camel route definitions (XML)
├── src/
│   └── main/
│       ├── java/           Spring Boot + Camel backend
│       └── resources/
│           ├── static/     Built frontend assets (generated)
│           └── application.properties
└── pom.xml
```

This line was added in agent-suite popup