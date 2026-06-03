package com.example.searchservice.repository;

import com.example.searchservice.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {
    /** Returns documents for one tenant. */
    List<Document> findByTenantId(String tenantId);

    /** Searches documents by tenant and keyword fragment. */
    @Query("select d from Document d where lower(d.tenantId) = lower(?1) and (lower(d.title) like lower(?2) or lower(d.content) like lower(?2))")
    List<Document> search(String tenantId, String term);
}
