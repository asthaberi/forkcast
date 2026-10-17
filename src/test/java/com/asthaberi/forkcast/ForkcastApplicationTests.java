package com.asthaberi.forkcast;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Checks that the whole Spring context wires up. Seeding and index creation are switched off
// so this runs without a database; real MongoDB tests come with Testcontainers.
@SpringBootTest(properties = {
		"forkcast.seed.enabled=false",
		"spring.data.mongodb.auto-index-creation=false"
})
class ForkcastApplicationTests {

	@Test
	void contextLoads() {
	}

}
