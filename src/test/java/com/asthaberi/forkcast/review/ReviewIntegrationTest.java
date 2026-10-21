package com.asthaberi.forkcast.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import com.asthaberi.forkcast.menu.MenuItem;
import com.asthaberi.forkcast.menu.MenuItemRepository;
import com.asthaberi.forkcast.menu.SpiceLevel;
import com.asthaberi.forkcast.order.DeliveryAddress;
import com.asthaberi.forkcast.order.Order;
import com.asthaberi.forkcast.order.OrderActor;
import com.asthaberi.forkcast.order.OrderRepository;
import com.asthaberi.forkcast.order.OrderStatus;
import com.asthaberi.forkcast.restaurant.Restaurant;
import com.asthaberi.forkcast.restaurant.RestaurantRepository;
import com.asthaberi.forkcast.review.dto.ReviewDtos.CreateReviewRequest;
import com.asthaberi.forkcast.review.dto.ReviewSummaryResponse;
import com.asthaberi.forkcast.review.dto.ReviewSummaryResponse.Status;
import com.asthaberi.forkcast.review.summary.ReviewSummarizer;
import com.asthaberi.forkcast.review.summary.ReviewSummaryDraft;
import com.asthaberi.forkcast.review.summary.ReviewSummaryDraft.Sentiment;
import com.asthaberi.forkcast.review.summary.ReviewSummaryService;
import com.asthaberi.forkcast.support.TestContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * Reviews, ratings and summaries against a real MongoDB: the unique index, the atomic rating update and the
 * stored summaries only prove themselves there. The LLM is a Mockito stand-in, so tests can count its calls.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"forkcast.seed.enabled=false", "spring.cache.type=none", "spring.data.redis.port=1"})
class ReviewIntegrationTest {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = TestContainers.mongo();

    private static final ReviewSummaryDraft DRAFT = new ReviewSummaryDraft("Crisp dosas and good coffee.",
            List.of("Crisp dosa"), List.of("Slow delivery"), Sentiment.POSITIVE, List.of("masala dosa", "Dim Sum"));

    @Autowired
    private ReviewService reviewService;
    @Autowired
    private ReviewSummaryService summaryService;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private RestaurantRepository restaurantRepository;
    @Autowired
    private MenuItemRepository menuItemRepository;
    @MockitoBean
    private ReviewSummarizer summarizer;

    private Restaurant restaurant;

    @BeforeEach
    void setUp() {
        reviewRepository.deleteAll();
        orderRepository.deleteAll();
        menuItemRepository.deleteAll();
        // A new restaurant (new id) per test, so no test sees another's in-memory summary state
        restaurant = restaurantRepository.save(Restaurant.builder().name("Dosa Junction").address("Patel Nagar")
                .city("Gwalior").location(new GeoJsonPoint(78.1850, 26.2080)).cuisines(List.of("south indian"))
                .opensAt(LocalTime.MIDNIGHT).closesAt(LocalTime.MIDNIGHT).rating(4.2).build());
        menuItemRepository.save(MenuItem.builder().restaurantId(restaurant.getId()).name("Masala Dosa")
                .category("Dosas").price(new BigDecimal("129.00")).vegetarian(true).spiceLevel(SpiceLevel.MILD)
                .tags(List.of()).available(true).build());
        when(summarizer.isAvailable()).thenReturn(true);
        when(summarizer.modelName()).thenReturn("stub-model");
        when(summarizer.summarize(anyString(), anyList(), anyList())).thenReturn(DRAFT);
    }

    @Test
    void concurrentReviewsAllCountAndTheAverageIsExact() throws Exception {
        List<String> orders = deliveredOrders(20);

        // Stars 1..5 four times each: total 60, average 3.0
        List<Outcome> outcomes = runConcurrently(20,
                i -> () -> reviewService.create("user-" + i, orders.get(i), new CreateReviewRequest(i % 5 + 1, "fine")));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNull());
        Restaurant updated = restaurantRepository.findById(restaurant.getId()).orElseThrow();
        assertThat(updated.getReviewCount()).isEqualTo(20);
        assertThat(updated.getRatingTotal()).isEqualTo(60);
        assertThat(updated.getRating()).isEqualTo(3.0);
    }

    @Test
    void anOrderCanBeReviewedOnlyOnceEvenWhenSubmittedTwiceAtOnce() throws Exception {
        String order = deliveredOrders(1).getFirst();

        List<Outcome> outcomes = runConcurrently(5,
                i -> () -> reviewService.create("user-0", order, new CreateReviewRequest(4, "double tap")));

        assertThat(outcomes).filteredOn(outcome -> outcome.error() == null).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome.error() != null)
                .allSatisfy(outcome -> assertThat(outcome.error().code()).isEqualTo(ErrorCode.ALREADY_REVIEWED));
        assertThat(reviewRepository.count()).isEqualTo(1);
        assertThat(restaurantRepository.findById(restaurant.getId()).orElseThrow().getReviewCount()).isEqualTo(1);
    }

    @Test
    void noSummaryAndNoLlmCallBelowThreeReviews() {
        addReviews(2);

        ReviewSummaryResponse response = summaryService.get(restaurant.getId());

        assertThat(response.status()).isEqualTo(Status.NOT_ENOUGH_REVIEWS);
        assertThat(response.reviewCount()).isEqualTo(2);
        assertThat(response.starDistribution()).containsEntry(4, 2).containsEntry(5, 0);
        verify(summarizer, never()).summarize(any(), any(), any());
    }

    @Test
    void summaryIsWrittenOnceThenServedFromTheDatabase() {
        addReviews(3);

        ReviewSummaryResponse first = summaryService.get(restaurant.getId());
        ReviewSummaryResponse second = summaryService.get(restaurant.getId());

        assertThat(first.status()).isEqualTo(Status.READY);
        assertThat(first.summary()).isEqualTo("Crisp dosas and good coffee.");
        // Grounded in the menu: the hallucinated "Dim Sum" is dropped, the spelling is the menu's
        assertThat(first.mentionedDishes()).containsExactly("Masala Dosa");
        assertThat(first.averageRating()).isEqualTo(4.0);
        assertThat(first.basedOnReviews()).isEqualTo(3);
        assertThat(first.model()).isEqualTo("stub-model");
        assertThat(second.stale()).isFalse();
        assertThat(second.generatedAt()).isEqualTo(first.generatedAt());
        verify(summarizer, times(1)).summarize(any(), any(), any());
    }

    @Test
    void simultaneousFirstRequestsShareOneLlmCall() throws Exception {
        addReviews(3);
        when(summarizer.summarize(anyString(), anyList(), anyList())).thenAnswer(call -> {
            Thread.sleep(300);
            return DRAFT;
        });

        List<ReviewSummaryResponse> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            CyclicBarrier startTogether = new CyclicBarrier(10);
            List<Future<ReviewSummaryResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                futures.add(pool.submit(() -> {
                    startTogether.await();
                    return summaryService.get(restaurant.getId());
                }));
            }
            for (Future<ReviewSummaryResponse> future : futures) {
                responses.add(future.get());
            }
        }

        assertThat(responses).allSatisfy(response -> assertThat(response.status()).isEqualTo(Status.READY));
        verify(summarizer, times(1)).summarize(any(), any(), any());
    }

    @Test
    void afterThreeNewReviewsTheOldSummaryIsServedWhileANewOneIsWritten() throws InterruptedException {
        addReviews(3);
        summaryService.get(restaurant.getId());
        addReviews(3);

        ReviewSummaryResponse stale = summaryService.get(restaurant.getId());

        assertThat(stale.stale()).isTrue();
        assertThat(stale.basedOnReviews()).isEqualTo(3);
        assertThat(stale.reviewCount()).isEqualTo(6); // the numbers are always current
        awaitTrue(() -> summaryService.get(restaurant.getId()).basedOnReviews() == 6, "background refresh");
        assertThat(summaryService.get(restaurant.getId()).stale()).isFalse();
        verify(summarizer, times(2)).summarize(any(), any(), any());
    }

    @Test
    void withoutAnApiKeyTheFirstSummaryIsA503() {
        addReviews(3);
        when(summarizer.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> summaryService.get(restaurant.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.AI_SUMMARY_UNAVAILABLE));
    }

    @Test
    void afterAnLlmFailureTheRestaurantIsNotRetriedOnEveryRequest() {
        addReviews(3);
        when(summarizer.summarize(anyString(), anyList(), anyList())).thenThrow(new IllegalStateException("quota"));

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> summaryService.get(restaurant.getId()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.AI_SUMMARY_UNAVAILABLE));
        }
        verify(summarizer, times(1)).summarize(any(), any(), any());
    }

    private void addReviews(int count) {
        List<String> orders = deliveredOrders(count);
        for (int i = 0; i < count; i++) {
            reviewService.create("user-" + i, orders.get(i), new CreateReviewRequest(4, "Good food"));
        }
    }

    private List<String> deliveredOrders(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Instant now = Instant.now();
            Order order = Order.builder().userId("user-" + i).idempotencyKey("key-" + System.nanoTime())
                    .restaurantId(restaurant.getId()).restaurantName(restaurant.getName()).lines(List.of())
                    .subtotal(BigDecimal.ZERO).deliveryFee(BigDecimal.ZERO).tax(BigDecimal.ZERO).total(BigDecimal.ZERO)
                    .deliveryAddress(new DeliveryAddress("Hostel 3", 26.2120, 78.1830)).placedAt(now).build();
            for (OrderStatus next : List.of(OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.OUT_FOR_DELIVERY,
                    OrderStatus.DELIVERED)) {
                order.transitionTo(next, OrderActor.RESTAURANT, now);
            }
            ids.add(orderRepository.save(order).getId());
        }
        return ids;
    }

    private static List<Outcome> runConcurrently(int threads, IntFunction<Callable<?>> task) throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<?> call = task.apply(i);
                futures.add(pool.submit(() -> {
                    startTogether.await();
                    try {
                        call.call();
                        return new Outcome(null);
                    } catch (BusinessException e) {
                        return new Outcome(e);
                    }
                }));
            }
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get());
            }
            return outcomes;
        }
    }

    private static void awaitTrue(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }

    private record Outcome(BusinessException error) {
    }
}
