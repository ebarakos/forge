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
 */
public final class ResponseParser {
    private ResponseParser() {}

    private static final Pattern LABELED_PATTERN =
            Pattern.compile("(?:CHOICE|ANSWER|OPTION|SELECT)\\s*[:=]?\\s*(\\d+)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern THINK_PATTERN =
            Pattern.compile("<think>[\\s\\S]*?</think>", Pattern.CASE_INSENSITIVE);

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
     */
    private static JsonObject tryParseJsonObject(String response) {
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
        if (!s.startsWith("{")) return null;
        try {
            JsonElement el = JsonParser.parseString(s);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
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
     */
    public static Map<Integer, Set<Integer>> parseBatchBlockAssignments(
            String response, int numAttackers, int numBlockers) {
        Map<Integer, Set<Integer>> result = new LinkedHashMap<>();
        // Try JSON {blocks: [{attacker:int, blockers:int[]}]} first
        JsonObject obj = tryParseJsonObject(response);
        if (obj != null && obj.has("blocks") && obj.get("blocks").isJsonArray()) {
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
            if (!result.isEmpty()) return result;
        }
        if (response == null || response.isBlank()) return result;

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

        return result;
    }

    /**
     * Parse a batch response containing comma-separated indices (e.g., "0,2,3")
     * or "NONE"/"ALL" keywords.
     *
     * @param response raw LLM response text
     * @param maxIndex maximum valid index (exclusive)
     * @return set of valid indices (may be empty for NONE)
     */
    public static Set<Integer> parseBatchIndices(String response, int maxIndex) {
        Set<Integer> result = new LinkedHashSet<>();
        // Try JSON {indices: int[]} first
        JsonObject obj = tryParseJsonObject(response);
        if (obj != null && obj.has("indices") && obj.get("indices").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("indices")) {
                try {
                    int v = el.getAsInt();
                    if (v >= 0 && v < maxIndex) result.add(v);
                } catch (Exception ignored) {}
            }
            return result;
        }
        if (response == null || response.isBlank()) return result;

        String trimmed = response.strip().toUpperCase();
        if (trimmed.equals("NONE") || trimmed.equals("N/A") || trimmed.equals("NO")) {
            return result;
        }
        if (trimmed.equals("ALL")) {
            for (int i = 0; i < maxIndex; i++) result.add(i);
            return result;
        }

        // Extract all integers from the response
        Matcher m = Pattern.compile("\\b(\\d+)\\b").matcher(response);
        while (m.find()) {
            int val = Integer.parseInt(m.group(1));
            if (val >= 0 && val < maxIndex) {
                result.add(val);
            }
        }
        return result;
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

        Set<Integer> seen = new LinkedHashSet<>();
        JsonObject obj = tryParseJsonObject(response);
        if (obj != null && obj.has("targets") && obj.get("targets").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("targets")) {
                try {
                    int v = el.getAsInt();
                    if (v >= 0 && v < numCandidates && seen.add(v)) result.add(v);
                } catch (Exception ignored) {}
            }
            return result;
        }
        if (response == null || response.isBlank()) return result;

        // Text fallback for models that ignore the schema: take every integer
        // that names a real candidate, in the order written.
        Matcher m = Pattern.compile("\\b(\\d+)\\b").matcher(response);
        while (m.find()) {
            int val = Integer.parseInt(m.group(1));
            if (val >= 0 && val < numCandidates && seen.add(val)) {
                result.add(val);
            }
        }
        return result;
    }

    /**
     * Parse an ordered plan sequence from an LLM response (B1 — MAIN-phase plan batching).
     * Format: comma-separated indices, optionally followed by "PASS" to terminate
     * (e.g. "2,0,PASS"). A plain "PASS" or "NONE" returns an empty list. Indices
     * are returned in source order, duplicates skipped, out-of-range values end
     * the plan at that point.
     */
    public static List<Integer> parsePlanSequence(String response, int numOptions) {
        List<Integer> result = new ArrayList<>();
        // Try JSON {plan: int[]} first
        JsonObject obj = tryParseJsonObject(response);
        if (obj != null && obj.has("plan") && obj.get("plan").isJsonArray()) {
            Set<Integer> seen = new LinkedHashSet<>();
            for (JsonElement el : obj.getAsJsonArray("plan")) {
                try {
                    int v = el.getAsInt();
                    if (v < 0 || v >= numOptions) break; // out-of-range ends plan
                    if (seen.add(v)) result.add(v);
                } catch (Exception ex) { break; }
            }
            return result;
        }
        if (response == null || response.isBlank()) return result;

        String trimmed = response.strip().toUpperCase();
        if (trimmed.equals("PASS") || trimmed.equals("NONE") || trimmed.equals("N/A")) {
            return result;
        }

        // Walk tokens in order, stopping at PASS.
        Matcher tok = Pattern.compile("\\bPASS\\b|\\b(\\d+)\\b").matcher(trimmed);
        Set<Integer> seen = new LinkedHashSet<>();
        while (tok.find()) {
            if (tok.group(1) == null) {
                break; // hit PASS
            }
            int val = Integer.parseInt(tok.group(1));
            if (val < 0 || val >= numOptions) {
                break; // out-of-range — end plan here
            }
            if (seen.add(val)) {
                result.add(val);
            }
        }
        return result;
    }
}
