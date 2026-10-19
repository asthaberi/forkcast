package com.asthaberi.forkcast;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Checks that the whole Spring context wires up without MongoDB or Redis running: seeding and index
// creation are off, and Redis points at a closed port, so the app must start with its dependencies down.
@SpringBootTest(properties = {
		"forkcast.seed.enabled=false",
		"spring.data.mongodb.auto-index-creation=false",
		"spring.data.redis.port=1"
})
class ForkcastApplicationTests {

	@Test
	void contextLoads() {
	}

}
