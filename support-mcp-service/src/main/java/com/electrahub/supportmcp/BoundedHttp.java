package com.electrahub.supportmcp;

import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;

final class BoundedHttp {
    private BoundedHttp() {}
    static HttpResponse<byte[]> send(HttpClient client, HttpRequest request, int limit) throws Exception {
        var future = client.sendAsync(request, ignored -> new Body(limit));
        try {
            // A hard deadline covers headers AND all body bytes, including a stalled/chunked response.
            return future.get(request.timeout().orElseThrow().toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ex) { future.cancel(true); throw ex; }
    }
    static final class Body implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        Body(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > limit - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException("Response exceeds bound")); return;
                }
                byte[] data = new byte[chunk.remaining()]; chunk.get(data); bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
