package dev.alexreyes.securevault.document.controller;

import dev.alexreyes.securevault.document.dto.DocumentResponse;
import dev.alexreyes.securevault.document.service.DocumentService;
import dev.alexreyes.securevault.document.service.DocumentService.DocumentDownload;
import dev.alexreyes.securevault.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse upload(@AuthenticationPrincipal AuthenticatedUser user,
                                   @RequestParam("file") MultipartFile file,
                                   HttpServletRequest request) {
        return documentService.upload(user.id(), file, request.getRemoteAddr());
    }

    @GetMapping
    public List<DocumentResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return documentService.list(user.id());
    }

    @GetMapping("/{id}")
    public DocumentResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return documentService.get(user.id(), id);
    }

    /**
     * Always served as an attachment with the type detected at upload.
     * {@code X-Content-Type-Options: nosniff} comes from Spring Security's
     * default headers on every response (asserted in the tests).
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal AuthenticatedUser user,
                                             @PathVariable UUID id,
                                             HttpServletRequest request) {
        DocumentDownload download = documentService.download(user.id(), id, request.getRemoteAddr());
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.document().getOriginalFilename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.document().getContentType()))
                .contentLength(download.document().getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(download.content());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser user,
                       @PathVariable UUID id,
                       HttpServletRequest request) {
        documentService.delete(user.id(), id, request.getRemoteAddr());
    }
}
