package forge.ai.llm;

import java.util.LinkedHashSet;
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
     */
    public static String stripThinkingTags(String content) {
        if (content == null) return null;
        if (!content.contains("<think>") && !content.contains("<Think>")) {
            return content;
        }
        return THINK_PATTERN.matcher(content).replaceAll("").strip();
    }

    /**
     * Parse the LLM response to extract a chosen option index.
     *
     * @param response   the raw LLM response text
     * @param numOptions number of valid options [0, numOptions)
     * @return chosen index, or -1 if parsing fails
     */
    public static int parseChoiceIndex(String response, int numOptions) {
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
     * Parse a batch response containing comma-separated indices (e.g., "0,2,3")
     * or "NONE"/"ALL" keywords.
     *
     * @param response raw LLM response text
     * @param maxIndex maximum valid index (exclusive)
     * @return set of valid indices (may be empty for NONE)
     */
    public static Set<Integer> parseBatchIndices(String response, int maxIndex) {
        Set<Integer> result = new LinkedHashSet<>();
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
}
