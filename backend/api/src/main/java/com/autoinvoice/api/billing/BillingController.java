package com.autoinvoice.api.billing;

import com.autoinvoice.billing.BillingEngine;
import com.autoinvoice.billing.BillingRequest;
import com.autoinvoice.billing.BillingResult;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {
    private final BillingEngine engine = new BillingEngine();

    @PostMapping("/simulate")
    @PreAuthorize("hasAuthority('pricing.publish')")
    public BillingResult simulate(@Valid @RequestBody BillingRequest request) {
        return engine.calculate(request);
    }
}

