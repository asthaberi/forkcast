package com.asthaberi.forkcast.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import com.asthaberi.forkcast.common.exception.ResourceNotFoundException;
import com.asthaberi.forkcast.order.DeliveryAddress;
import com.asthaberi.forkcast.order.Order;
import com.asthaberi.forkcast.order.OrderActor;
import com.asthaberi.forkcast.order.OrderRepository;
import com.asthaberi.forkcast.order.OrderStatus;
import com.asthaberi.forkcast.restaurant.RestaurantService;
import com.asthaberi.forkcast.review.dto.ReviewDtos.CreateReviewRequest;
import com.asthaberi.forkcast.review.dto.ReviewDtos.ReviewResponse;
import com.asthaberi.forkcast.user.Role;
import com.asthaberi.forkcast.user.User;
import com.asthaberi.forkcast.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class ReviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final CreateReviewRequest FIVE_STARS = new CreateReviewRequest(5, "  Crisp dosa  ");

    private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final RatingAggregates ratingAggregates = mock(RatingAggregates.class);
    private final ReviewService service = new ReviewService(reviewRepository, orderRepository, userRepository,
            mock(RestaurantService.class), ratingAggregates, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(userRepository.findById("u1")).thenReturn(Optional.of(
                User.create("Astha Beri", "astha@example.com", "hash", Set.of(Role.CUSTOMER), NOW)));
        when(reviewRepository.insert(any(Review.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void reviewsADeliveredOrderAndUpdatesTheRating() {
        when(orderRepository.findByIdAndUserId("o1", "u1")).thenReturn(Optional.of(order(OrderStatus.DELIVERED)));

        ReviewResponse response = service.create("u1", "o1", FIVE_STARS);

        assertThat(response.text()).isEqualTo("Crisp dosa");
        assertThat(response.authorName()).isEqualTo("Astha B.");
        assertThat(response.verifiedOrder()).isTrue();
        assertThat(response.createdAt()).isEqualTo(NOW);
        verify(ratingAggregates).addRating("r1", 5);
    }

    @Test
    void cannotReviewAnOrderBeforeItIsDelivered() {
        when(orderRepository.findByIdAndUserId("o1", "u1")).thenReturn(Optional.of(order(OrderStatus.PREPARING)));

        assertThatThrownBy(() -> service.create("u1", "o1", FIVE_STARS))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.REVIEW_NOT_ALLOWED));
        verifyNoInteractions(ratingAggregates);
    }

    @Test
    void someoneElsesOrderIsNotFound() {
        when(orderRepository.findByIdAndUserId("o1", "u2")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create("u2", "o1", FIVE_STARS)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void secondReviewOfTheSameOrderIsRejectedWithoutCountingTwice() {
        when(orderRepository.findByIdAndUserId("o1", "u1")).thenReturn(Optional.of(order(OrderStatus.DELIVERED)));
        when(reviewRepository.insert(any(Review.class))).thenThrow(new DuplicateKeyException("orderId"));

        assertThatThrownBy(() -> service.create("u1", "o1", FIVE_STARS))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.ALREADY_REVIEWED));
        verify(ratingAggregates, never()).addRating(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void showsFirstNameAndLastInitialOnly() {
        assertThat(ReviewService.firstNameAndInitial("Astha Beri")).isEqualTo("Astha B.");
        assertThat(ReviewService.firstNameAndInitial("  Astha  ")).isEqualTo("Astha");
        assertThat(ReviewService.firstNameAndInitial("Ravi Kumar Sharma")).isEqualTo("Ravi S.");
    }

    static Order order(OrderStatus status) {
        Order order = Order.builder().userId("u1").idempotencyKey("k1").restaurantId("r1")
                .restaurantName("Dosa Junction").lines(List.of()).subtotal(BigDecimal.ZERO).deliveryFee(BigDecimal.ZERO)
                .tax(BigDecimal.ZERO).total(BigDecimal.ZERO).deliveryAddress(new DeliveryAddress("Home", 26.21, 78.18))
                .placedAt(NOW.minusSeconds(3600)).build();
        for (OrderStatus next : List.of(OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.OUT_FOR_DELIVERY,
                OrderStatus.DELIVERED)) {
            if (order.getStatus() == status) {
                break;
            }
            order.transitionTo(next, OrderActor.RESTAURANT, NOW);
        }
        return order;
    }
}
