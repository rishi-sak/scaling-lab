
package com.rishi.scalinglab;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BenchmarkController {

    @GetMapping("/api/benchmark")
    public BenchmarkResponse benchmark(
            @RequestParam(defaultValue = "100000") int iterations) {

        if (iterations < 1 || iterations > 2_000_000) {
            throw new IllegalArgumentException(
                    "iterations must be between 1 and 2000000");
        }

        long start = System.nanoTime();
        double result = 0;

        for (int i = 1; i <= iterations; i++) {
            result += Math.sqrt(i) * Math.sin(i);
        }

        long durationNanos = System.nanoTime() - start;

        return new BenchmarkResponse(
                iterations,
                result,
                durationNanos / 1_000_000.0
        );
    }

    public record BenchmarkResponse(
            int iterations,
            double result,
            double processingTimeMs
    ) {

    }

}
