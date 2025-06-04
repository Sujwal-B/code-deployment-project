# Command & File Service (Spring Boot 3.3)

This project provides a simple Spring Boot application that exposes RESTful endpoints for:
- Executing server-side shell commands within a sandboxed environment.
- Downloading remote files to a designated directory on the server.
- Tailing log files.

It also includes a self-contained HTML/JavaScript page for live log viewing and basic interactions.

## Project Meta

| Item                 | Value                                                         |
|----------------------|---------------------------------------------------------------|
| **Group / Artifact** | `de.zeroco:code-deployment-project`                           |
| **Java version**     | **21**                                                        |
| **Build tool**       | Maven                                                         |
| **Spring Boot**      | **3.3.0**                                                     |
| **Packaging**        | **jar**                                                       |
| **Base package**     | `de.zeroco.core`                                              |

## Build & Run Steps

### Prerequisites
- Java 21 JDK
- Apache Maven 3.6+

### Build
To build the project and create an executable JAR:
```bash
mvn clean package
```
This will generate `target/code-deployment-project-0.0.1-SNAPSHOT.jar`.

### Run
There are two main ways to run the application:

1.  **Using Maven Spring Boot Plugin (for development):**
    ```bash
    mvn spring-boot:run
    ```
    The application will start, typically on port `8080`.

2.  **Running the executable JAR:**
    ```bash
    java -jar target/code-deployment-project-0.0.1-SNAPSHOT.jar
    ```

The service will create the following directories in its working path if they don't exist:
- `sandbox/`: For command execution.
- `downloads/`: For storing downloaded files.
- `logs_data/`: For custom log files accessible via the logs API.

## REST API

The following endpoints are available under the `/api/system` base path:

| Method & Path                  | Description                                      | Request Body / Params                                                                    | Response                               | Content-Type          |
|--------------------------------|--------------------------------------------------|------------------------------------------------------------------------------------------|----------------------------------------|-----------------------|
| **POST** `/api/system/execute` | Execute a server-side shell command.             | JSON: `{ "command": "<bash_cmd>" }`                                                      | `200 OK` with merged **stdout+stderr** | `text/plain`          |
| **GET** `/api/system/download` | Download a remote file to a server location.     | Query Params: `url=<remote_file_url>&destination=<server_relative_path>`                  | `200 OK` with success/error message    | `text/plain` (message) |
| **GET** `/api/system/logs`     | Tail a log file from the server.                 | Query Params: `lines=<int (default 500)>&file=<path (default application.log)>`           | `200 OK` with last *n* lines           | `text/plain`          |

### `curl` Samples

#### Execute Command
Execute a command in the `sandbox` directory. For example, to list files in the sandbox:
```bash
# First, ensure 'sandbox' directory exists where the app is running.
# You might want to place a test script there, e.g., sandbox/my_script.sh
# echo "echo 'Hello from script'" > sandbox/my_script.sh

curl -X POST -H "Content-Type: application/json"      -d '{ "command": "ls -la" }'      http://localhost:8080/api/system/execute
```
Example with a script (assuming `sandbox/test.sh` contains `echo "Test script output"`):
```bash
curl -X POST -H "Content-Type: application/json"      -d '{ "command": "bash test.sh" }'      http://localhost:8080/api/system/execute
# Expected output: Test script output
```

#### Download File
Download a remote file to the server's `downloads` directory.
```bash
# This will download the Spring Boot README to cmd-file-service/downloads/spring_readme.md
curl -X GET      "http://localhost:8080/api/system/download?url=https://raw.githubusercontent.com/spring-projects/spring-boot/main/README.md&destination=spring_readme.md"
# Response: File downloaded successfully to: /path/to/cmd-file-service/downloads/spring_readme.md

# To download to a subdirectory within 'downloads':
curl -X GET      "http://localhost:8080/api/system/download?url=https://raw.githubusercontent.com/spring-projects/spring-boot/main/README.md&destination=docs/spring_readme.md"
# Response: File downloaded successfully to: /path/to/cmd-file-service/downloads/docs/spring_readme.md
```

#### Get Logs
Tail the application log:
```bash
# Get last 10 lines of application.log
curl -X GET "http://localhost:8080/api/system/logs?lines=10"
```
Tail a custom log file from the `logs_data` directory (e.g., `logs_data/my_app.log`):
```bash
# First, create a sample log: echo "This is a custom log line" > logs_data/my_app.log
curl -X GET "http://localhost:8080/api/system/logs?file=my_app.log&lines=5"
```

## Hardening & Security Notes

*   **Command Execution**:
    *   Commands sent to `/api/system/execute` are executed within a dedicated `sandbox/` subdirectory relative to the application's working directory.
    *   The executed command string undergoes basic validation to prevent trivial command injection patterns (e.g., containing `..`, `;`, `&&`, `||`, `|`, `` ` ``). More robust validation or an allow-list approach is recommended for production.
    *   For the "Deploy" button in `logs.html` to work with its default `./deploy.sh` command, you must create a `deploy.sh` script inside the `sandbox/` directory.
*   **File Downloads**:
    *   Files downloaded via `/api/system/download` are saved to a dedicated `downloads/` subdirectory.
    *   The `destination` path is normalized, and attempts to write files outside this directory (path traversal) are rejected.
    *   Parent directories for the destination path are created if they don't exist within `downloads/`.
*   **Log File Access**:
    *   The `/api/system/logs` endpoint can access:
        *   `application.log` (searched in project root, then `logs/application.log`, then `logs_data/application.log`).
        *   Any file specified by the `file` parameter, provided it resides directly within the `logs_data/` subdirectory.
    *   Path traversal attempts for the `file` parameter are rejected.
*   **Security Annotations**:
    *   Endpoint methods in `SystemController.java` are annotated with `@SecuredOperation`. This is a marker annotation that can be used by Spring Security (e.g., with `@PreAuthorize`) or custom security filters to apply access control. By default, the endpoints are open.

## Runtime Environment & Reverse Proxy

*   **User Privileges**: For security, always run the application under a **non-root user**.
*   **Firewall (ufw)**: On Linux, use `ufw` to manage open ports. If the application runs on port 8080, you might allow access:
    ```bash
    sudo ufw allow 8080/tcp
    sudo ufw enable
    ```
*   **Reverse Proxy (Nginx/Apache)**: For production, it's highly recommended to run the Spring Boot application behind a reverse proxy like Nginx or Apache. This allows for:
    *   **HTTPS Termination**: The reverse proxy handles SSL/TLS encryption.
    *   **Port Forwarding**: Expose the application on standard ports (80/443) while it runs internally on a higher port (e.g., 8080).
    *   **Load Balancing** (if multiple instances).
    *   **Serving Static Content**: The proxy can serve static files like `logs.html` more efficiently.
    *   **Additional Security Headers**.

    Example Nginx snippet (basic HTTP):
    ```nginx
    server {
        listen 80;
        server_name your.domain.com;

        location / {
            proxy_pass http://localhost:8080; # Assumes app runs on localhost:8080
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
        }
    }
    ```

## OpenAPI & Swagger UI

*   **OpenAPI 3 JSON**: The API documentation in OpenAPI 3 format is automatically generated and available at:
    `GET /v3/api-docs`
*   **Swagger UI**: A user-friendly interface to explore and test the API is available at:
    `GET /swagger-ui.html`

## Live Log Viewer (`logs.html`)

*   **Purpose**: A self-contained HTML page for real-time log monitoring of `application.log` (by default) and quick actions.
*   **Access**: Open `http://<host>:8080/logs.html` in a web browser after starting the application.
*   **Features**:
    *   Live polling of logs.
    *   Auto-scroll.
    *   "Download Logs" button.
    *   "Deploy" button (executes `sandbox/deploy.sh` via the API).
    *   Toast notifications for actions.
    *   Copy-to-clipboard for log lines.
    *   Responsive design.