package com.example.metrics.web;

import java.time.Instant;

/**
 * One aggregated bucket in a query response.
 *
 * @param bucket bucket start instant
 * @param avg average value in the bucket
 * @param max maximum value in the bucket
 * @param min minimum value in the bucket
 * @param count raw points aggregated into the bucket
 */
public record SeriesPointDto(Instant bucket, double avg, double max, double min, long count) {
}
