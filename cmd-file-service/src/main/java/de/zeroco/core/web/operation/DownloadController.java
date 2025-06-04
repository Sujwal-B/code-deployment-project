package de.zeroco.core.web.operation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.io.IOException; // Required for Files.createDirectories

@RestController
@RequestMapping("/api/system")
@Tag(name = "Download Operations", description = "Endpoints for downloading files")
public class DownloadController {

    private final Path baseDownloadsPath;

    public DownloadController(@Value("${app.download.base-path:downloads}") String downloadPathValue) {
        this.baseDownloadsPath = Paths.get(downloadPathValue).toAbsolutePath().normalize();
        // Note: As per plan, this controller does NOT create the baseDownloadsPath directory.
        // It assumes the directory pointed to by app.download.base-path exists.
        // The original SystemController *did* create a 'downloads' directory.
        // This new controller relies on external setup for this path.
        System.out.println("DownloadController: Base download path configured to: " + this.baseDownloadsPath);
    }

    @GetMapping("/download")
    @Operation(summary = "Download a file from a URL",
               description = "Downloads a file from the given URL and saves it to a specified destination within the configured downloads directory. Path traversal is prevented.",
               parameters = {
                   @Parameter(name = "url", description = "URL of the file to download", required = true, example = "https://www.example.com/somefile.txt"),
                   @Parameter(name = "destination", description = "Destination filename or relative path within the downloads directory", required = true, example = "myfile.txt")
               },
               responses = {
                   @ApiResponse(responseCode = "200", description = "File downloaded successfully",
                                content = @Content(mediaType = "text/plain", schema = @Schema(type = "string"))),
                   @ApiResponse(responseCode = "400", description = "Bad Request - Invalid URL, invalid destination path, or path traversal attempt",
                                content = @Content(mediaType = "text/plain", schema = @Schema(type = "string"))),
                   @ApiResponse(responseCode = "500", description = "Internal Server Error - Error downloading file or other unexpected error",
                                content = @Content(mediaType = "text/plain", schema = @Schema(type = "string")))
               })
    public ResponseEntity<?> downloadFile(@RequestParam String url, @RequestParam String destination) {
        try {
            new URL(url).toURI(); // Validate URL syntax
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid URL format: " + e.getMessage());
        }

        Path destinationPath = this.baseDownloadsPath.resolve(destination).normalize();

        // Security check: Ensure the resolved destination path is still within the baseDownloadsPath
        if (!destinationPath.startsWith(this.baseDownloadsPath)) {
            return ResponseEntity.badRequest().body("Invalid destination path. Path traversal attempt detected.");
        }

        // Check if baseDownloadsPath directory exists, log if not, but don't create.
        if (!this.baseDownloadsPath.toFile().exists() || !this.baseDownloadsPath.toFile().isDirectory()) {
             System.err.println("Error: Base download directory '" + this.baseDownloadsPath + "' does not exist or is not a directory.");
             return ResponseEntity.internalServerError().body("Download directory misconfiguration. Please contact administrator.");
        }

        try {
            // Ensure parent directories for the destination *file* exist within the baseDownloadsPath
            Path parentDir = destinationPath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                // This creates subdirectories within baseDownloadsPath if specified in 'destination'
                // e.g., destination = "subdir/myfile.txt" will create "subdir"
                // This is generally acceptable as it's within the controlled base path.
                Files.createDirectories(parentDir);
            }

            try (InputStream in = URI.create(url).toURL().openStream()) {
                Files.copy(in, destinationPath, StandardCopyOption.REPLACE_EXISTING);
            }
            return ResponseEntity.ok("File downloaded successfully to: " + destinationPath.toString());
        } catch (IOException e) {
            // Log the exception for server-side diagnostics
            System.err.println("Error downloading file from URL '" + url + "' to '" + destinationPath + "': " + e.getMessage());
            return ResponseEntity.status(500).body("Error downloading file: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Unexpected error during file download: " + e.getMessage());
            return ResponseEntity.status(500).body("An unexpected error occurred: " + e.getMessage());
        }
    }
}
