package com.example.payments.merchant.api;

import java.io.IOException;
import java.net.URI;

interface MerchantCallbackTransport extends AutoCloseable {
    Result post(URI uri, String form) throws IOException, InterruptedException;

    @Override
    default void close() throws IOException {}

    record Result(int statusCode, String body) {}
}
