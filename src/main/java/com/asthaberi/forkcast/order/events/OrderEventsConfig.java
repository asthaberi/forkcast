package com.asthaberi.forkcast.order.events;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables the scheduled SSE heartbeat in {@link OrderEventStreams}. */
@Configuration
@EnableScheduling
public class OrderEventsConfig {
}
