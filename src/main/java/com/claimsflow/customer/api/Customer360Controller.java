package com.claimsflow.customer.api;

import com.claimsflow.customer.application.Customer360Service;
import com.claimsflow.customer.domain.Customer360View;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for the Customer bounded context (FR-05).
 */
@RestController
@RequestMapping("/api/v1/customers")
public class Customer360Controller {

    private final Customer360Service customer360Service;

    public Customer360Controller(Customer360Service customer360Service) {
        this.customer360Service = customer360Service;
    }

    /**
     * Aggregated policyholder view: claim counts by status, financial totals,
     * fraud exposure, and recent claim activity.
     */
    @GetMapping("/{policyNumber}/view")
    public Customer360View view(@PathVariable String policyNumber) {
        return customer360Service.view(policyNumber);
    }
}
