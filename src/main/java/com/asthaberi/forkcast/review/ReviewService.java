package com.asthaberi.forkcast.review;

import java.time.Clock;
import java.util.Map;

import com.asthaberi.forkcast.common.exception.BusinessException;
import com.asthaberi.forkcast.common.exception.ErrorCode;
import com.asthaberi.forkcast.common.exception.ResourceNotFoundException;
import com.asthaberi.forkcast.common.web.PageResponse;
import com.asthaberi.forkcast.order.Order;
import com.asthaberi.forkcast.order.OrderRepository;
import com.asthaberi.forkcast.order.OrderStatus;
import com.asthaberi.forkcast.restaurant.RestaurantService;
import com.asthaberi.forkcast.review.dto.ReviewDtos.CreateReviewRequest;
import com.asthaberi.forkcast.review.dto.ReviewDtos.ReviewResponse;
import com.asthaberi.forkcast.user.User;
import com.asthaberi.forkcast.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final RestaurantService restaurantService;
    private final RatingAggregates ratingAggregates;
    private final Clock clock;

    /**
     * Only the customer who placed the order can review it, only once it was delivered, and only once.
     * So every review comes from someone who actually ate there.
     */
    public ReviewResponse create(String userId, String orderId, CreateReviewRequest request) {
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_ALLOWED,
                    "You can review an order once it has been delivered (status " + order.getStatus() + ")",
                    Map.of("status", order.getStatus()));
        }
        Review review = Review.of(order.getRestaurantId(), orderId, userId, displayName(userId), request.rating(),
                request.text().trim(), clock.instant());
        try {
            review = reviewRepository.insert(review);
        } catch (DuplicateKeyException e) {
            // Unique orderId index: also stops two simultaneous submissions for the same order
            throw new BusinessException(ErrorCode.ALREADY_REVIEWED, "This order has already been reviewed");
        }
        ratingAggregates.addRating(order.getRestaurantId(), request.rating());
        return toResponse(review);
    }

    public PageResponse<ReviewResponse> list(String restaurantId, int page, int size) {
        restaurantService.requireExists(restaurantId);
        return PageResponse.from(reviewRepository.findByRestaurantIdOrderByCreatedAtDesc(restaurantId,
                PageRequest.of(page, size)), ReviewService::toResponse);
    }

    private String displayName(String userId) {
        return userRepository.findById(userId).map(User::getName).map(ReviewService::firstNameAndInitial)
                .orElse("Customer");
    }

    /** "Astha Beri" -> "Astha B.": recognisable, without publishing a full name or email. */
    static String firstNameAndInitial(String fullName) {
        String[] parts = fullName.trim().split("\\s+");
        return parts.length == 1 ? parts[0] : parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }

    static ReviewResponse toResponse(Review review) {
        return new ReviewResponse(review.getId(), review.getRating(), review.getText(), review.getAuthorName(),
                review.isVerifiedOrder(), review.getCreatedAt());
    }
}
