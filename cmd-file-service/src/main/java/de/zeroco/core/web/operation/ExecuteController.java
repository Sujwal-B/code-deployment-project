package de.zeroco.core.web.operation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/system")
@Tag(name = "Execute Operations", description = "Endpoints for executing system commands")
public class ExecuteController {

    private final Path sandboxPath;

    // Define ExecuteRequest as a static inner class or a separate POJO
    public static class ExecuteRequest {
        private String command;
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
    }

    public ExecuteController(@Value("${app.execute.sandbox-path:sandbox}") String sandboxPathValue) {
        // It's good practice to resolve and normalize paths from properties once
        this.sandboxPath = Paths.get(sandboxPathValue).toAbsolutePath().normalize();
        // Note: As per plan, this controller does NOT create the directory.
        // It assumes the directory pointed to by app.execute.sandbox-path exists.
        // If it does not exist, ProcessBuilder will fail.
        // Consider adding a check and logging a warning if it doesn't exist at startup,
        // or rely on external setup to ensure it's there.
         System.out.println("ExecuteController: Sandbox path configured to: " + this.sandboxPath);
    }

    @PostMapping(value = "/execute", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "Execute a system command",
               description = "Executes a given command in a sandboxed environment. The command must not contain restricted characters.",
               requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                   description = "Command to execute",
                   required = true,
                   content = @Content(schema = @Schema(implementation = ExecuteRequest.class))
               ),
               responses = {
                   @ApiResponse(responseCode = "200", description = "Command executed successfully, returns output",
                                content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE, schema = @Schema(type = "string"))),
                   @ApiResponse(responseCode = "400", description = "Bad Request - Invalid command (empty, contains restricted characters)",
                                content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE, schema = @Schema(type = "string"))),
                   @ApiResponse(responseCode = "500", description = "Internal Server Error - Command execution failed or error occurred",
                                content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE, schema = @Schema(type = "string"))),
                   @ApiResponse(responseCode = "504", description = "Gateway Timeout - Command timed out",
                                content = @Content(mediaType = MediaType.TEXT_PLAIN_VALUE, schema = @Schema(type = "string")))
               })
    public ResponseEntity<String> executeCommand(@RequestBody ExecuteRequest request) {
        if (request == null || request.getCommand() == null || request.getCommand().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Command cannot be empty.");
        }
        String command = request.getCommand();
        // Basic validation for command safety
        if (command.contains("..") || command.contains(";") || command.contains("&&") || command.contains("||") || command.contains("|") || command.contains("`")) {
            return ResponseEntity.badRequest().body("Invalid characters in command. Execution restricted.");
        }

        // Check if sandbox directory exists, log if not, but don't create.
        // This is a runtime check. Ideally, the application deployer ensures this path exists.
        if (!sandboxPath.toFile().exists() || !sandboxPath.toFile().isDirectory()) {
             System.err.println("Error: Sandbox directory '" + sandboxPath + "' does not exist or is not a directory.");
             return ResponseEntity.internalServerError().body("Sandbox directory misconfiguration. Please contact administrator.");
        }

        StringBuilder output = new StringBuilder();
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command("/bin/bash", "-c", command);
        processBuilder.directory(sandboxPath.toFile()); // Use the configured sandbox path
        processBuilder.redirectErrorStream(true);

        try {
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
            Thread.currentThread().interrupt(); // Restore interrupted status
            return ResponseEntity.status(500).body("Error executing command: " + e.getMessage());
        }
    }
}
