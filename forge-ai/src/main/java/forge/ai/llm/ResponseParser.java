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
 * Handles various response formats: bare integers, "CHOICE: 2",
 * "I choose option 2", first number on first line, etc.
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
 */
public final class ResponseParser {
    private ResponseParser() {}

    private static final Pattern LABELED_PATTERN =
            Pattern.compile("(?:CHOICE|ANSWER|OPTION|SELECT)\\s*[:=]?\\s*(\\d+)",
                    Pattern.CASE_INSENSITIVE);

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
     */
    public static int parseChoiceIndex(String response, int numOptions) {
        JsonObject obj = tryParseJsonObject(response);
        if (obj != null && obj.has("choice")) {
            try {
                int v = obj.get("choice").getAsInt();
                if (v >= 0 && v < numOptions) return v;
            } catch (Exception ignored) {}
        }
        if (response == null || response.isBlank()) return -1;

        String trimmed = response.strip();

        // 1. Try direct integer parse of entire response
        try {
            int val = Integer.parseInt(trimmed);
            if (val >= 0 && val < numOptions) return val;
        } catch (NumberFormatException ignored) {}

        // 2. Look for "CHOICE: N" / "ANSWER: N" pattern
        Matcher m = LABELED_PATTERN.matcher(trimmed);
        if (m.find()) {
            int val = Integer.parseInt(m.group(1));
            if (val >= 0 && val < numOptions) return val;
        }

        // 3. First integer on first non-empty line
        String firstLine = trimmed.lines()
                .filter(l -> !l.isBlank())
                .findFirst()
                .orElse("");
        Matcher lineM = Pattern.compile("\\b(\\d+)\\b").matcher(firstLine);
        if (lineM.find()) {
            int val = Integer.parseInt(lineM.group(1));
            if (val >= 0 && val < numOptions) return val;
        }

        // 4. Last non-empty line (chatty models put answer at the end)
        String lastLine = "";
        String[] lines = trimmed.split("\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                lastLine = lines[i].strip();
                break;
            }
        }
        if (!lastLine.isEmpty() && !lastLine.equals(firstLine)) {
            // Try direct parse of last line
            try {
                int val = Integer.parseInt(lastLine);
                if (val >= 0 && val < numOptions) return val;
            } catch (NumberFormatException ignored) {}
            // Try labeled pattern on last line
            Matcher lastM = LABELED_PATTERN.matcher(lastLine);
            if (lastM.find()) {
                int val = Integer.parseInt(lastM.group(1));
                if (val >= 0 && val < numOptions) return val;
            }
            // Try first integer on last line
            Matcher lastLineM = Pattern.compile("\\b(\\d+)\\b").matcher(lastLine);
            if (lastLineM.find()) {
                int val = Integer.parseInt(lastLineM.group(1));
                if (val >= 0 && val < numOptions) return val;
            }
        }

        // 5. Last integer in valid range anywhere in response
        int lastValid = -1;
        Matcher anyM = Pattern.compile("\\b(\\d+)\\b").matcher(trimmed);
        while (anyM.find()) {
            int val = Integer.parseInt(anyM.group(1));
            if (val >= 0 && val < numOptions) lastValid = val;
        }
        return lastValid;
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

        String trimmed = response.strip().toUpperCase();
        if (trimmed.equals("NONE") || trimmed.equals("N/A") || trimmed.equals("NO")) {
            return result;
        }
        if (trimmed.equals("ALL")) {
            for (int i = 0; i < maxIndex; i++) result.add(i);
            return result;
        }

        // Extract all integers from the response
        Matcher m = ANY_INTEGER.matcher(response);
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

        String trimmed = response.strip().toUpperCase();
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
