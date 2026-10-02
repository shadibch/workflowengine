package com.wfe.core.error;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A named resource does not exist in the caller's tenant.
 *
 * <p>Thrown from the service layer, mapped to 404 by the API's problem handler.
 * The detail is built from the resource type and the key the caller supplied,
 * which is their own input and therefore safe to echo; nothing about other
 * tenants' rows is exposed.
 */
public class ResourceNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String resourceType;
    private final String key;

    public ResourceNotFoundException(String resourceType, String key) {
        super("%s '%s' does not exist".formatted(resourceType, key));
        this.resourceType = resourceType;
        this.key = key;
    }

    public static ResourceNotFoundException definition(String key) {
        return new ResourceNotFoundException("Definition", key);
    }

    public static ResourceNotFoundException definitionVersion(String key, int versionNo) {
        return new ResourceNotFoundException("Definition version", key + "@" + versionNo);
    }

    public String resourceType() {
        return resourceType;
    }

    public String key() {
        return key;
    }

    /** Machine-readable problem properties for the RFC 9457 body. */
    public Map<String, Object> problemProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("resource", resourceType);
        properties.put("key", key);
        return properties;
    }
}
