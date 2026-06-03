package com.example.searchservice.service;

import com.example.searchservice.model.Document;
import com.example.searchservice.repository.DocumentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DocumentSearchService {
    /** Tracks simple per-tenant request quotas for the prototype API. */

    private static final int RATE_LIMIT_PER_MINUTE = 60;

    private final DocumentRepository repository;
    private final RedisTemplate<String, String> redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, TenantRateLimiter> rateLimiters = new ConcurrentHashMap<>();

    public DocumentSearchService(DocumentRepository repository,
                                 RedisTemplate<String, String> redisTemplate,
                                 JdbcTemplate jdbcTemplate,
                                 ObjectMapper objectMapper) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** Stores a new document and invalidates tenant search cache. */
    public Document index(String tenantId, String title, String content) {
        Document document = new Document();
        document.setTenantId(tenantId);
        document.setTitle(title);
        document.setContent(content);
        redisTemplate.delete("search:" + tenantId.toLowerCase(Locale.ROOT));
        return repository.save(document);
    }

    /** Searches documents for the tenant and returns cached or fresh results. */
    @CircuitBreaker(name = "documentService", fallbackMethod = "fallbackSearch")
    public List<Document> search(String tenantId, String query) {
        String cacheKey = "search:" + tenantId.toLowerCase(Locale.ROOT) + ":" + query.toLowerCase(Locale.ROOT);
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, new TypeReference<List<Document>>() {});
            } catch (Exception ignored) {
                // fall back to a fresh query if cache payload cannot be decoded
            }
        }

        String term = "%" + query.toLowerCase(Locale.ROOT) + "%";
        List<Document> results = repository.search(tenantId, term);
        results.sort(Comparator.comparing(Document::getCreatedAt).reversed());
        try {
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(results), java.time.Duration.ofMinutes(1));
        } catch (Exception ignored) {
            // cache is optional for this prototype
        }
        return results;
    }

    /** Loads one document by its identifier only if it belongs to the tenant. */
    @CircuitBreaker(name = "documentService", fallbackMethod = "fallbackGetById")
    public Optional<Document> getById(UUID id, String tenantId) {
        return repository.findById(id)
                .filter(document -> tenantId.equalsIgnoreCase(document.getTenantId()));
    }

    /** Deletes a document only if it belongs to the tenant. */
    @CircuitBreaker(name = "documentService", fallbackMethod = "fallbackDelete")
    public boolean delete(UUID id, String tenantId) {
        Optional<Document> document = repository.findById(id);
        if (document.isEmpty() || !tenantId.equalsIgnoreCase(document.get().getTenantId())) {
            return false;
        }
        repository.deleteById(id);
        return true;
    }

    /** Enforces simple tenant-based rate limiting. */
    public boolean allowRequest(String tenantId) {
        TenantRateLimiter limiter = rateLimiters.computeIfAbsent(tenantId, key -> new TenantRateLimiter());
        return limiter.allowRequest();
    }

    /** Returns dependency status for PostgreSQL and Redis. */
    @CircuitBreaker(name = "documentService", fallbackMethod = "fallbackHealth")
    public Map<String, Object> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "ok");
        status.put("postgres", jdbcTemplate.queryForObject("select 1", Integer.class) == 1 ? "ok" : "down");
        status.put("redis", "pong".equals(redisTemplate.getConnectionFactory().getConnection().ping()) ? "ok" : "down");
        return status;
    }

    private List<Document> fallbackSearch(String tenantId, String query, Throwable throwable) {
        return List.of();
    }

    private Optional<Document> fallbackGetById(UUID id, String tenantId, Throwable throwable) {
        return Optional.empty();
    }

    private boolean fallbackDelete(UUID id, String tenantId, Throwable throwable) {
        return false;
    }

    private Map<String, Object> fallbackHealth(Throwable throwable) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "degraded");
        status.put("postgres", "unknown");
        status.put("redis", "unknown");
        status.put("circuitBreaker", "open");
        return status;
    }

    private static class TenantRateLimiter {
        private int requests = 0;
        private long windowStart = System.currentTimeMillis();

        private synchronized boolean allowRequest() {
            long now = System.currentTimeMillis();
            if (now - windowStart >= 60_000L) {
                windowStart = now;
                requests = 0;
            }
            if (requests >= RATE_LIMIT_PER_MINUTE) {
                return false;
            }
            requests++;
            return true;
        }
    }
}
