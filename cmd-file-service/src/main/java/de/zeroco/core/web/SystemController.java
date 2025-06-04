package de.zeroco.core.web;

import de.zeroco.core.security.SecuredOperation;
// ... other imports from previous steps ...
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@RestController
@RequestMapping("/api/system")
public class SystemController {

    private static final String SANDBOX_DIRECTORY = "sandbox";
    private static final String DOWNLOADS_DIRECTORY = "downloads";
    private static final String LOGS_DATA_DIRECTORY = "logs_data"; // Base directory for accessible logs
    private static final String DEFAULT_LOG_FILE = "application.log"; // Default log file name

    public SystemController() {
        // Create sandbox, downloads, and logs_data directories
        for (String dir : new String[]{SANDBOX_DIRECTORY, DOWNLOADS_DIRECTORY, LOGS_DATA_DIRECTORY}) {
            Path dirPath = Paths.get(dir);
            if (!dirPath.toFile().exists()) {
                try {
                    java.nio.file.Files.createDirectories(dirPath);
                    System.out.println(dir + " directory created at: " + dirPath.toAbsolutePath());
                } catch (IOException e) {
                    System.err.println("Failed to create " + dir + " directory: " + e.getMessage());
                }
            } else {
                 System.out.println(dir + " directory already exists at: " + dirPath.toAbsolutePath());
            }
        }
    }

    // ExecuteRequest class (from previous step)
    public static class ExecuteRequest {
        private String command;
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
    }

    // executeCommand method (from previous step)
    @SecuredOperation
    @PostMapping(value = "/execute", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> executeCommand(@RequestBody ExecuteRequest request) {
        // ... (implementation from previous step)
        if (request == null || request.getCommand() == null || request.getCommand().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Command cannot be empty.");
        }
        String command = request.getCommand();
        if (command.contains("..") || command.contains(";") || command.contains("&&") || command.contains("||") || command.contains("|") || command.contains("`")) {
            return ResponseEntity.badRequest().body("Invalid characters in command.");
        }

        StringBuilder output = new StringBuilder();
        try {
            Path projectRoot = Paths.get(".").toAbsolutePath().normalize();
            Path sandboxPath = projectRoot.resolve(SANDBOX_DIRECTORY).normalize();
            if (!sandboxPath.toFile().exists() && !sandboxPath.toFile().mkdirs()) {
                 return ResponseEntity.internalServerError().body("Could not create or access sandbox directory.");
            }

            ProcessBuilder processBuilder = new ProcessBuilder();
            processBuilder.command("/bin/bash", "-c", command);
            processBuilder.directory(sandboxPath.toFile());
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return ResponseEntity.status(504).body("Command timed out.");
            }
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return ResponseEntity.ok(output.toString());
            } else {
                return ResponseEntity.status(500).body("Command failed with exit code " + exitCode + System.lineSeparator() + output.toString());
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(500).body("Error executing command: " + e.getMessage());
        }
    }

    // downloadFile method (from previous step)
    @SecuredOperation
    @GetMapping("/download")
    public ResponseEntity<?> downloadFile(@RequestParam String url, @RequestParam String destination) {
        // ... (implementation from previous step)
        try {
            new URL(url).toURI(); // Validate URL syntax
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid URL format: " + e.getMessage());
        }

        Path baseDownloadsPath = Paths.get(DOWNLOADS_DIRECTORY).toAbsolutePath().normalize();
        Path destinationPath = baseDownloadsPath.resolve(destination).normalize();

        if (!destinationPath.startsWith(baseDownloadsPath)) {
            return ResponseEntity.badRequest().body("Invalid destination path. Path traversal attempt detected.");
        }

        try {
            Path parentDir = destinationPath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }
            try (InputStream in = URI.create(url).toURL().openStream()) {
                Files.copy(in, destinationPath, StandardCopyOption.REPLACE_EXISTING);
            }
            return ResponseEntity.ok("File downloaded successfully to: " + destinationPath.toString());
        } catch (IOException e) {
            return ResponseEntity.status(500).body("Error downloading file: " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(500).body("An unexpected error occurred: " + e.getMessage());
        }
    }

    @SecuredOperation
    @GetMapping(value = "/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getLogs(
            @RequestParam(defaultValue = "500") int lines,
            @RequestParam(defaultValue = DEFAULT_LOG_FILE) String file) {

        if (lines <= 0) {
            lines = 500; // Default to 500 if invalid value is provided
        }

        Path logFilePath;
        Path projectRoot = Paths.get(".").toAbsolutePath().normalize();

        // Determine the path of the log file
        if (DEFAULT_LOG_FILE.equals(file)) {
            // Special handling for "application.log" - look in project root or standard log locations
            // For simplicity, we'll check project root first.
            // A more robust solution might check common Spring Boot logging paths or use a configured path.
            logFilePath = projectRoot.resolve(DEFAULT_LOG_FILE).normalize();
            if (!Files.exists(logFilePath)) {
                // If not in root, check a 'logs' subdirectory as Spring Boot might put it there.
                logFilePath = projectRoot.resolve("logs").resolve(DEFAULT_LOG_FILE).normalize();
                 if (!Files.exists(logFilePath)) {
                    // Fallback to logs_data if application.log is not found in typical spots
                    logFilePath = projectRoot.resolve(LOGS_DATA_DIRECTORY).resolve(DEFAULT_LOG_FILE).normalize();
                 }
            }
        } else {
            // For other files, restrict to LOGS_DATA_DIRECTORY
            Path baseLogsPath = projectRoot.resolve(LOGS_DATA_DIRECTORY).normalize();
            logFilePath = baseLogsPath.resolve(file).normalize();

            // Security check: Ensure the path is within LOGS_DATA_DIRECTORY
            if (!logFilePath.startsWith(baseLogsPath)) {
                return ResponseEntity.badRequest().body("Access to the specified log file is restricted.");
            }
        }

        if (!Files.exists(logFilePath) || !Files.isReadable(logFilePath)) {
            return ResponseEntity.status(404).body("Log file not found or not readable: " + logFilePath.toString());
        }

        try {
            List<String> allLines;
            try (Stream<String> stream = Files.lines(logFilePath, StandardCharsets.UTF_8)) {
                allLines = stream.collect(Collectors.toList());
            }

            int totalLines = allLines.size();
            int linesToSkip = Math.max(0, totalLines - lines);

            String result = allLines.stream().skip(linesToSkip).collect(Collectors.joining(System.lineSeparator()));

            return ResponseEntity.ok(result);

        } catch (IOException e) {
            return ResponseEntity.status(500).body("Error reading log file: " + e.getMessage());
        }
    }
}
