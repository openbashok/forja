package com.openbash.forja.traffic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class AppModelTest {

    private AppModel model;

    @BeforeEach
    void setUp() {
        model = new AppModel();
    }

    @Test
    void addOrUpdate_newEndpoint() {
        EndpointInfo ep = model.addOrUpdate("GET", "/users/123", "/users/{id}");
        assertEquals("GET", ep.getMethod());
        assertEquals("/users/{id}", ep.getPathPattern());
        assertEquals(1, ep.getTimesSeen());
        assertEquals(1, model.getEndpointCount());
    }

    @Test
    void addOrUpdate_existingEndpointIncrements() {
        model.addOrUpdate("GET", "/users/123", "/users/{id}");
        model.addOrUpdate("GET", "/users/456", "/users/{id}");
        assertEquals(1, model.getEndpointCount());
        EndpointInfo ep = model.getEndpoints().get("GET /users/{id}");
        assertEquals(2, ep.getTimesSeen());
    }

    @Test
    void addOrUpdate_differentMethodsDifferentEndpoints() {
        model.addOrUpdate("GET", "/users", "/users");
        model.addOrUpdate("POST", "/users", "/users");
        assertEquals(2, model.getEndpointCount());
    }

    @Test
    void eviction_removesLeastSeen() {
        model.setMaxEntries(3);
        model.addOrUpdate("GET", "/a", "/a");
        EndpointInfo b = model.addOrUpdate("GET", "/b", "/b");
        model.addOrUpdate("GET", "/c", "/c");

        // Make /b seen more
        model.addOrUpdate("GET", "/b", "/b");
        model.addOrUpdate("GET", "/b", "/b");

        // This should evict one of the least-seen endpoints
        model.addOrUpdate("GET", "/d", "/d");
        assertEquals(3, model.getEndpointCount());
        // /b (seen 3 times) should survive
        assertNotNull(model.getEndpoints().get("GET /b"));
    }

    @Test
    void clear_removesEverything() {
        model.addOrUpdate("GET", "/a", "/a");
        model.addTechStack("Java");
        model.addCookie("JSESSIONID");
        model.addInterestingPattern("JWT");

        model.clear();

        assertEquals(0, model.getEndpointCount());
        assertTrue(model.getTechStack().isEmpty());
        assertTrue(model.getCookies().isEmpty());
        assertTrue(model.getInterestingPatterns().isEmpty());
    }

    @Test
    void threadSafety() throws InterruptedException {
        int threads = 10;
        int opsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < opsPerThread; i++) {
                        model.addOrUpdate("GET", "/path/" + threadId + "/" + i, "/path/{id}/{id}");
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // All should resolve to same pattern, so only 1 endpoint
        assertEquals(1, model.getEndpointCount());
        EndpointInfo ep = model.getEndpoints().get("GET /path/{id}/{id}");
        assertNotNull(ep);
        assertEquals(threads * opsPerThread, ep.getTimesSeen());
    }

    @Test
    void pathNormalization() {
        assertEquals("/", TrafficCollector.normalizePath(""));
        assertEquals("/", TrafficCollector.normalizePath("/"));
        assertEquals("/users/{id}", TrafficCollector.normalizePath("/users/123"));
        assertEquals("/api/v1/items/{id}", TrafficCollector.normalizePath("/api/v1/items/42"));
        assertEquals("/users/{uuid}/profile",
                TrafficCollector.normalizePath("/users/550e8400-e29b-41d4-a716-446655440000/profile"));
        assertEquals("/api/search", TrafficCollector.normalizePath("/api/search"));
    }
}
