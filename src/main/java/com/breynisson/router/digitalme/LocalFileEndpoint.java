package com.breynisson.router.digitalme;

public final class LocalFileEndpoint {

    private LocalFileEndpoint() {
    }

    public static boolean isLocalFileUrl(String url) {
        return url.contains("/localFile?");
    }
}
