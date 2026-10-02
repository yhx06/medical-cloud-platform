package com.medcloud.course.contract;

/** Public booking directory: deliberately excludes account and clinical fields. */
public final class CatalogOption {
    private final String id, name;
    public CatalogOption(String id, String name) { this.id = id; this.name = name; }
    public String getId() { return id; }
    public String getName() { return name; }
}
