package com.obraviva.platform.developments;

import com.obraviva.platform.developments.DashboardDtos.DashboardResponse;
import com.obraviva.platform.developments.ExpenseDtos.CreateExpenseRequest;
import com.obraviva.platform.developments.ExpenseDtos.ExpenseResponse;
import com.obraviva.platform.developments.ExpenseDtos.UpdateExpenseRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class DashboardController {

    private final DashboardService dashboardService;
    private final ExpenseService expenseService;

    public DashboardController(DashboardService dashboardService, ExpenseService expenseService) {
        this.dashboardService = dashboardService;
        this.expenseService = expenseService;
    }

    @GetMapping("/developments/{developmentId}/dashboard")
    public DashboardResponse dashboard(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) UUID unitId) {
        return dashboardService.dashboard(userId(jwt), developmentId, unitId);
    }

    @PostMapping("/developments/{developmentId}/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseResponse createExpense(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId,
            @Valid @RequestBody CreateExpenseRequest request) {
        return expenseService.create(userId(jwt), developmentId, request);
    }

    @GetMapping("/developments/{developmentId}/expenses")
    public List<ExpenseResponse> listExpenses(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID developmentId) {
        return expenseService.listByDevelopment(userId(jwt), developmentId);
    }

    @PatchMapping("/expenses/{expenseId}")
    public ExpenseResponse updateExpense(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID expenseId,
            @Valid @RequestBody UpdateExpenseRequest request) {
        return expenseService.update(userId(jwt), expenseId, request);
    }

    @DeleteMapping("/expenses/{expenseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteExpense(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID expenseId) {
        expenseService.delete(userId(jwt), expenseId);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}