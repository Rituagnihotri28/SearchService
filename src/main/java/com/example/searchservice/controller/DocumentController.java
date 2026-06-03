package com.example.searchservice.controller;

import com.example.searchservice.model.Document;
import com.example.searchservice.service.DocumentSearchService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
public class DocumentController {

    /** Service used by the REST endpoints for indexing, searching, and deleting documents. */
    private final DocumentSearchService service;

    public DocumentController(DocumentSearchService service) {
        this.service = service;
    }

    /** Indexes a new document for the current tenant. */
    @PostMapping("/documents")
    public ResponseEntity<?> index(@RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
                                   @RequestBody DocumentRequest request) {
        String tenantId = request.tenantId() != null ? request.tenantId() : tenantHeader;
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "default";
        }
        if (!service.allowRequest(tenantId)) {
            return ResponseEntity.status(429).body(Map.of("error", "Rate limit exceeded for tenant " + tenantId));
        }

        Document saved = service.index(tenantId, request.title(), request.content());
        return ResponseEntity.status(201).body(saved);
    }

    /** Searches indexed documents for the supplied query within the tenant scope. */
    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam("q") String query,
                                    @RequestParam(value = "tenant", required = false) String tenantId,
                                    @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader) {
        String resolvedTenant = tenantId != null && !tenantId.isBlank() ? tenantId : tenantHeader;
        if (resolvedTenant == null || resolvedTenant.isBlank()) {
            resolvedTenant = "default";
        }
        if (!service.allowRequest(resolvedTenant)) {
            return ResponseEntity.status(429).body(Map.of("error", "Rate limit exceeded for tenant " + resolvedTenant));
        }
        return ResponseEntity.ok(Map.of("query", query, "tenantId", resolvedTenant, "results", service.search(resolvedTenant, query)));
    }

    /** Returns a single document by its identifier for the current tenant only. */
    @GetMapping("/documents/{id}")
    public ResponseEntity<?> get(@PathVariable UUID id,
                                 @RequestParam(value = "tenant", required = false) String tenantId,
                                 @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader) {
        String resolvedTenant = tenantId != null && !tenantId.isBlank() ? tenantId : tenantHeader;
        if (resolvedTenant == null || resolvedTenant.isBlank()) {
            resolvedTenant = "default";
        }

        return service.getById(id, resolvedTenant)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Deletes an indexed document by identifier for the current tenant only. */
    @DeleteMapping("/documents/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id,
                                    @RequestParam(value = "tenant", required = false) String tenantId,
                                    @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader) {
        String resolvedTenant = tenantId != null && !tenantId.isBlank() ? tenantId : tenantHeader;
        if (resolvedTenant == null || resolvedTenant.isBlank()) {
            resolvedTenant = "default";
        }

        return service.delete(id, resolvedTenant)
                ? ResponseEntity.ok(Map.of("status", "deleted", "id", id.toString()))
                : ResponseEntity.notFound().build();
    }

    /** Returns the current health status and dependency checks. */
    @GetMapping({"/health", "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "dependencies", service.health(),
                "endpoints", Map.of(
                        "health", "/health",
                        "liveness", "/actuator/health/liveness",
                        "readiness", "/actuator/health/readiness"
                )
        ));
    }

    public record DocumentRequest(@NotBlank String title, @NotBlank String content, String tenantId) {
    }
}
