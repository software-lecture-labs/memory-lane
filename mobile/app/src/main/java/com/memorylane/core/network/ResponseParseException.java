package com.memorylane.core.network;

import java.io.IOException;

/**
 * The response arrived in full but its body could not be converted. Thrown only by
 * {@link ParseErrorConverterFactory}, so {@link ApiCaller} can tell it apart from transport
 * failures without guessing from exception types that both Gson and OkHttp use (e.g. EOFException).
 */
final class ResponseParseException extends IOException {
    ResponseParseException(Throwable cause) {
        super("Response body could not be converted", cause);
    }
}
