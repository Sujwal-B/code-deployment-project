package de.zeroco.core.web;

import de.zeroco.core.security.SecuredOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Minimal configuration for SecuredOperation to be recognized
@Import(SystemController.class) // Import controller to test
@WebMvcTest(SystemController.class)
class SystemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // SystemController creates directories in its constructor.
    // We need to ensure these directories exist for tests or mock them.
    // Using @TempDir is a good approach for file-based tests.
    @TempDir
    Path tempDir;

    private Path sandboxDir;
    private Path downloadsDir;
    private Path logsDataDir;
    private Path projectRootForTest;


    // We need to mock ProcessBuilder and Process to avoid actual command execution
    // This is tricky because ProcessBuilder is a final class.
    // One common way is to have an injectable service that handles command execution,
    // which can then be mocked. For this test, we'll assume the SystemController's
    // direct use of ProcessBuilder and try to manage its creation context or use a more
    // involved mocking strategy if needed. Given current structure, we'll test behavior
    // around it.

    @BeforeEach
    void setUp() throws IOException, IllegalAccessException {
        // The controller creates these directories at startup relative to the actual project root.
        // We will ensure these exist for the tests.
        sandboxDir = getActualProjectRoot().resolve("sandbox");
        downloadsDir = getActualProjectRoot().resolve("downloads");
        logsDataDir = getActualProjectRoot().resolve("logs_data");

        Files.createDirectories(sandboxDir);
        Files.createDirectories(downloadsDir);
        Files.createDirectories(logsDataDir);
    }

    // Helper to get actual project root for path assertions
    private Path getActualProjectRoot() {
        return Paths.get(".").toAbsolutePath().normalize();
    }


    @Test
    void executeCommand_success() throws Exception {
        // This test is hard to do without refactoring SystemController to make ProcessBuilder mockable
        // or using PowerMock/Mockito-inline for final classes/methods.
        // For now, we'll test the request mapping and basic success based on a simple command.
        // We'll assume a command like 'echo test' would work if ProcessBuilder was properly mocked.
        // The current implementation of SystemController directly calls new ProcessBuilder().
        // A better approach would be to inject an ExecutorService or a CommandRunner.

        // Since we can't easily mock ProcessBuilder, we'll test the aspects we can control:
        // - Request validation
        // - Correct directory context if we could verify it (hard without mocking)
        // We will simulate a successful command by ensuring the sandbox exists.
        Path actualSandboxDir = getActualProjectRoot().resolve("sandbox");
        if(!Files.exists(actualSandboxDir)) Files.createDirectories(actualSandboxDir);
        // Create a simple script that ProcessBuilder can execute
        String scriptName = "test_script.sh";
        String scriptContent = "#!/bin/bash\necho 'Hello from script'";
        Path scriptPath = actualSandboxDir.resolve(scriptName);
        Files.writeString(scriptPath, scriptContent);
        scriptPath.toFile().setExecutable(true); // Make it executable

        mockMvc.perform(post("/api/system/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"command\":\"./" + scriptName + "\"}")) // Execute the script
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString("Hello from script")));

        Files.deleteIfExists(scriptPath);
    }

    @Test
    void executeCommand_invalidInput() throws Exception {
        mockMvc.perform(post("/api/system/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"command\":\"echo hello; rm -rf /\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid characters in command."));
    }

    @Test
    void executeCommand_emptyCommand() throws Exception {
        mockMvc.perform(post("/api/system/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"command\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Command cannot be empty."));
    }

    @Test
    void downloadFile_success() throws Exception {
        // Simulate a file to be "downloaded" from a mock URL
        // The controller writes to DOWNLOADS_DIRECTORY.
        Path actualDownloadsDir = getActualProjectRoot().resolve("downloads");
        if(!Files.exists(actualDownloadsDir)) Files.createDirectories(actualDownloadsDir);

        String destFile = "test_download.txt";
        // A real URL that points to a small text file for testing purposes
        String remoteUrl = "https://raw.githubusercontent.com/spring-projects/spring-boot/main/README.md";


        mockMvc.perform(get("/api/system/download")
                .param("url", remoteUrl)
                .param("destination", destFile))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("File downloaded successfully to: " + actualDownloadsDir.resolve(destFile).toString())));

        assertTrue(Files.exists(actualDownloadsDir.resolve(destFile)), "Downloaded file should exist");
        // Clean up
        Files.deleteIfExists(actualDownloadsDir.resolve(destFile));
    }

    @Test
    void downloadFile_invalidDestination_pathTraversal() throws Exception {
        mockMvc.perform(get("/api/system/download")
                .param("url", "http://example.com/file.txt")
                .param("destination", "../evil.txt"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid destination path. Path traversal attempt detected."));
    }

    @Test
    void downloadFile_invalidUrl() throws Exception {
        mockMvc.perform(get("/api/system/download")
                .param("url", "notaurl")
                .param("destination", "somefile.txt"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Invalid URL format")));
    }

    @Test
    void downloadFile_createsParentDirectories() throws Exception {
        Path actualDownloadsDir = getActualProjectRoot().resolve("downloads");
        if(!Files.exists(actualDownloadsDir)) Files.createDirectories(actualDownloadsDir);
        String destFile = "parent/child/test_download.txt";
        String remoteUrl = "https://raw.githubusercontent.com/spring-projects/spring-boot/main/README.md";

        mockMvc.perform(get("/api/system/download")
                .param("url", remoteUrl)
                .param("destination", destFile))
                .andExpect(status().isOk());

        assertTrue(Files.exists(actualDownloadsDir.resolve(destFile)), "Downloaded file should exist in nested directory");
        Files.deleteIfExists(actualDownloadsDir.resolve(destFile));
        Files.deleteIfExists(actualDownloadsDir.resolve("parent/child"));
        Files.deleteIfExists(actualDownloadsDir.resolve("parent"));
    }


    @Test
    void getLogs_defaultApplicationLog() throws Exception {
        Path actualProjectRoot = getActualProjectRoot();
        Path appLogPath = actualProjectRoot.resolve("application.log");
        Files.writeString(appLogPath, "line1\nline2\nline3");

        mockMvc.perform(get("/api/system/logs")) // Defaults to application.log & 500 lines
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("line1\nline2\nline3"));

        Files.deleteIfExists(appLogPath);
    }

    @Test
    void getLogs_fromLogsDataDirectory() throws Exception {
        Path actualLogsDataDir = getActualProjectRoot().resolve("logs_data");
        if(!Files.exists(actualLogsDataDir)) Files.createDirectories(actualLogsDataDir);
        Path customLog = actualLogsDataDir.resolve("custom.log");
        Files.writeString(customLog, "custom log line 1\ncustom log line 2");

        mockMvc.perform(get("/api/system/logs")
                .param("file", "custom.log"))
                .andExpect(status().isOk())
                .andExpect(content().string("custom log line 1\ncustom log line 2"));

        Files.deleteIfExists(customLog);
    }

    @Test
    void getLogs_limitLines() throws Exception {
        Path actualProjectRoot = getActualProjectRoot();
        Path appLogPath = actualProjectRoot.resolve("application.log");
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            lines.add("Line " + i);
        }
        Files.write(appLogPath, lines, StandardCharsets.UTF_8);

        mockMvc.perform(get("/api/system/logs")
                .param("lines", "3"))
                .andExpect(status().isOk())
                .andExpect(content().string("Line 8\nLine 9\nLine 10"));

        Files.deleteIfExists(appLogPath);
    }

    @Test
    void getLogs_fileNotFound() throws Exception {
        mockMvc.perform(get("/api/system/logs")
                .param("file", "nonexistent.log"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Log file not found or not readable")));
    }

    @Test
    void getLogs_pathTraversalAttempt() throws Exception {
        mockMvc.perform(get("/api/system/logs")
                .param("file", "../secrets.txt"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Access to the specified log file is restricted."));
    }

    @Test
    void getLogs_applicationLogNotFound_shouldTryLogsData() throws Exception {
        Path actualProjectRoot = getActualProjectRoot();
        Path logsDataDir = actualProjectRoot.resolve("logs_data");
        if(!Files.exists(logsDataDir)) Files.createDirectories(logsDataDir);
        Path appLogInLogsData = logsDataDir.resolve("application.log");
        Files.writeString(appLogInLogsData, "app log from logs_data");

        // Ensure no application.log in root or /logs
        Files.deleteIfExists(actualProjectRoot.resolve("application.log"));
        Path logsSubDir = actualProjectRoot.resolve("logs");
        Files.deleteIfExists(logsSubDir.resolve("application.log"));
        // Files.deleteIfExists(logsSubDir); // remove if it was created

        mockMvc.perform(get("/api/system/logs")
                .param("file", "application.log")) // default file
                .andExpect(status().isOk())
                .andExpect(content().string("app log from logs_data"));

        Files.deleteIfExists(appLogInLogsData);
    }


    @AfterEach
    void tearDown() throws IOException {
        // Clean up directories and files created directly in the project root by tests or controller
        // This is important because @TempDir only handles its own path.
        // The SystemController creates sandbox, downloads, logs_data in its constructor in the project root.

        // Clean up files created in tests
        Files.deleteIfExists(getActualProjectRoot().resolve("application.log"));
        Path customLog = getActualProjectRoot().resolve("logs_data").resolve("custom.log");
        Files.deleteIfExists(customLog);
        Path testScript = getActualProjectRoot().resolve("sandbox").resolve("test_script.sh");
        Files.deleteIfExists(testScript);

        // It's generally better if the controller itself doesn't create directories in fixed locations
        // during construction for easier testing. Instead, they could be created on-demand or their paths injected.
        // For now, we leave the directories created by the controller as they are, as they are part of its defined behavior.
        // If these directories were only for tests, we would remove them here.
        // Example: FileUtils.deleteDirectory(getActualProjectRoot().resolve("sandbox").toFile());
    }
}
