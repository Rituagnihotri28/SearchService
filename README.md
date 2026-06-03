# SearchService

Spring Boot prototype for tenant-aware document search with PostgreSQL persistence, Redis caching, rate limiting, and health checks.

## What it uses
- PostgreSQL for document persistence
- Redis for cached search results
- Spring Web for the REST API
- Basic per-tenant rate limiting and health monitoring

## Run locally
1. Start dependencies:

   ```sh
   docker compose up -d
   ```

2. Start the application:

   ```sh
   mvn spring-boot:run
   ```

The application expects PostgreSQL on `localhost:5432` and Redis on `localhost:6379`.

## API endpoints
- `POST /documents`
- `GET /search?q=...&tenant=...`
- `GET /documents/{id}`
- `DELETE /documents/{id}`
- `GET /health`
