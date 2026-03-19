package com.openbash.forja.util;

import okhttp3.OkHttpClient;

import java.util.concurrent.TimeUnit;

public final class HttpUtil {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    private HttpUtil() {}

    public static OkHttpClient getClient() {
        return CLIENT;
    }
}
