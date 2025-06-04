package de.zeroco.core.web.operation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/system")
@Tag(name = "Logs Operations", description = "Endpoints for retrieving application logs")
public class LogsController {

    private final Path logsBasePath;
    private final String defaultLogFile;
    private final Path projectRoot; // To resolve application.log if not found in logsBasePath

    public LogsController(
            @Value("${app.logs.base-path:logs_data}") String logsBasePathValue,
            @Value("${app.logs.default-file:application.log}") String defaultLogFileValue) {
        this.logsBasePath = Paths.get(logsBasePathValue).toAbsolutePath().normalize();
        this.defaultLogFile = defaultLogFileValue;
        this.projectRoot = Paths.get(".").toAbsolutePath().normalize();
        System.out.println("LogsController: Log base path configured to: " + this.logsBasePath);
        System.out.println("LogsController: Default log file configured to: " + this.defaultLogFile);
        // Note: This controller does NOT create the logsBasePath directory.
        // It assumes the directory pointed to by app.logs.base-path exists.
    }

    @GetMapping(value = "/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "Retrieve log file content",
               description = "Retrieves a specified number of trailing lines from a log file. " +
                             "The 'file' parameter can specify a filename within the configured log base path, " +
                             "or if it matches the default log filename (e.g., 'application.log'), special lookup paths are checked.",
               parameters = {
                   @Parameter(name = "lines", description = "Number of trailing lines to retrieve", example = "500", schema = @Schema(type = "integer", defaultValue = "500")),
                   @Parameter(name = "file", description = "Name of the log file to retrieve (e.g., 'application.log' or 'specific-service.log')", required = true, example = "application.log")
               },
               responses = {
                   @ApiResponse(responseCode = "200", description = "Log content retrieved successfully",
                                content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE, schema = @Schema(type = "string"))),
                   @ApiResponse(responseCode = "400", description = "Bad Request - Invalid parameters or restricted file access",
                                content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE, schema = @Schema(type = "string"))),
                   @ApiResponse(responseCode = "404", description = "Not Found - Log file not found or not readable",
                                content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE, schema = @Schema(type = "string"))),
                   @ApiResponse(responseCode = "500", description = "Internal Server Error - Error reading log file",
                                content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE, schema = @Schema(type = "string")))
               })
    public ResponseEntity<String> getLogs(
            @RequestParam(defaultValue = "500") int lines,
            @RequestParam String file) { // 'file' is now required as per original SystemController and HTML

        if (lines <= 0) {
            lines = 500; // Default to 500 if invalid value is provided
        }

        Path logFilePath;

        // Normalize the input filename to prevent directory traversal issues early
        String normalizedFileName = Paths.get(file).getFileName().toString();
        if (!normalizedFileName.equals(file)) { // Check if any path components were stripped
             return ResponseEntity.badRequest().body("Invalid characters or path components in filename.");
        }


        if (normalizedFileName.equals(this.defaultLogFile)) {
            // Special handling for the default log file (e.g., "application.log")
            // 1. Check within the configured logsBasePath
            logFilePath = this.logsBasePath.resolve(normalizedFileName).normalize();
            if (!Files.exists(logFilePath)) {
                // 2. Check in project root (common for default Spring Boot application.log)
                logFilePath = this.projectRoot.resolve(normalizedFileName).normalize();
                if (!Files.exists(logFilePath)) {
                    // 3. Check in a 'logs' subdirectory of project root (another common Spring Boot location)
                    logFilePath = this.projectRoot.resolve("logs").resolve(normalizedFileName).normalize();
                     if (!Files.exists(logFilePath)) {
                        // If all attempts fail for the default log file, it's effectively not found.
                        return ResponseEntity.status(404).body("Default log file '" + normalizedFileName + "' not found in standard locations: " +
                            this.logsBasePath.resolve(normalizedFileName) + ", " +
                            this.projectRoot.resolve(normalizedFileName) + ", " +
                            this.projectRoot.resolve("logs").resolve(normalizedFileName));
                    }
                }
            }
        } else {
            // For any other file, restrict to the configured logsBasePath
            logFilePath = this.logsBasePath.resolve(normalizedFileName).normalize();
            // Security check: Ensure the path is within logsBasePath
            if (!logFilePath.startsWith(this.logsBasePath)) {
                // This check should be redundant due to prior getFileName().toString() normalization
                // but kept for defense in depth.
                return ResponseEntity.badRequest().body("Access to the specified log file is restricted outside the configured log base path.");
            }
        }

        // Check if logsBasePath itself exists if we are trying to read a non-default file from it
        if (!normalizedFileName.equals(this.defaultLogFile) && (!this.logsBasePath.toFile().exists() || !this.logsBasePath.toFile().isDirectory())) {
            System.err.println("Error: Log base directory '" + this.logsBasePath + "' does not exist or is not a directory.");
            return ResponseEntity.status(404).body("Log base directory misconfiguration. Cannot access file: " + normalizedFileName);
        }


        if (!Files.exists(logFilePath) || !Files.isReadable(logFilePath) || Files.isDirectory(logFilePath)) {
            return ResponseEntity.status(404).body("Log file not found, not readable, or is a directory: " + logFilePath.toString());
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
            System.err.println("Error reading log file '" + logFilePath + "': " + e.getMessage());
            return ResponseEntity.status(500).body("Error reading log file: " + e.getMessage());
        }
    }
}
