package com.hcl.observability.sftp;

public final class SftpProfileContext {

    public static final String DEFAULT = "default";
    public static final String RABBIT_NORDICS = "rabbit-nordics";
    public static final String APIGEE_REST = "apigee-rest";

    private static final ThreadLocal<String> ACTIVE_PROFILE = ThreadLocal.withInitial(() -> DEFAULT);

    private SftpProfileContext() {
    }

    public static String currentProfile() {
        String profile = ACTIVE_PROFILE.get();
        return profile == null || profile.trim().isEmpty() ? DEFAULT : profile;
    }

    public static Scope use(String profile) {
        String previous = currentProfile();
        ACTIVE_PROFILE.set(normalize(profile));
        return new Scope(previous);
    }

    public static boolean isRabbitNordics() {
        return RABBIT_NORDICS.equalsIgnoreCase(currentProfile());
    }

    public static boolean isApigeeRest() {
        return APIGEE_REST.equalsIgnoreCase(currentProfile());
    }

    private static String normalize(String profile) {
        return profile == null || profile.trim().isEmpty() ? DEFAULT : profile.trim().toLowerCase();
    }

    public static final class Scope implements AutoCloseable {
        private final String previous;
        private boolean closed;

        private Scope(String previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (!closed) {
                ACTIVE_PROFILE.set(previous);
                closed = true;
            }
        }
    }
}
