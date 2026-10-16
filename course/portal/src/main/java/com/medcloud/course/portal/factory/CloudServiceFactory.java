package com.medcloud.course.portal.factory;

import com.medcloud.course.contract.Role;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class CloudServiceFactory {
    public PortalFacade forRole(Role role) {
        if (role == null) {
            throw new IllegalArgumentException("role required");
        }
        switch (role) {
            case PATIENT:
                return facade("/patient", "患者门户", "view-slots", "book", "view-record", "view-order", "pay", "view-files", "upload-files", "delete-files", "download-files", "supplement-complaint", "view-record-details");
            case DOCTOR:
                return facade("/doctor", "医生门户", "view-appointments", "start", "complete", "view-files", "upload-files", "download-files", "save-draft", "view-record-details", "append-correction");
            case HOSPITAL_ADMIN:
                return facade("/hospital", "医院门户", "view-slots", "view-statistics");
            case PLATFORM_ADMIN:
                return facade("/platform", "平台门户", "view-overview", "manage-tenants", "view-statistics");
            default:
                throw new IllegalArgumentException("unsupported role");
        }
    }

    private static PortalFacade facade(final String path, final String title, String... actions) {
        final Set<String> permitted = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(actions)));
        return new PortalFacade() {
            public String getHomePath() { return path; }
            public String getTitle() { return title; }
            public boolean allows(String action) { return permitted.contains(action); }
        };
    }
}
