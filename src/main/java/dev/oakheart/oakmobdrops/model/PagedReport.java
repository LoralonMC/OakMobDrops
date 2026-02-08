package dev.oakheart.oakmobdrops.model;

import dev.oakheart.oakmobdrops.DropStatistics;

import java.util.List;

/**
 * A page of a statistics report with pagination metadata.
 */
public record PagedReport(List<String> lines, int currentPage, int totalPages,
                           DropStatistics.ReportType type) {
}
