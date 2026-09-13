package com.medcloud.platform.identity.api;

import com.medcloud.platform.common.api.ApiResponse;
import com.medcloud.platform.common.web.RequestIdFilter;
import com.medcloud.platform.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/patient/profile")
public class PatientProfileController {
    @GetMapping
    @PreAuthorize("hasRole('PATIENT')")
    ApiResponse<PatientProfileResponse> profile(
            @AuthenticationPrincipal CurrentUser user,
            HttpServletRequest request
    ) {
        return ApiResponse.success(
                new PatientProfileResponse(user.userId(), user.username()),
                request.getAttribute(RequestIdFilter.ATTRIBUTE_NAME).toString());
    }

    record PatientProfileResponse(UUID userId, String username) {
    }
}
