package com.walpro.autobuilder.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.walpro.autobuilder.model.FileInfo;
import com.walpro.autobuilder.service.FileService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

@RestController
@RequestMapping("/files")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @Operation(summary = "Read file as UTF-8 text", description = "Reads a text file and returns it as a UTF-8 string.")
    @ApiResponses({
	    @ApiResponse(responseCode = "200", description = "File read successfully"),
	    @ApiResponse(responseCode = "400", description = "Invalid file path")
    })
    @GetMapping("/read")
    public String readAsText(@RequestParam String path) throws IOException {
        return fileService.readFile(path); // returns text (UTF-8)
    }
    

    @Operation(summary = "Read file as raw bytes", description = "Returns the file contents as a raw byte array (no headers).")
    @ApiResponses({
	    @ApiResponse(responseCode = "200", description = "File read successfully"),
	    @ApiResponse(responseCode = "404", description = "File not found")
    })
    @GetMapping("/raw/{path:.+}")
    public byte[] readBytesRaw(@PathVariable String path) throws IOException {
        return fileService.readBytes(path);
    }

    @Operation(
    	    summary = "Write content to a file",
    	    description = "Creates or overwrites a file within the base directory with the given content."
    	)
    @ApiResponses({
    	@ApiResponse(responseCode = "200", description = "File written successfully"),
    	@ApiResponse(responseCode = "400", description = "Invalid path or input"),
    	@ApiResponse(responseCode = "500", description = "Internal server error")
    })
    @PostMapping("/write")
    public void write(@RequestParam String path, @RequestBody String content) throws IOException {
        fileService.writeFile(path, content);
    }

    @Operation(
            summary = "List files in a directory",
            description = "Returns a list of relative file paths within the given directory."
        )
    @ApiResponses({
	    @ApiResponse(responseCode = "200", description = "Files listed successfully"),
	    @ApiResponse(responseCode = "404", description = "Directory not found")
    })
    @GetMapping("/list")
    public List<FileInfo> list(@RequestParam String path) throws IOException {
        return fileService.listFiles(path);
    }

    @Operation(
            summary = "Delete a file",
            description = "Deletes the specified file within the base directory."
        )
    @ApiResponses({
	    @ApiResponse(responseCode = "200", description = "File deleted successfully"),
	    @ApiResponse(responseCode = "404", description = "File not found")
    })
    @DeleteMapping("/{path:.+}")
    public ResponseEntity<String> delete(@PathVariable String path) throws IOException {
        fileService.deleteFile(path);
        return ResponseEntity.ok("Deleted: " + path);
    }
    
    @Operation(summary = "Download file as binary", description = "Downloads the specified file as a byte array with headers.")
    @ApiResponses({
	    @ApiResponse(responseCode = "200", description = "File downloaded"),
	    @ApiResponse(responseCode = "404", description = "File not found")
    })
    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@RequestParam String path) throws IOException {
        Path filePath = fileService.resolveSecurePath(path);
        byte[] data = fileService.readAllBytes(filePath);

        String contentType = fileService.detectMimeType(filePath)
            .orElse("application/octet-stream");

        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + filePath.getFileName() + "\"")
            .header("Content-Type", contentType)
            .body(data);
    }
    
    @Operation(
    	    summary = "Upload a file",
    	    description = "Uploads a binary file to the optional path (relative to the base directory)."
    	)
    	@ApiResponses({
    	    @ApiResponse(responseCode = "200", description = "File uploaded successfully"),
    	    @ApiResponse(responseCode = "400", description = "Invalid file or path"),
    	    @ApiResponse(responseCode = "500", description = "Internal error during upload")
    	})
    	@PostMapping("/upload")
    	public ResponseEntity<String> uploadFile(
    	        @RequestParam("file") MultipartFile file,
    	        @RequestParam(value = "path", required = false) String relativePath
    	) throws IOException {
    	    String savedPath = fileService.saveUploadedFile(file, relativePath);
    	    return ResponseEntity.ok("File uploaded to: " + savedPath);
    	}
}
