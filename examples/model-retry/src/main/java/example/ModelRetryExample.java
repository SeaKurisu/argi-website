/*
 * Copyright 2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package example;

import io.github.agentic.ai.agent.*;
import io.github.agentic.ai.agent.orchestration.interceptor.modelretry.ModelRetryInterceptor;
import io.github.agentic.ai.agent.react.ReActAgent;
import io.github.agentic.ai.agent.retry.*;
import io.github.agentic.ai.api.chat.*;
import io.github.agentic.ai.runtime.execution.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline executable example: no provider adapter, network, or credentials. */
public final class ModelRetryExample {

    /** A provider-specific failure that a custom classifier maps to RATE_LIMITED. */
    static final class ProviderRateLimitException extends RuntimeException {
        ProviderRateLimitException() { super("429 from provider"); }
    }

    /** Scripted client: each call takes the next step; a step is a failure or a reply. */
    static final class ScriptedClient implements ChatClient {
        final Queue<RuntimeException> failures;
        final AtomicInteger calls = new AtomicInteger();
        ScriptedClient(List<RuntimeException> failures) { this.failures = new ArrayDeque<>(failures); }
        public CompletionStage<ChatResponse> call(ChatRequest request) {
            calls.incrementAndGet();
            RuntimeException next;
            synchronized (failures) { next = failures.poll(); }
            if (next != null) return CompletableFuture.failedFuture(next);
            return CompletableFuture.completedFuture(new ChatResponse(null, "offline",
                    List.of(ChatMessage.assistant("ok", List.of())), List.of(), FinishReason.STOP,
                    new Usage(1L, 1L, 2L), Map.of()));
        }
        public ChatClientCapabilities capabilities() { return ChatClientCapabilities.basic(); }
        public Flow.Publisher<ChatEvent> stream(ChatRequest request) {
            throw new UnsupportedOperationException("this example uses call()");
        }
    }

    static AgentExecutionContext context(String runId, Instant deadline) {
        var trusted = TrustedExecutionContext.builder()
                .tenant(new TenantReference("example", "tenant"))
                .principal(new PrincipalReference("example", "user"))
                .runId(runId).correlationId(runId)
                .budget(ExecutionBudget.unlimited()).delegationScope(DelegationScope.none())
                .deadline(deadline).build();
        return new AgentExecutionContext(trusted, Clock.systemUTC());
    }

    /** Runs the agent once and returns the terminal failure, or null on success. */
    static Throwable run(ReActAgent agent, String runId, Instant deadline) throws Exception {
        try (var context = context(runId, deadline)) {
            AgentOutcome outcome = agent.invoke(AgentRequest.messages(List.of(ChatMessage.user("hi"))), context)
                    .get(10, TimeUnit.SECONDS);
            check(outcome.status() == AgentOutcome.Status.COMPLETED, "unexpected status " + outcome.status());
            return null;
        } catch (ExecutionException error) {
            return error.getCause();
        }
    }

    static ModelRetryInterceptor.Builder retry() {
        // initialDelay(0) keeps the example fast; production code keeps a real backoff.
        return ModelRetryInterceptor.builder().maxAttempts(3).initialDelay(0)
                .failureClassifier(new TypeBasedModelCallFailureClassifier());
    }

    static ReActAgent agent(ScriptedClient client, ModelRetryInterceptor retry) {
        return ReActAgent.builder(client).interceptors(List.of(retry)).build();
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void expectFailure(Throwable failure, String messagePart, Class<?> cause) {
        check(failure != null, "expected a failure");
        check(failure.getMessage() != null && failure.getMessage().contains(messagePart),
                "unexpected failure message: " + failure.getMessage());
        check(cause.isInstance(failure.getCause()), "provider failure not kept as cause: " + failure.getCause());
    }

    public static void main(String[] args) throws Exception {
        // 1. Transient failures (IOException) are retried; maxAttempts counts the first call.
        var transientThenOk = new ScriptedClient(List.of(
                new UncheckedIOException(new IOException("reset")), new UncheckedIOException(new IOException("reset"))));
        check(run(agent(transientThenOk, retry().build()), "transient", null) == null, "should recover");
        check(transientThenOk.calls.get() == 3, "expected 3 calls, got " + transientThenOk.calls.get());

        // 2. Attempts are bounded: a third transient failure ends the sequence.
        var alwaysTransient = new ScriptedClient(List.of(new UncheckedIOException(new IOException("a")),
                new UncheckedIOException(new IOException("b")), new UncheckedIOException(new IOException("c"))));
        expectFailure(run(agent(alwaysTransient, retry().build()), "exhausted", null),
                "maximum number of retries", UncheckedIOException.class);
        check(alwaysTransient.calls.get() == 3, "expected 3 calls, got " + alwaysTransient.calls.get());

        // 3. The type based classifier does not read message text: an unclassified
        //    failure is UNKNOWN and not retried, even when its message says "timeout".
        var permanent = new ScriptedClient(List.of(new IllegalArgumentException("invalid timeout parameter")));
        expectFailure(run(agent(permanent, retry().build()), "permanent", null),
                "non-retryable", IllegalArgumentException.class);
        check(permanent.calls.get() == 1, "UNKNOWN must not be retried");

        // 4. Without a classifier the legacy message predicate decides, so the same
        //    permanent failure IS repeated because its text contains "timeout".
        var legacy = new ScriptedClient(List.of(new IllegalArgumentException("invalid timeout parameter"),
                new IllegalArgumentException("invalid timeout parameter"),
                new IllegalArgumentException("invalid timeout parameter")));
        run(agent(legacy, ModelRetryInterceptor.builder().maxAttempts(3).initialDelay(0).build()), "legacy", null);
        check(legacy.calls.get() == 3, "legacy predicate should retry by message text");

        // 5. A custom classifier maps a provider exception to RATE_LIMITED, which is
        //    retryable by default.
        ModelCallFailureClassifier classifier = failure -> failure instanceof ProviderRateLimitException
                ? new ModelCallFailure(ModelCallFailureCategory.RATE_LIMITED, failure)
                : new TypeBasedModelCallFailureClassifier().classify(failure);
        var rateLimited = new ScriptedClient(List.of(new ProviderRateLimitException()));
        check(run(agent(rateLimited, retry().failureClassifier(classifier).build()), "rate", null) == null,
                "rate limit should be retried");
        check(rateLimited.calls.get() == 2, "expected 2 calls, got " + rateLimited.calls.get());

        // 6. The run deadline wins: a backoff that would outlive it stops the sequence
        //    before another provider call.
        var slow = new ScriptedClient(List.of(new UncheckedIOException(new IOException("reset"))));
        Instant deadline = Instant.now().plusMillis(300);
        expectFailure(run(agent(slow, retry().initialDelay(1000).build()), "deadline", deadline),
                "deadline", UncheckedIOException.class);
        check(slow.calls.get() == 1, "no call may start after the deadline decision");

        System.out.println("PASS: transient retry, attempt bound, UNKNOWN not retried, legacy predicate,"
                + " custom classifier, deadline stop");
    }
}
