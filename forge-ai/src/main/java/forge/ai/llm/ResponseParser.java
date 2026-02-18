package forge.ai.llm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a chosen option index from LLM response text.
 * Handles various response formats: bare integers, "CHOICE: 2",
 * "I choose option 2", first number on first line, etc.
 */
public final class ResponseParser {
    private ResponseParser() {}

    private static final Pattern LABELED_PATTERN =
            Pattern.compile("(?:CHOICE|ANSWER|OPTION|SELECT)\\s*[:=]?\\s*(\\d+)",
                    Pattern.CASE_INSENSITIVE);

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

        // 4. Any integer in valid range anywhere in response
        Matcher anyM = Pattern.compile("\\b(\\d+)\\b").matcher(trimmed);
        while (anyM.find()) {
            int val = Integer.parseInt(anyM.group(1));
            if (val >= 0 && val < numOptions) return val;
        }

        return -1;
    }
}
