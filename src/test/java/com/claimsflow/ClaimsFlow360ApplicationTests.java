package com.claimsflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ClaimsFlow360ApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the Spring context boots cleanly with the H2/test profile.
    }
}
