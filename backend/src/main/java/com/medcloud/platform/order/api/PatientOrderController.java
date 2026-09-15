package com.medcloud.platform.order.api;

import com.medcloud.platform.common.api.ApiResponse;
import com.medcloud.platform.common.web.RequestIdFilter;
import com.medcloud.platform.order.application.MedicalOrderService;
import com.medcloud.platform.order.application.MedicalOrderService.MedicalOrderResponse;
import com.medcloud.platform.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/patient/orders")
@PreAuthorize("hasRole('PATIENT')")
public class PatientOrderController {
    private final MedicalOrderService orderService;

    public PatientOrderController(MedicalOrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    ApiResponse<List<MedicalOrderResponse>> list(
            @AuthenticationPrincipal CurrentUser patient,
            HttpServletRequest request
    ) {
        return ApiResponse.success(orderService.patientOrders(patient), requestId(request));
    }

    @GetMapping("/{id}")
    ApiResponse<MedicalOrderResponse> get(
            @AuthenticationPrincipal CurrentUser patient,
            @PathVariable UUID id,
            HttpServletRequest request
    ) {
        return ApiResponse.success(orderService.patientOrder(patient, id), requestId(request));
    }

    @PostMapping("/{id}/pay")
    ApiResponse<MedicalOrderResponse> pay(
            @AuthenticationPrincipal CurrentUser patient,
            @PathVariable UUID id,
            HttpServletRequest request
    ) {
        return ApiResponse.success(orderService.pay(patient, id), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME);
        return value == null ? "unknown" : value.toString();
    }
}
