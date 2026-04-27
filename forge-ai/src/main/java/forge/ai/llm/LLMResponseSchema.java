package forge.ai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * The four JSON response schemas used by the LLM player. Each one shares a
 * {@code reasoning} field (forces chain-of-thought for non-thinking models)
 * plus a payload field whose name matches the decision type.
 *
 * <p>The schema is injected into the chat-completion request body as
 * {@code response_format: { type: "json_schema", schema: {...} }} for
 * OpenAI-compatible cloud providers, or as {@code format: {...}} for Ollama.
 */
public enum LLMResponseSchema {
    /** Single choice — payload field {@code choice: int}. */
    CHOICE("choice", PayloadKind.INT),
    /** Multi-pick / batch attack / batch scry — payload {@code indices: int[]}. */
    INDICES("indices", PayloadKind.INT_ARRAY),
    /** Block assignment — payload {@code blocks: [{attacker:int, blockers:int[]}]}. */
    BLOCKS("blocks", PayloadKind.BLOCKS),
    /** MAIN-phase plan sequence — payload {@code plan: int[]} (omit for PASS). */
    PLAN("plan", PayloadKind.INT_ARRAY);

    enum PayloadKind { INT, INT_ARRAY, BLOCKS }

    private final String fieldName;
    private final PayloadKind kind;

    LLMResponseSchema(String fieldName, PayloadKind kind) {
        this.fieldName = fieldName;
        this.kind = kind;
    }

    public String fieldName() { return fieldName; }
    public PayloadKind kind() { return kind; }

    /**
     * Build the JSON Schema object for this response shape. Used in the
     * {@code response_format} (OpenAI/Cerebras/OpenRouter) or {@code format}
     * (Ollama) field of the chat-completion request body.
     */
    public JsonObject toJsonSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();

        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("type", "string");
        reasoning.addProperty("description",
                "1-3 sentences explaining your choice — what you're trying to do and why.");
        properties.add("reasoning", reasoning);

        JsonArray required = new JsonArray();
        required.add("reasoning");

        switch (kind) {
            case INT: {
                JsonObject p = new JsonObject();
                p.addProperty("type", "integer");
                p.addProperty("description", "The chosen option index from OPTIONS.");
                properties.add(fieldName, p);
                required.add(fieldName);
                break;
            }
            case INT_ARRAY: {
                JsonObject p = new JsonObject();
                p.addProperty("type", "array");
                JsonObject items = new JsonObject();
                items.addProperty("type", "integer");
                p.add("items", items);
                p.addProperty("description",
                        "Ordered indices of options to take. Empty array means none / PASS.");
                properties.add(fieldName, p);
                required.add(fieldName);
                break;
            }
            case BLOCKS: {
                JsonObject p = new JsonObject();
                p.addProperty("type", "array");
                JsonObject items = new JsonObject();
                items.addProperty("type", "object");
                JsonObject itemProps = new JsonObject();
                JsonObject att = new JsonObject(); att.addProperty("type", "integer");
                JsonObject bl = new JsonObject(); bl.addProperty("type", "array");
                JsonObject blItem = new JsonObject(); blItem.addProperty("type", "integer");
                bl.add("items", blItem);
                itemProps.add("attacker", att);
                itemProps.add("blockers", bl);
                items.add("properties", itemProps);
                JsonArray itemReq = new JsonArray();
                itemReq.add("attacker"); itemReq.add("blockers");
                items.add("required", itemReq);
                items.addProperty("additionalProperties", false);
                p.add("items", items);
                p.addProperty("description",
                        "Block assignments: each entry maps an attacker index to a list of blocker indices. Empty array means no blocks.");
                properties.add(fieldName, p);
                required.add(fieldName);
                break;
            }
        }

        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }
}
