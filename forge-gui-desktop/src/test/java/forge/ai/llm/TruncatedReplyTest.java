package forge.ai.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * What happens when a model runs out of completion budget before it answers.
 *
 * <p>A reasoning model emits its chain-of-thought as completion tokens. Given
 * 1024 of them it can spend every one thinking and never reach the structured
 * answer the prompt asked for. The provider reports that honestly —
 * {@code finish_reason: "length"} — and the reply arrives in one of two shapes:
 * the reasoning in its own field with no {@code content} key at all, or the
 * reasoning dumped into {@code content} and cut off mid-word.
 *
 * <p>Both shapes used to end badly. The first raised a NullPointerException.
 * The second was worse: it looked like a normal reply, so the parser read it,
 * found small integers in the unfinished sentences, and handed them back as the
 * model's decision. Those calls were counted as successes, so they never
 * reached the fallback counter that {@code FORGE_LLM_STRICT} and the
 * degraded-run status are computed from — a run could report a clean
 * measurement while a fifth of its plays were numbers scraped out of prose.
 *
 * <p>The fixtures below are the real thing: response text captured from a
 * traced two-game match, one call per fixture, each one 1024 completion tokens
 * long and each one stopping mid-sentence. The plan named beside each is what
 * the old text scraper made of it.
 */
public class TruncatedReplyTest {

    // ---- fixtures: real truncated replies, with the plan each used to yield --

    /** Ended "…We could use a mana ability like sacrificing something". Read as [1, 2, 3, 0]. */
    private static final String CUT_OFF_WEIGHING_TOKENS =
            "We need to decide what to do in main phase. We have 2R mana (two red mana). "
            + "Options: we can activate Blood Tokens (cost {1} and tap). Each token costs 1 mana "
            + "and tap, then discard a card, sacrifice token to draw a card. We have 2R, so we "
            + "could activate up to two tokens (cost 2 mana). We have three t";

    /** Ended mid-word on madness costs. Read as [3, 1, 2]. */
    private static final String CUT_OFF_COMPARING_COSTS =
            "We need to decide what to cast in MAIN1. We have 3R mana (3 total). Options: "
            + "Grab the Prize costs {1}{R} (2 mana). Melded Moxite costs {1}{R} (2). Blood Token "
            + "activation costs {1}, {T}, discard a card, sacrifice token: draw a card. That's an "
            + "ability, not a spell; but it's listed as option 2 (Blood T";

    /** Ended mid-word on mana accounting. Read as [1, 2]. */
    private static final String CUT_OFF_ON_MANA =
            "We need to decide what to cast in main1. We have 2R mana. Options: Grab the Prize "
            + "costs {1}{R} (2 mana). Blood Token activation costs {1}, {T}, discard a card, "
            + "sacrifice token: draw a card. That's an ability, not a spell; but option 1 is "
            + "\"Blood Token [Artifact - Blood] - ...\" maybe they mean activat";

    /**
     * The nastiest shape: the model wrote the word PASS while listing what it
     * could do. The token walk stopped there and returned an empty plan — which
     * is not even a fallback, it is "the model chose to hold everything".
     */
    private static final String CUT_OFF_AFTER_WRITING_PASS =
            "We need to decide what to cast in MAIN2. We have 3R mana available. Options: "
            + "Faithless Looting (cost R), Melded Moxite (cost 1R), or PASS.\n\n"
            + "We have hand: Lava Dart, Lightning Bolt, 2x Fireblast, Faithless Looting, "
            + "Melded Moxite. We have a Blood Token (can sacrifice for draw). We have Sneaky Snacker ";

    /** Ended "…Thus answer: [0,1].\n\nWe need". An answer label, still no answer. */
    private static final String CUT_OFF_AFTER_SAYING_ANSWER =
            "We need to decide actions in MAIN2. We have 2R mana (two red mana). We have two "
            + "Blood Tokens on battlefield, each can be activated for {1}, {T}, discard a card, "
            + "sacrifice token: draw a card. So we could activate both. "
            + "Thus answer: [0,1].\n\nWe need";

    private static final String[] TRUNCATED_REPLIES = {
            CUT_OFF_WEIGHING_TOKENS, CUT_OFF_COMPARING_COSTS, CUT_OFF_ON_MANA,
            CUT_OFF_AFTER_WRITING_PASS, CUT_OFF_AFTER_SAYING_ANSWER,
    };

    // ---- the parser refuses to invent a decision ---------------------------

    @Test
    public void aTruncatedReplyIsUnreadableAndNotAPlan() {
        for (String reply : TRUNCATED_REPLIES) {
            Assert.assertNull(ResponseParser.parsePlanSequence(reply, 4),
                    "a plan was invented from a reply that never answered: " + head(reply));
        }
    }

    @Test
    public void aTruncatedReplyIsUnreadableAndNotAnIndexSet() {
        for (String reply : TRUNCATED_REPLIES) {
            Assert.assertNull(ResponseParser.parseBatchIndices(reply, 4),
                    "an index set was invented from a reply that never answered: " + head(reply));
        }
    }

    @Test
    public void aTruncatedReplyIsUnreadableAndNotAChoice() {
        for (String reply : TRUNCATED_REPLIES) {
            Assert.assertEquals(ResponseParser.parseChoiceIndex(reply, 4), -1,
                    "an option was invented from a reply that never answered: " + head(reply));
        }
    }

    /**
     * The empty plan has to survive, or the fix trades one wrong reading for
     * another: every deliberate hold would become a fallback and the rate would
     * stop meaning anything again.
     */
    @Test
    public void realAnswersStillParse() {
        Assert.assertEquals(
                ResponseParser.parsePlanSequence("{\"reasoning\":\"holding\",\"plan\":[]}", 8),
                java.util.Collections.emptyList());
        Assert.assertEquals(ResponseParser.parsePlanSequence("PASS", 8),
                java.util.Collections.emptyList());
        Assert.assertEquals(ResponseParser.parsePlanSequence("2,0,PASS", 8),
                java.util.Arrays.asList(2, 0));
        Assert.assertEquals(ResponseParser.parsePlanSequence("PLAN: 2, 0", 8),
                java.util.Arrays.asList(2, 0));
        Assert.assertEquals(ResponseParser.parseBatchIndices("NONE", 4),
                java.util.Collections.emptySet());
        Assert.assertEquals(ResponseParser.parseBatchIndices("0,2", 4),
                new java.util.LinkedHashSet<>(java.util.Arrays.asList(0, 2)));
        Assert.assertEquals(ResponseParser.parseChoiceIndex("2", 4), 2);
        Assert.assertEquals(ResponseParser.parseChoiceIndex("CHOICE: 2", 4), 2);
        Assert.assertEquals(ResponseParser.parseChoiceIndex("{\"choice\":2}", 4), 2);
        // A chatty model that still commits, with the answer on its own last line.
        Assert.assertEquals(ResponseParser.parseChoiceIndex("Keeping this hand.\n0", 4), 0);
    }

    /**
     * An index inside a sentence is not an answer, even when the sentence is
     * complete and reads like one. This is the rule that makes the fallback
     * rate mean what it is assumed to mean, and it costs a real capability:
     * a model that ignores the schema and answers in prose now falls back
     * instead of being guessed at.
     */
    @Test
    public void proseIsNeverMinedForDigits() {
        Assert.assertNull(ResponseParser.parsePlanSequence(
                "I am at 20 life, so I will hold everything this turn.", 8));
        Assert.assertNull(ResponseParser.parsePlanSequence(
                "Cast option 1 first, then option 2.", 8));
        Assert.assertNull(ResponseParser.parseBatchIndices(
                "Attack with the 2/2 and keep the 1/1 back.", 4));
        Assert.assertEquals(ResponseParser.parseChoiceIndex(
                "The 2/3 blocks well, so I would rather keep the hand.", 4), -1);
    }

    // ---- the client answers the truncation instead of guessing at names ----

    /**
     * {@code gpt-oss-120b} matches none of the name patterns in
     * {@link LLMConfig#isThinkingModel()} and reasons for three to four
     * thousand tokens anyway. So the budget is settled by what comes back: the
     * first reply that stops at the ceiling causes one retry at the large
     * budget, and every later call for that model starts there.
     */
    @Test
    public void aReplyCutOffAtTheCeilingIsRetriedAtALargerOne() throws Exception {
        StubUpstream upstream = new StubUpstream();
        upstream.replies.add(truncatedReply());
        upstream.replies.add(contentReply("{\"reasoning\":\"go face\",\"plan\":[0]}"));
        upstream.start();
        try {
            LLMClient client = clientAgainst(upstream, "stub-reasoner-120b");
            String answer = client.chatCompletion("system", "user", "mainPhasePlan", "Ai(1)");
            Assert.assertEquals(answer, "{\"reasoning\":\"go face\",\"plan\":[0]}");

            Assert.assertEquals(upstream.requests.size(), 2,
                    "the truncated reply should have been retried exactly once");
            Assert.assertEquals(maxTokensOf(upstream.requests.get(0)), 1024);
            Assert.assertEquals(maxTokensOf(upstream.requests.get(1)), 16384,
                    "the retry has to raise the ceiling or it will truncate again");
            // One logical decision, however many HTTP exchanges it took.
            Assert.assertEquals(client.getTotalCalls(), 1);
        } finally {
            upstream.stop();
        }
    }

    /** The lesson is remembered, so the second decision does not pay for it again. */
    @Test
    public void theLargerCeilingIsRememberedForTheRestOfTheSession() throws Exception {
        StubUpstream upstream = new StubUpstream();
        upstream.replies.add(truncatedReply());
        upstream.replies.add(contentReply("{\"plan\":[0]}"));
        upstream.replies.add(contentReply("{\"plan\":[1]}"));
        upstream.start();
        try {
            LLMClient client = clientAgainst(upstream, "stub-reasoner-remembering");
            client.chatCompletion("system", "user", "mainPhasePlan", "Ai(1)");
            client.chatCompletion("system", "user", "mainPhasePlan", "Ai(1)");

            Assert.assertEquals(upstream.requests.size(), 3);
            Assert.assertEquals(maxTokensOf(upstream.requests.get(2)), 16384,
                    "the second decision should start at the ceiling the first one learned");
        } finally {
            upstream.stop();
        }
    }

    /**
     * When even the larger ceiling produces no answer, the call fails as a
     * typed error naming what happened — not as a NullPointerException carrying
     * several thousand characters of chain-of-thought into every log line.
     */
    @Test
    public void aReplyWithNoContentFailsCleanly() throws Exception {
        StubUpstream upstream = new StubUpstream();
        upstream.replies.add(truncatedReply());
        upstream.replies.add(truncatedReply());
        upstream.start();
        try {
            LLMClient client = clientAgainst(upstream, "stub-reasoner-hopeless");
            client.chatCompletion("system", "user", "mainPhasePlan", "Ai(1)");
            Assert.fail("a reply with no content should not be returned as an answer");
        } catch (LLMException e) {
            Assert.assertTrue(e.getMessage().contains("no content"), e.getMessage());
            Assert.assertTrue(e.getMessage().contains("finish_reason=length"), e.getMessage());
            Assert.assertFalse(e.getMessage().contains("Now let me think"),
                    "the failure text should not carry the model's reasoning: " + e.getMessage());
        } finally {
            upstream.stop();
        }
    }

    /** Content is not an answer when the provider says it stopped at the limit. */
    @Test
    public void aContentBearingReplyStillFailsWhenTheLargeRetryIsTruncated() throws Exception {
        StubUpstream upstream = new StubUpstream();
        upstream.replies.add(truncatedReply());
        upstream.replies.add(truncatedContentReply());
        upstream.start();
        try {
            LLMClient client = clientAgainst(upstream, "stub-reasoner-still-truncated");
            client.chatCompletion("system", "user", "mainPhasePlan", "Ai(1)");
            Assert.fail("a content-bearing reply with finish_reason=length is still incomplete");
        } catch (LLMException e) {
            Assert.assertTrue(e.getMessage().contains("truncated"), e.getMessage());
            Assert.assertTrue(e.getMessage().contains("finish_reason=length"), e.getMessage());
            Assert.assertEquals(upstream.requests.size(), 2,
                    "the client should try the large budget once, then fail closed");
        } finally {
            upstream.stop();
        }
    }

    /** A 4xx that is not a rate limit is a verdict; re-sending it changes nothing. */
    @Test
    public void aRejectedRequestIsNotSentThreeMoreTimes() throws Exception {
        StubUpstream upstream = new StubUpstream();
        upstream.status = 400;
        upstream.replies.add("{\"error\":{\"message\":\"unsupported property\"}}");
        upstream.start();
        try {
            LLMClient client = clientAgainst(upstream, "stub-rejecting");
            client.chatCompletion("system", "user", "mainPhasePlan", "Ai(1)");
            Assert.fail("a 400 should surface as a failure");
        } catch (LLMException e) {
            Assert.assertEquals(upstream.requests.size(), 1,
                    "a non-retriable 400 was re-sent " + (upstream.requests.size() - 1) + " times");
        } finally {
            upstream.stop();
        }
    }

    /** HTTP 408 is transient: the server did not receive a complete request. */
    @Test
    public void aRequestTimeoutIsRetried() throws Exception {
        StubUpstream upstream = new StubUpstream();
        upstream.statuses.add(408);
        upstream.statuses.add(200);
        upstream.replies.add("{\"error\":{\"message\":\"request timeout\"}}");
        upstream.replies.add(contentReply("{\"plan\":[0]}"));
        upstream.start();
        try {
            LLMClient client = clientAgainst(upstream, "stub-timeout-once");
            Assert.assertEquals(
                    client.chatCompletion("system", "user", "mainPhasePlan", "Ai(1)"),
                    "{\"plan\":[0]}");
            Assert.assertEquals(upstream.requests.size(), 2,
                    "a 408 should be retried once and recover on the next response");
        } finally {
            upstream.stop();
        }
    }

    // ---- the prompt-caching request format ---------------------------------

    /**
     * Anthropic-style {@code cache_control} content blocks are only sent where
     * they are understood. Cerebras answers HTTP 400 to them
     * ({@code "content.str: Input should be a valid string"}), which cost one
     * real game decision on every process launch.
     */
    @Test
    public void cacheControlBlocksAreOnlySentToAnthropic() {
        Assert.assertTrue(cachingFor("relay:anthropic:claude-haiku-4.5"),
                "anthropic accepts the content-block cache hint");
        Assert.assertFalse(cachingFor("relay:cerebras:gpt-oss-120b"),
                "cerebras rejects the content-block cache hint with HTTP 400");
        Assert.assertFalse(cachingFor("relay:groq:llama-3.3-70b"));
        Assert.assertTrue(cachingFor("relay:openrouter:anthropic/claude-sonnet-4"),
                "OpenRouter accepts Anthropic-style per-block cache hints");
        Assert.assertTrue(cachingFor("direct:openrouter:anthropic/claude-sonnet-4"),
                "the direct OpenRouter Chat Completions endpoint accepts the same hints");
        Assert.assertFalse(cachingFor("direct:cerebras:gpt-oss-120b"));
        Assert.assertFalse(cachingFor("direct:openai:gpt-4.1-mini"));
    }

    private static boolean cachingFor(String profile) {
        LLMConfig config = LLMConfig.fromProfileString(profile, "k", 0.2, 30_000, false);
        Assert.assertNotNull(config, "profile did not parse: " + profile);
        return config.isPromptCachingEnabled();
    }

    // ---- helpers ------------------------------------------------------------

    private static String head(String reply) {
        return reply.substring(0, Math.min(60, reply.length())) + "…";
    }

    private static int maxTokensOf(String requestBody) {
        return JsonParser.parseString(requestBody).getAsJsonObject().get("max_tokens").getAsInt();
    }

    /** The Cerebras shape: reasoning in its own field, no {@code content} key. */
    private static String truncatedReply() {
        return "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"role\":\"assistant\","
                + "\"reasoning\":\"Now let me think about which spell to cast first. We have 2R and\"}}],"
                + "\"usage\":{\"prompt_tokens\":1300,\"completion_tokens\":1024}}";
    }

    private static String contentReply(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content);
        JsonObject choice = new JsonObject();
        choice.addProperty("finish_reason", "stop");
        choice.add("message", message);
        JsonObject usage = new JsonObject();
        usage.addProperty("prompt_tokens", 1300);
        usage.addProperty("completion_tokens", 40);
        JsonObject body = new JsonObject();
        com.google.gson.JsonArray choices = new com.google.gson.JsonArray();
        choices.add(choice);
        body.add("choices", choices);
        body.add("usage", usage);
        return body.toString();
    }

    /** An OpenRouter-style truncation: unfinished reasoning in content. */
    private static String truncatedContentReply() {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", "We have 2R mana and option 1 costs two, so perhaps");
        JsonObject choice = new JsonObject();
        choice.addProperty("finish_reason", "length");
        choice.add("message", message);
        JsonObject usage = new JsonObject();
        usage.addProperty("prompt_tokens", 1300);
        usage.addProperty("completion_tokens", 16384);
        JsonObject body = new JsonObject();
        com.google.gson.JsonArray choices = new com.google.gson.JsonArray();
        choices.add(choice);
        body.add("choices", choices);
        body.add("usage", usage);
        return body.toString();
    }

    private static LLMClient clientAgainst(StubUpstream upstream, String model) {
        return new LLMClient(new LLMConfig.Builder()
                .provider("openai-compat")
                .apiBaseUrl(upstream.baseUrl())
                .model(model)
                .temperature(0.2)
                .timeoutMs(10_000)
                .minIntervalMs(0)
                .debug(false)
                .promptCaching(false)
                .build());
    }

    /** A local OpenAI-compatible endpoint that replays a scripted list of replies. */
    private static final class StubUpstream {
        final List<String> replies = new ArrayList<>();
        final List<String> requests = new ArrayList<>();
        final List<Integer> statuses = new ArrayList<>();
        int status = 200;
        private HttpServer server;

        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", this::handle);
            server.start();
        }

        void stop() {
            if (server != null) server.stop(0);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            synchronized (requests) {
                requests.add(body);
            }
            int index = Math.min(requests.size() - 1, replies.size() - 1);
            byte[] reply = replies.get(index).getBytes(StandardCharsets.UTF_8);
            int responseStatus = statuses.isEmpty()
                    ? status : statuses.get(Math.min(index, statuses.size() - 1));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        }
    }
}
