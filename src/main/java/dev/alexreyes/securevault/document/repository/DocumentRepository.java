package dev.alexreyes.securevault.document.repository;

import dev.alexreyes.securevault.document.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lookups are always scoped by owner. Use these rather than the inherited
 * {@code findById}, which has no ownership check.
 */
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<Document> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    @Query("select coalesce(sum(d.sizeBytes), 0) from Document d where d.ownerId = :ownerId")
    long totalSizeByOwnerId(UUID ownerId);
}
