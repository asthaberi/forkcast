package com.asthaberi.forkcast.review.summary;

import org.springframework.data.mongodb.repository.MongoRepository;

interface ReviewSummaryRepository extends MongoRepository<ReviewSummary, String> {
}
