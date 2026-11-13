package com.obraviva.platform.reports;

import com.obraviva.platform.reports.ReportDtos.ApproveReportRequest;
import com.obraviva.platform.reports.ReportDtos.GenerateReportRequest;
import com.obraviva.platform.reports.ReportDtos.RejectReportRequest;
import com.obraviva.platform.reports.ReportDtos.ReportDetailResponse;
import com.obraviva.platform.reports.ReportDtos.ReportSummaryResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.obraviva.platform.auth.CurrentUser.userId;

@RestController
@RequestMapping("/api")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    // --- Alta ---

    @PostMapping("/developments/{developmentId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportDetailResponse generate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId,
            @Valid @RequestBody GenerateReportRequest request) {
        return reportService.generate(userId(jwt), developmentId, request);
    }

    // --- Consulta ---

    @GetMapping("/developments/{developmentId}/reports")
    public List<ReportSummaryResponse> listByDevelopment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId) {
        return reportService.listByDevelopment(userId(jwt), developmentId);
    }

    @GetMapping("/reports/pending-review")
    public List<ReportSummaryResponse> listPendingReview(@AuthenticationPrincipal Jwt jwt) {
        return reportService.listPendingReview(userId(jwt));
    }

    @GetMapping("/reports/{reportId}")
    public ReportDetailResponse detail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID reportId) {
        return reportService.detail(userId(jwt), reportId);
    }

    // --- Revisión ---

    @PostMapping("/reports/{reportId}/approve")
    public ReportDetailResponse approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID reportId,
            @Valid @RequestBody ApproveReportRequest request) {
        return reportService.approve(userId(jwt), reportId, request);
    }

    @PostMapping("/reports/{reportId}/reject")
    public ReportDetailResponse reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID reportId,
            @Valid @RequestBody RejectReportRequest request) {
        return reportService.reject(userId(jwt), reportId, request);
    }
}