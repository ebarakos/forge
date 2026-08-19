package forge.ai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a chosen option index from LLM response text.
 * Handles the structured JSON answer the prompts ask for, and the legacy text
 * shapes: a bare index list, an index list behind a "CHOICE:" style label, and
 * sentinel words such as "PASS" or "NONE".
 * Supports thinking model output with {@code <think>} tags.
 *
 * <h2>"Empty" and "unreadable" are different answers</h2>
 *
 * <p>The batch parsers — {@link #parsePlanSequence}, {@link #parseBatchIndices},
 * {@link #parseBatchBlockAssignments} — return {@code null} when the response
 * could not be understood at all, and an empty collection only when the model
 * really did say "nothing". Callers must treat the two differently: an empty
 * plan is the model choosing to hold, while an unreadable answer is a failed
 * call that has to be recorded as a fallback before the heuristic takes over.
 *
 * <p>Conflating them is how a run could report zero fallbacks while the
 * heuristic AI played every MAIN phase: a prose answer parsed to an empty list,
 * which looked exactly like a deliberate pass, so nothing was counted and
 * neither {@code FORGE_LLM_STRICT} nor the fallback-rate status ever fired.
 *
 * <h2>Text parsing accepts an answer, never prose</h2>
 *
 * <p>There is a second way to lose the same distinction, one layer out:
 * treating text nobody could read as text that was understood. A reasoning
 * model whose reply is cut off by the token limit sends back its
 * chain-of-thought and no answer at all. That text is full of small integers —
 * mana costs, option numbers it was still weighing, life totals — and a parser
 * that scans a whole response for the first in-range digits will find some and
 * hand them back as a decision. Nothing downstream can tell that apart from a
 * real answer, so it is not recorded as a fallback and no gate ever sees it.
 * Measured on one traced match, six of thirty-one calls were plans assembled
 * this way out of sentences that stopped mid-word.
 *
 * <p>So the text paths here accept a response only when the response <em>is</em>
 * an answer: a bare index list such as {@code "2,0,PASS"}, the same list behind
 * an explicit label such as {@code "PLAN: 2,0"}, or a sentinel word such as
 * {@code "PASS"} or {@code "NONE"} on its own. Anything else — any reply with a
 * sentence in it — is unreadable, returns {@code null} (or {@code -1}), and is
 * counted as the failed call it is.
 */
public final class ResponseParser {
    private ResponseParser() {}

    private static final Pattern THINK_PATTERN =
            Pattern.compile("<think>[\\s\\S]*?</think>", Pattern.CASE_INSENSITIVE);

    /** A {@code "reasoning": "..."} field, so its digits stay out of the text fallbacks. */
    private static final Pattern REASONING_FIELD = Pattern.compile(
            "\"reasoning\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])*\"", Pattern.CASE_INSENSITIVE);

    /** An explicit {@code TARGET:} / {@code TARGETS:} answer label. */
    private static final Pattern TARGET_LABEL = Pattern.compile(
            "TARGETS?\\s*[:=]\\s*([0-9][0-9,\\s]*)", Pattern.CASE_INSENSITIVE);

    private static final Pattern ANY_INTEGER = Pattern.compile("\\b(\\d+)\\b");

    /**
     * A bare answer: one or more indices, optionally ended by a sentinel word.
     * {@code "2"}, {@code "2, 0"}, {@code "2,0,PASS"}, {@code "PASS"},
     * {@code "NONE"}, {@code "ALL"}, {@code "N/A"} all match; a sentence does
     * not, whatever digits it contains.
     */
    private static final Pattern BARE_ANSWER = Pattern.compile(
            "(?:\\d+(?:\\s*[,+]\\s*\\d+)*(?:\\s*[,+]?\\s*(?:PASS|NONE|ALL|N/A))?"
                    + "|PASS|NONE|ALL|NO|N/A)",
            Pattern.CASE_INSENSITIVE);

    /**
     * An answer label the model may put in front of a bare answer, at the start
     * of a line. Line-initial on purpose: a model narrating its way to a
     * decision writes "…so the answer: 0,1" in the middle of a sentence, and
     * that sentence is exactly the truncated reasoning this class must not
     * mine. A legacy text answer puts its label first.
     */
    private static final Pattern ANSWER_LABEL = Pattern.compile(
            "^[ \\t]*(?:PLAN|INDICES|CHOICE|ANSWER|OPTIONS?|SELECT|ATTACKERS?)[ \\t]*[:=]?[ \\t]*",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /**
     * The part of {@code response} that is an answer, or null when none of it
     * is. Everything after an explicit label is taken, up to the end of that
     * line; with no label the whole response has to be the answer. Trailing
     * sentence punctuation is tolerated, a following sentence is not.
     *
     * <p>This is the guard that keeps a truncated chain-of-thought from being
     * read as a decision. It has to run before any digit scan, because by the
     * time digits have been collected there is no longer anything to tell a
     * mana cost in an unfinished sentence from the index the model chose.
     */
    private static String answerText(String response) {
        String s = stripThinkingTags(response);
        if (s == null) return null;
        s = s.strip();
        if (s.isEmpty()) return null;
        // The whole reply is the answer.
        String whole = bareAnswer(s);
        if (whole != null) return whole;
        // Or a line-initial label carries it on its own line.
        Matcher label = ANSWER_LABEL.matcher(s);
        while (label.find()) {
            String rest = s.substring(label.end());
            int nl = rest.indexOf('\n');
            if (nl >= 0) rest = rest.substring(0, nl);
            String found = bareAnswer(rest);
            if (found != null) return found;
        }
        return null;
    }

    /** {@code text} when the whole of it is a bare answer, else null. */
    private static String bareAnswer(String text) {
        String s = text.strip();
        // Drop trailing sentence punctuation so "2,0." still reads as an answer.
        while (!s.isEmpty() && ".;! \t".indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) return null;
        return BARE_ANSWER.matcher(s).matches() ? s : null;
    }

    /**
     * Strip {@code <think>...</think>} blocks from content.
     * Some providers embed reasoning tokens directly in the content field.
     * Also handles malformed closing tags (e.g. phi4-mini-reasoning outputs
     * {@code </atech>} instead of {@code </think>}).
     */
    public static String stripThinkingTags(String content) {
        if (content == null) return null;
        if (!content.toLowerCase().contains("<think>")) {
            return content;
        }
        // First try the well-formed pattern
        String result = THINK_PATTERN.matcher(content).replaceAll("").strip();
        // If the content still starts with <think>, the closing tag is malformed.
        // Strip from <think> to the last </...> tag, keeping only what follows it.
        if (result.toLowerCase().contains("<think>")) {
            // Find the last occurrence of any closing tag like </think>, </atech>, etc.
            int lastClose = result.lastIndexOf("</");
            if (lastClose >= 0) {
                int tagEnd = result.indexOf('>', lastClose);
                if (tagEnd >= 0) {
                    result = result.substring(tagEnd + 1).strip();
                }
            }
            // If still contains <think>, just take everything after the last newline
            if (result.toLowerCase().contains("<think>") || result.isEmpty()) {
                String[] lines = content.split("\n");
                for (int i = lines.length - 1; i >= 0; i--) {
                    String line = lines[i].strip();
                    if (!line.isEmpty() && !line.toLowerCase().contains("<think>")
                            && !line.startsWith("</")) {
                        return line;
                    }
                }
            }
        }
        return result;
    }

    /**
     * Try to parse the response body as a JSON object and return it. Returns
     * null if the body isn't a JSON object — the caller should then fall back
     * to legacy text parsers. Tolerates leading {@code <think>} blocks (already
     * stripped upstream) and common chatty preambles.
     *
     * <p>A chatty preamble really does happen: when a 400 turns structured
     * output off for the session the prompts stop saying anything about output
     * shape, and a model that still answers {@code Sure: {"targets":[1]}} is
     * giving the right answer in the wrong wrapper. So a JSON object embedded
     * anywhere in the body is found and parsed rather than thrown away.
     */
    private static JsonObject tryParseJsonObject(String response) {
        String s = jsonBody(response);
        if (s == null) return null;
        JsonObject direct = parseObject(s);
        if (direct != null) return direct;
        String embedded = firstBalancedObject(s);
        return embedded == null ? null : parseObject(embedded);
    }

    /** The response with any code fence removed, or null when there is nothing to read. */
    private static String jsonBody(String response) {
        if (response == null) return null;
        String s = response.strip();
        if (s.isEmpty()) return null;
        // Some providers wrap structured output in ```json ... ``` blocks even
        // with response_format set. Strip a leading code fence if present.
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) s = s.substring(nl + 1);
            int closing = s.lastIndexOf("```");
            if (closing >= 0) s = s.substring(0, closing);
            s = s.strip();
        }
        return s.isEmpty() ? null : s;
    }

    private static JsonObject parseObject(String s) {
        if (!s.startsWith("{")) return null;
        try {
            JsonElement el = JsonParser.parseString(s);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The first complete {@code {...}} block in the body, or null when there is
     * none or the braces never close — which is what a reply truncated by the
     * token limit looks like.
     */
    private static String firstBalancedObject(String s) {
        int start = s.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return s.substring(start, i + 1);
        }
        return null;
    }

    /**
     * Did the model try to answer in JSON and fail? A body that opens with a
     * brace but cannot be parsed is a truncated or malformed structured answer,
     * not prose. Scraping digits out of it reads the reasoning text as if it
     * were the answer, so callers stop there instead.
     */
    private static boolean isBrokenJson(String response) {
        String s = jsonBody(response);
        return s != null && s.startsWith("{") && tryParseJsonObject(response) == null;
    }

    /** The body with any {@code "reasoning": "..."} field and think block removed. */
    private static String withoutReasoning(String response) {
        String s = stripThinkingTags(response);
        if (s == null) return "";
        return REASONING_FIELD.matcher(s).replaceAll(" ");
    }

    /** Every in-range index in {@code text}, in written order, duplicates dropped. */
    private static List<Integer> indicesIn(String text, int limit) {
        List<Integer> found = new ArrayList<>();
        if (text == null || text.isEmpty()) return found;
        Set<Integer> seen = new LinkedHashSet<>();
        Matcher m = ANY_INTEGER.matcher(text);
        while (m.find()) {
            int val = Integer.parseInt(m.group(1));
            if (val >= 0 && val < limit && seen.add(val)) {
                found.add(val);
            }
        }
        return found;
    }

    private static String lastNonEmptyLine(String text) {
        String[] lines = text.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) return lines[i].strip();
        }
        return "";
    }

    /**
     * Extract a chosen option index. Tries JSON {@code {choice: int}} first;
     * falls back to text parsing for legacy responses.
     *
     * @return the chosen index, or {@code -1} when the response named no option
     *         — a failed call the caller has to record as a fallback
     */
    public static int parseChoiceIndex(String response, int numOptions) {
        JsonObject obj = tryParseJsonObject(response);
        if (obj != null) {
            // A parsed object is the answer, whatever it holds. Falling through
            // to text parsing would scan the object's own reasoning string.
            if (obj.has("choice")) {
                try {
                    int v = obj.get("choice").getAsInt();
                    if (v >= 0 && v < numOptions) return v;
                } catch (Exception ignored) {}
            }
            return -1;
        }
        if (response == null || response.isBlank()) return -1;
        // A body that opens with a brace and will not parse is a structured
        // answer the token limit cut in half, not prose to be mined.
        if (isBrokenJson(response)) return -1;

        // Legacy text answers only: the whole reply is the index, or a
        // line-initial "CHOICE: 2" label carries it, or a chatty model put the
        // bare index on its own last line. Digits inside a sentence are never
        // the answer — see the class notes.
        String answer = answerText(response);
        if (answer == null) {
            answer = answerText(lastNonEmptyLine(stripThinkingTags(response)));
        }
        if (answer == null) return -1;
        Matcher m = ANY_INTEGER.matcher(answer);
        while (m.find()) {
            int val = Integer.parseInt(m.group(1));
            if (val >= 0 && val < numOptions) return val;
        }
        return -1;
    }

    /**
     * Parse batch block assignments from LLM response.
     * Expected format: "A0:B1, A2:B0,B1" or "NONE" for no blocks.
     * Returns map of attacker index → set of blocker indices.
     *
     * @return the assignments, empty when the model declined to block, or
     *         {@code null} when the answer could not be read at all — which is
     *         a failed call, not a decision to block with nothing.
     */
    public static Map<Integer, Set<Integer>> parseBatchBlockAssignments(
            String response, int numAttackers, int numBlockers) {
        Map<Integer, Set<Integer>> result = new LinkedHashMap<>();
        // Try JSON {blocks: [{attacker:int, blockers:int[]}]} first
        JsonObject obj = tryParseJsonObject(response);
        if (obj != null) {
            if (!obj.has("blocks") || !obj.get("blocks").isJsonArray()) {
                return null; // an object without the payload field is not an answer
            }
            JsonArray arr = obj.getAsJsonArray("blocks");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject entry = el.getAsJsonObject();
                if (!entry.has("attacker") || !entry.has("blockers")) continue;
                int attIdx;
                try { attIdx = entry.get("attacker").getAsInt(); }
                catch (Exception ex) { continue; }
                if (attIdx < 0 || attIdx >= numAttackers) continue;
                if (!entry.get("blockers").isJsonArray()) continue;
                Set<Integer> blockers = new LinkedHashSet<>();
                for (JsonElement b : entry.getAsJsonArray("blockers")) {
                    try {
                        int bi = b.getAsInt();
                        if (bi >= 0 && bi < numBlockers) blockers.add(bi);
                    } catch (Exception ignored) {}
                }
                if (!blockers.isEmpty()) result.put(attIdx, blockers);
            }
            return result;
        }
        if (response == null || response.isBlank() || isBrokenJson(response)) return null;

        String trimmed = response.strip().toUpperCase();
        if (trimmed.equals("NONE") || trimmed.equals("N/A") || trimmed.equals("NO")) {
            return result;
        }

        // Split on commas that separate assignments (but not blocker lists within an assignment)
        // Format: "A0:B1, A2:B0,B1" — each assignment is "Ax:By" or "Ax:By,Bz"
        // We split on spaces and commas between assignments
        // Strategy: find all "A<n>:B<m>" patterns
        Pattern assignPattern = Pattern.compile("A?(\\d+)\\s*:\\s*B?(\\d+(?:\\s*[,+]\\s*B?\\d+)*)");
        Matcher m = assignPattern.matcher(trimmed);
        while (m.find()) {
            int attackerIdx = Integer.parseInt(m.group(1));
            if (attackerIdx < 0 || attackerIdx >= numAttackers) continue;

            Set<Integer> blockers = new LinkedHashSet<>();
            String blockerStr = m.group(2);
            Matcher bm = Pattern.compile("\\d+").matcher(blockerStr);
            while (bm.find()) {
                int blockerIdx = Integer.parseInt(bm.group());
                if (blockerIdx >= 0 && blockerIdx < numBlockers) {
                    blockers.add(blockerIdx);
                }
            }
            if (!blockers.isEmpty()) {
                result.put(attackerIdx, blockers);
            }
        }

        // Fallback: if no "A:B" pattern found, try simple format like "0:1, 2:0"
        if (result.isEmpty()) {
            Pattern simplePattern = Pattern.compile("(\\d+)\\s*:\\s*(\\d+(?:\\s*,\\s*\\d+)*)");
            Matcher sm = simplePattern.matcher(trimmed);
            while (sm.find()) {
                int attackerIdx = Integer.parseInt(sm.group(1));
                if (attackerIdx < 0 || attackerIdx >= numAttackers) continue;

                Set<Integer> blockers = new LinkedHashSet<>();
                Matcher bm = Pattern.compile("\\d+").matcher(sm.group(2));
                while (bm.find()) {
                    int blockerIdx = Integer.parseInt(bm.group());
                    if (blockerIdx >= 0 && blockerIdx < numBlockers) {
                        blockers.add(blockerIdx);
                    }
                }
                if (!blockers.isEmpty()) {
                    result.put(attackerIdx, blockers);
                }
            }
        }

        // Nothing named a block and nothing said "none": the answer was not understood.
        return result.isEmpty() ? null : result;
    }

    /**
     * Parse a batch response containing comma-separated indices (e.g., "0,2,3")
     * or "NONE"/"ALL" keywords.
     *
     * @param response raw LLM response text
     * @param maxIndex maximum valid index (exclusive)
     * @return the chosen indices, empty when the model chose none, or
     *         {@code null} when the answer could not be read at all — a failed
     *         call, which is not the same as a deliberate "none"
     */
    public static Set<Integer> parseBatchIndices(String response, int maxIndex) {
        Set<Integer> result = new LinkedHashSet<>();
        // Try JSON {indices: int[]} first
        JsonObject obj = tryParseJsonObject(response);
        if (obj != null) {
            if (!obj.has("indices") || !obj.get("indices").isJsonArray()) {
                return null; // an object without the payload field is not an answer
            }
            for (JsonElement el : obj.getAsJsonArray("indices")) {
                try {
                    int v = el.getAsInt();
                    if (v >= 0 && v < maxIndex) result.add(v);
                } catch (Exception ignored) {}
            }
            return result;
        }
        if (response == null || response.isBlank() || isBrokenJson(response)) return null;

        // Same rule as the plan parser: read an answer, never a sentence.
        String answer = answerText(response);
        if (answer == null) return null;

        String trimmed = answer.toUpperCase();
        if (trimmed.equals("NONE") || trimmed.equals("N/A") || trimmed.equals("NO")) {
            return result;
        }
        if (trimmed.equals("ALL")) {
            for (int i = 0; i < maxIndex; i++) result.add(i);
            return result;
        }

        // Extract all integers from the answer
        Matcher m = ANY_INTEGER.matcher(answer);
        while (m.find()) {
            int val = Integer.parseInt(m.group(1));
            if (val >= 0 && val < maxIndex) {
                result.add(val);
            }
        }
        // No payload field, no sentinel and no index in range: prose, not an answer.
        return result.isEmpty() ? null : result;
    }

    /**
     * Parse a target selection from an LLM response.
     *
     * <p>The answer is a list of indices into the candidate list the prompt
     * offered, in the order the model wants them targeted. Duplicates are
     * dropped (targeting the same entity twice is never legal) and indices
     * outside the candidate list are skipped rather than ending the list — a
     * model that names one bad index has still expressed a preference about the
     * rest, and the caller checks legality of every pick against the engine
     * before applying it.
     *
     * <p>Returns an empty list when nothing usable is present. That is not the
     * same as "target nothing": the caller treats an empty answer as a failed
     * call and keeps the targets the heuristic AI had already chosen.
     *
     * @param response      raw LLM response text
     * @param numCandidates number of candidates offered (indices must be below this)
     */
    public static List<Integer> parseTargetIndices(String response, int numCandidates) {
        List<Integer> result = new ArrayList<>();
        if (numCandidates <= 0) return result;

        // A parsed JSON object is the answer, whatever it holds — never a starting
        // point for a digit scan. Falling through from here is how a schema-shaped
        // answer got inverted: {"reasoning":"Bolt the 2/3 flier","targets":1} has a
        // scalar payload, so the array branch missed, the whole body was scraped, and
        // the "2" in "2/3" came out ahead of the "1" the model actually chose — with
        // candidate 2 being the seat's own creature. Every check downstream passed,
        // because Bolting your own Ornithopter is legal.
        JsonObject obj = tryParseJsonObject(response);
        if (obj != null) {
            Set<Integer> seen = new LinkedHashSet<>();
            JsonElement payload = obj.get("targets");
            if (payload != null && payload.isJsonArray()) {
                for (JsonElement el : payload.getAsJsonArray()) {
                    addIndex(result, seen, el, numCandidates);
                }
            } else if (payload != null && payload.isJsonPrimitive()) {
                addIndex(result, seen, payload, numCandidates);
            }
            return result;
        }
        if (response == null || response.isBlank()) return result;
        // A body that opens with a brace and will not parse is a truncated structured
        // answer. Its reasoning string is full of small numbers — power, toughness,
        // life, mana — and scraping them would produce a confident wrong target.
        if (isBrokenJson(response)) return result;

        // Text fallback, narrowest first. Digits inside a justification must never
        // outrank digits in the answer, so the reasoning field goes before anything
        // is counted, an explicit TARGETS: label wins, then the last line (chatty
        // models put the answer at the end), then the body as a whole.
        String body = withoutReasoning(response);
        Matcher labelled = TARGET_LABEL.matcher(body);
        if (labelled.find()) {
            List<Integer> fromLabel = indicesIn(labelled.group(1), numCandidates);
            if (!fromLabel.isEmpty()) return fromLabel;
        }
        List<Integer> fromLastLine = indicesIn(lastNonEmptyLine(body), numCandidates);
        if (!fromLastLine.isEmpty()) return fromLastLine;
        return indicesIn(body, numCandidates);
    }

    private static void addIndex(List<Integer> result, Set<Integer> seen, JsonElement el, int limit) {
        try {
            int v = el.getAsInt();
            if (v >= 0 && v < limit && seen.add(v)) result.add(v);
        } catch (Exception ignored) {
            // A non-numeric entry names no candidate; the caller checks the count.
        }
    }

    /**
     * Parse an ordered plan sequence from an LLM response (B1 — MAIN-phase plan batching).
     * Format: comma-separated indices, optionally followed by "PASS" to terminate
     * (e.g. "2,0,PASS"). A plain "PASS" or "NONE" returns an empty list. Indices
     * are returned in source order, duplicates skipped, out-of-range values end
     * the plan at that point.
     *
     * @return the plan, empty when the model chose to hold everything, or
     *         {@code null} when the answer could not be read at all
     *
     * <p>The distinction is the whole point of this method's contract. An empty
     * plan is a decision and is not a fallback; an unreadable answer is a failed
     * call and must be. Returning an empty list for both is how a prose reply
     * such as "I am at 20 life, so I will hold everything this turn." became a
     * silent heuristic decision, recorded in the model's own history as though
     * the model had made it, with the fallback counter still reading zero.
     */
    public static List<Integer> parsePlanSequence(String response, int numOptions) {
        List<Integer> result = new ArrayList<>();
        // Try JSON {plan: int[]} first
        JsonObject obj = tryParseJsonObject(response);
        if (obj != null) {
            JsonElement payload = obj.get("plan");
            if (payload == null || (!payload.isJsonArray() && !payload.isJsonPrimitive())) {
                return null; // an object without a usable payload field is not an answer
            }
            Set<Integer> seen = new LinkedHashSet<>();
            JsonArray steps = payload.isJsonArray() ? payload.getAsJsonArray() : null;
            if (steps == null) {
                addIndex(result, seen, payload, numOptions);
                return result;
            }
            for (JsonElement el : steps) {
                try {
                    int v = el.getAsInt();
                    if (v < 0 || v >= numOptions) break; // out-of-range ends plan
                    if (seen.add(v)) result.add(v);
                } catch (Exception ex) { break; }
            }
            return result;
        }
        if (response == null || response.isBlank() || isBrokenJson(response)) return null;

        // Text path. Only a response that IS an answer is read; a reply with a
        // sentence in it is a chain-of-thought the token limit cut short, and
        // its digits are mana costs and life totals, not a plan.
        String answer = answerText(response);
        if (answer == null) return null;

        String trimmed = answer.toUpperCase();
        if (trimmed.equals("PASS") || trimmed.equals("NONE") || trimmed.equals("N/A")) {
            return result;
        }

        // Walk tokens in order, stopping at PASS.
        Matcher tok = Pattern.compile("\\bPASS\\b|\\b(\\d+)\\b").matcher(trimmed);
        Set<Integer> seen = new LinkedHashSet<>();
        boolean understood = false;
        while (tok.find()) {
            if (tok.group(1) == null) {
                understood = true; // an explicit PASS is an answer, whatever came before
                break;
            }
            int val = Integer.parseInt(tok.group(1));
            if (val < 0 || val >= numOptions) {
                break; // out-of-range — end plan here
            }
            understood = true;
            if (seen.add(val)) {
                result.add(val);
            }
        }
        return understood ? result : null;
    }
}
