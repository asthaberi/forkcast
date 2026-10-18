package com.asthaberi.forkcast.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import com.asthaberi.forkcast.common.exception.ResourceNotFoundException;
import com.asthaberi.forkcast.order.events.OrderEvent;
import com.asthaberi.forkcast.order.events.OrderEventBus;
import com.asthaberi.forkcast.order.events.OrderEventStreams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class OrderLifecycleServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T07:10:00Z");

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderEventBus eventBus;
    @Mock
    private OrderEventStreams streams;

    private OrderLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new OrderLifecycleService(orderRepository, eventBus, streams, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void restaurantAcceptsAndTheChangeIsPublishedAfterSaving() {
        when(orderRepository.findByIdAndRestaurantId("o1", "r1")).thenReturn(Optional.of(placedOrder()));
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.updateByRestaurant("r1", "o1", OrderStatus.ACCEPTED);

        assertThat(response.status()).isEqualTo(OrderStatus.ACCEPTED);
        ArgumentCaptor<OrderEvent> event = ArgumentCaptor.forClass(OrderEvent.class);
        verify(eventBus).publish(event.capture());
        assertThat(event.getValue().status()).isEqualTo(OrderStatus.ACCEPTED);
        assertThat(event.getValue().at()).isEqualTo(NOW);
        assertThat(event.getValue().sequence()).isEqualTo(2);
    }

    @Test
    void orderFromAnotherRestaurantIsNotFound() {
        when(orderRepository.findByIdAndRestaurantId("o1", "other")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateByRestaurant("other", "o1", OrderStatus.ACCEPTED))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(eventBus, never()).publish(any());
    }

    @Test
    void losingARaceReJudgesTheChangeAgainstTheWinnersResult() {
        // Customer read the order while PLACED, but the restaurant accepted it before the cancel was saved
        Order acceptedMeanwhile = placedOrder();
        acceptedMeanwhile.transitionTo(OrderStatus.ACCEPTED, OrderActor.RESTAURANT, NOW);
        when(orderRepository.findByIdAndUserId("o1", "u1"))
                .thenReturn(Optional.of(placedOrder()))
                .thenReturn(Optional.of(acceptedMeanwhile));
        when(orderRepository.save(any())).thenThrow(new OptimisticLockingFailureException("version changed"));

        assertThatThrownBy(() -> service.cancelByCustomer("u1", "o1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE));
        verify(eventBus, never()).publish(any());
    }

    @Test
    void restaurantCantSkipSteps() {
        when(orderRepository.findByIdAndRestaurantId("o1", "r1")).thenReturn(Optional.of(placedOrder()));

        assertThatThrownBy(() -> service.updateByRestaurant("r1", "o1", OrderStatus.DELIVERED))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
        verify(orderRepository, never()).save(any());
    }

    private static Order placedOrder() {
        return Order.builder().userId("u1").idempotencyKey("k1").restaurantId("r1").restaurantName("Dosa Junction")
                .lines(List.of()).subtotal(BigDecimal.ZERO).deliveryFee(BigDecimal.ZERO).tax(BigDecimal.ZERO)
                .total(BigDecimal.ZERO).deliveryAddress(new DeliveryAddress("Home", 26.21, 78.18))
                .placedAt(NOW.minusSeconds(60)).build();
    }
}
