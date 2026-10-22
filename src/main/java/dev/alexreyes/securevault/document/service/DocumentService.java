package dev.alexreyes.securevault.document.service;

import dev.alexreyes.securevault.audit.AuditAction;
import dev.alexreyes.securevault.audit.AuditOutcome;
import dev.alexreyes.securevault.audit.AuditService;
import dev.alexreyes.securevault.document.AllowedFileType;
import dev.alexreyes.securevault.document.dto.DocumentResponse;
import dev.alexreyes.securevault.document.entity.Document;
import dev.alexreyes.securevault.document.repository.DocumentRepository;
import dev.alexreyes.securevault.exception.DocumentNotFoundException;
import dev.alexreyes.securevault.exception.FileRejectedException;
import dev.alexreyes.securevault.storage.StorageException;
import dev.alexreyes.securevault.storage.StorageService;
import dev.alexreyes.securevault.user.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Document operations. Every method takes the caller's id and every lookup
 * is scoped by owner in the database query itself, so a document that
 * belongs to someone else behaves exactly like one that doesn't exist.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final String DOCUMENT_RESOURCE = "DOCUMENT";
    private static final int MAX_FILENAME_LENGTH = 255;

    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final AuditService auditService;
    private final DataSize maxFileSize;
    private final DataSize userQuota;
    private final AppUserRepository userRepository;
    private final TransactionTemplate transactionTemplate;

    public DocumentService(
            DocumentRepository documentRepository,
            StorageService storageService,
            AuditService auditService,
            AppUserRepository userRepository,
            PlatformTransactionManager transactionManager,
            @Value("${spring.servlet.multipart.max-file-size}") DataSize maxFileSize,
            @Value("${app.storage.user-quota}") DataSize userQuota) {
        this.documentRepository = documentRepository;
        this.storageService = storageService;
        this.auditService = auditService;
        this.userRepository = userRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxFileSize = maxFileSize;
        this.userQuota = userQuota;
    }

    public record DocumentDownload(Document document, Resource content) {
    }

    public DocumentResponse upload(UUID ownerId, MultipartFile file, String clientIp) {
        AllowedFileType type;
        try {
            type = validate(file);
            // Cheap unlocked pre-check so obviously over-quota uploads never
            // touch the disk; the locked check below is authoritative.
            requireWithinQuota(ownerId, file.getSize());
        } catch (FileRejectedException e) {
            auditService.record(AuditAction.DOCUMENT_UPLOAD, AuditOutcome.FAILURE, ownerId,
                    DOCUMENT_RESOURCE, null, clientIp);
            throw e;
        }

        UUID documentId = UUID.randomUUID();
        // Server-generated only: nothing from the client reaches the path.
        String storageKey = ownerId + "/" + documentId + "." + type.extension();
        try (InputStream content = file.getInputStream()) {
            storageService.store(storageKey, content);
        } catch (IOException e) {
            throw new StorageException("Failed to read upload", e);
        }

        Document newDocument = new Document(documentId, ownerId, displayFilename(file.getOriginalFilename(), type),
                storageKey, type.mediaType(), file.getSize());
        Document document;
        try {
            document = transactionTemplate.execute(status -> {
                // Lock the owner's row so concurrent uploads by the same user
                // run the quota check + insert one at a time.
                userRepository.findByIdForUpdate(ownerId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
                requireWithinQuota(ownerId, newDocument.getSizeBytes());
                return documentRepository.save(newDocument);
            });
        } catch (RuntimeException e) {
            deleteQuietly(storageKey);
            if (e instanceof FileRejectedException) {
                auditService.record(AuditAction.DOCUMENT_UPLOAD, AuditOutcome.FAILURE, ownerId,
                        DOCUMENT_RESOURCE, null, clientIp);
            }
            throw e;
        }

        auditService.record(AuditAction.DOCUMENT_UPLOAD, AuditOutcome.SUCCESS, ownerId,
                DOCUMENT_RESOURCE, documentId, clientIp);
        return DocumentResponse.from(document);
    }

    public List<DocumentResponse> list(UUID ownerId) {
        return documentRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
                .map(DocumentResponse::from)
                .toList();
    }

    public DocumentResponse get(UUID ownerId, UUID documentId) {
        return documentRepository.findByIdAndOwnerId(documentId, ownerId)
                .map(DocumentResponse::from)
                .orElseThrow(DocumentNotFoundException::new);
    }

    public DocumentDownload download(UUID ownerId, UUID documentId, String clientIp) {
        Document document = findOwned(ownerId, documentId, AuditAction.DOCUMENT_DOWNLOAD, clientIp);
        Resource content = storageService.load(document.getStorageKey());
        auditService.record(AuditAction.DOCUMENT_DOWNLOAD, AuditOutcome.SUCCESS, ownerId,
                DOCUMENT_RESOURCE, documentId, clientIp);
        return new DocumentDownload(document, content);
    }

    public void delete(UUID ownerId, UUID documentId, String clientIp) {
        Document document = findOwned(ownerId, documentId, AuditAction.DOCUMENT_DELETE, clientIp);
        // Row first, then file: a failure in between leaves an orphaned
        // file (harmless, logged) rather than a row pointing at nothing.
        documentRepository.delete(document);
        deleteQuietly(document.getStorageKey());
        auditService.record(AuditAction.DOCUMENT_DELETE, AuditOutcome.SUCCESS, ownerId,
                DOCUMENT_RESOURCE, documentId, clientIp);
    }

    private Document findOwned(UUID ownerId, UUID documentId, AuditAction action, String clientIp) {
        return documentRepository.findByIdAndOwnerId(documentId, ownerId).orElseThrow(() -> {
            // Missing or someone else's: recorded identically, with the
            // requested id, so repeated probing shows up in the audit log.
            auditService.record(action, AuditOutcome.FAILURE, ownerId, DOCUMENT_RESOURCE, documentId, clientIp);
            return new DocumentNotFoundException();
        });
    }

    private AllowedFileType validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new FileRejectedException(HttpStatus.BAD_REQUEST, "File is empty");
        }
        if (file.getSize() > maxFileSize.toBytes()) {
            throw new FileRejectedException(HttpStatus.CONTENT_TOO_LARGE,
                    "File exceeds the " + maxFileSize.toMegabytes() + " MB limit");
        }
        byte[] leadingBytes;
        try (InputStream content = file.getInputStream()) {
            leadingBytes = content.readNBytes(AllowedFileType.SNIFF_LENGTH);
        } catch (IOException e) {
            throw new StorageException("Failed to read upload", e);
        }
        return AllowedFileType.detect(leadingBytes)
                .orElseThrow(() -> new FileRejectedException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "Only PDF, PNG, and JPEG files are allowed"));
    }

    private void requireWithinQuota(UUID ownerId, long additionalBytes) {
        long used = documentRepository.totalSizeByOwnerId(ownerId);
        if (used + additionalBytes > userQuota.toBytes()) {
            throw new FileRejectedException(HttpStatus.CONFLICT,
                    "Upload would exceed your storage quota (" + used + " of " + userQuota.toBytes()
                            + " bytes used, this file is " + additionalBytes + " bytes)");
        }
    }

    /**
     * The client's filename, reduced to something safe to show and to put
     * in a Content-Disposition header: no directory part, no control
     * characters, bounded length, and an extension matching the detected
     * type (so a PDF can't be saved by the browser as "invoice.html").
     */
    static String displayFilename(String original, AllowedFileType type) {
        String name = original == null ? "" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            name = "document";
        }

        String lower = name.toLowerCase(Locale.ROOT);
        boolean extensionMatches = lower.endsWith("." + type.extension())
                || (type == AllowedFileType.JPEG && lower.endsWith(".jpeg"));
        String suffix = extensionMatches ? "" : "." + type.extension();

        int maxBaseLength = MAX_FILENAME_LENGTH - suffix.length();
        if (name.codePointCount(0, name.length()) > maxBaseLength) {
            name = name.substring(0, name.offsetByCodePoints(0, maxBaseLength));
        }
        return name + suffix;
    }

    private void deleteQuietly(String storageKey) {
        try {
            storageService.delete(storageKey);
        } catch (StorageException e) {
            log.warn("Could not delete stored file {}; it is now orphaned", storageKey, e);
        }
    }
}
