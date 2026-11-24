# Model retry example

Scope: only the model-retries item in
[ARGI #372](https://github.com/agentic-ai-java/argi/issues/372).
See the Chinese and English `model-retry` website guides for the retry contract.
This fixture is intentionally not a reusable provider adapter.

## Reproduce

Use JDK 17+ and Maven 3.9.1+. Build Core commit
`c6e453f20fcb1c7837d92b321aa18ec5a46ccab6` first, not an arbitrary artifact with
the same `2.1.0-RC2-SNAPSHOT` version:

```bash
./mvnw -B -pl :argi-agent-framework -am install -DskipTests
```

Then, from the website repository:

```bash
mvn -B -f examples/model-retry/pom.xml compile exec:java
```

After dependencies/plugins are cached, the same command works with `-o`.
`ModelRetryInterceptor` ships in `argi-agent-framework`, so this example depends on
that module even though it drives the independent `ReActAgent`. No credentials,
provider adapters or Extensions artifacts are used. Backoff delays are set to zero
except in the deadline scenario, so the run takes about a second.

## Validation record

Checked 2026-11-24 on macOS arm64 using Eclipse Temurin JDK 21.0.13, Java release
target 17 and Maven 3.9.9. JDK 17 runtime execution was not tested.
Website base: `7974c3b40392722557a4404210cfb6cb4a975a16`.
Core base: `c6e453f20fcb1c7837d92b321aa18ec5a46ccab6`.

- Core dependency installation passed using
  `mvn -B -pl :argi-agent-framework -am install -DskipTests -Dcheckstyle.skip -Dspring-javaformat.skip`.
- These targeted Core checks passed:

  ```bash
  mvn -B -pl :argi-agent-framework test \
    -Dtest='ModelRetry*Test,ModelFallbackSwitchBoundaryTest' -Dsurefire.failIfNoSpecifiedTests=false
  mvn -B -pl :argi-agent-api test \
    -Dtest=ModelRetryControllerTest -Dsurefire.failIfNoSpecifiedTests=false
  ```

  Result: 50 and 12 tests, zero failures/errors/skips. This is a targeted test run,
  not a full Core test-suite claim.
- The example's `compile exec:java` passed, then passed again with Maven `-o`.
  Assertions cover transient recovery and call count, the attempt bound, type-based
  classification not reading message text, the legacy message predicate, a custom
  `RATE_LIMITED` classifier, and the deadline stopping a retry before another call.
- Website `npm run typecheck`, `npm run lint`, `npm run verify:contract` and
  `npm run build` passed. The build includes both `zh-Hans` and `en`, with broken
  Markdown links treated as errors, followed by search indexing. The pre-existing
  contract checker still contains old API literal checks; its pass is not used as
  proof of Java compatibility. The compiled example is.

No live-provider, streaming, cancellation or graph-based `ReactAgent` validation is
claimed by this example. Other #372 capabilities and the site-wide onboarding
migration remain out of scope.
