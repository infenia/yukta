# API Reference

Jagratha provides a comprehensive REST API for managing sessions, logging file changes, and executing build tasks.

## Swagger UI

When the application is running, you can access the interactive Swagger UI at:

[http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)

This interface allows you to:
- Explore all available endpoints.
- View detailed request/response schemas.
- Execute API calls directly from your browser.

## OpenAPI Specification

The raw OpenAPI 3.0 specification is available in JSON format at:

[http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)

## Key Endpoints

### 1. Session Configuration (`POST /api/config`)
Initializes or updates the configuration for a specific session.

### 2. File Logging (`POST /api/files`)
Logs a file path that has been modified. Jagratha will track these files and reset their status to `PENDING`.

### 3. Task Execution (`POST /api/tasks/complete`)
Triggers the execution of build tasks (e.g., tests, checkstyle) for the current session. Returns a summary of success or failure.

### 4. Log Retrieval (`GET /api/logs/{sessionId}`)
Lists all log files generated for a specific session.

### 5. Log Content (`GET /api/logs/{sessionId}/{filename}`)
Retrieves the raw content of a specific log file.

## Error Handling

Jagratha uses structured error responses for all API errors.

```json
{
  "timestamp": "2026-01-01T10:00:00",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/config",
  "errors": [
    {
      "field": "sessionId",
      "message": "Session ID is required"
    }
  ]
}
```
