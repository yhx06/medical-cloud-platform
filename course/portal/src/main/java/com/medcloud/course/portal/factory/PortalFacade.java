package com.medcloud.course.portal.factory;

public interface PortalFacade {
    String getHomePath();
    String getTitle();
    boolean allows(String action);
}
