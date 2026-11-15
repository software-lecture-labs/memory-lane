package com.memorylane.core.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Converter;
import retrofit2.Retrofit;

/**
 * Wraps the JSON converter so a conversion failure can be told apart from a dropped connection.
 * The body is read completely first: if the connection breaks while reading, OkHttp's IOException
 * passes through unchanged and is reported as a network error. Only a body that was fully received
 * and still could not be converted becomes a {@link ResponseParseException}.
 *
 * <p>Buffering is fine here because converted bodies are small JSON documents. Raw
 * {@link ResponseBody} results (for example photo downloads) are handled by Retrofit's built-in
 * converters before this factory is consulted, so they are still streamed.
 */
final class ParseErrorConverterFactory extends Converter.Factory {
    private final Converter.Factory delegate;

    ParseErrorConverterFactory(Converter.Factory delegate) {
        this.delegate = delegate;
    }

    @Nullable
    @Override
    public Converter<ResponseBody, ?> responseBodyConverter(@NonNull Type type, @NonNull Annotation[] annotations,
                                                            @NonNull Retrofit retrofit) {
        Converter<ResponseBody, ?> converter = delegate.responseBodyConverter(type, annotations, retrofit);
        if (converter == null) {
            return null;
        }
        return body -> {
            // Network errors surface from this read, before any parsing starts.
            byte[] bytes = body.bytes();
            MediaType contentType = body.contentType();
            try {
                return converter.convert(ResponseBody.create(bytes, contentType));
            } catch (IOException | RuntimeException e) {
                throw new ResponseParseException(e);
            }
        };
    }

    // Request bodies and query/path values are written by us, not received; they keep the delegate's behaviour.
    @Nullable
    @Override
    public Converter<?, RequestBody> requestBodyConverter(@NonNull Type type,
                                                          @NonNull Annotation[] parameterAnnotations,
                                                          @NonNull Annotation[] methodAnnotations,
                                                          @NonNull Retrofit retrofit) {
        return delegate.requestBodyConverter(type, parameterAnnotations, methodAnnotations, retrofit);
    }

    @Nullable
    @Override
    public Converter<?, String> stringConverter(@NonNull Type type, @NonNull Annotation[] annotations,
                                                @NonNull Retrofit retrofit) {
        return delegate.stringConverter(type, annotations, retrofit);
    }
}
