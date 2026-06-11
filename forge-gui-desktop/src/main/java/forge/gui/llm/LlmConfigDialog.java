/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2024  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.gui.llm;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.KeyStroke;

import forge.ai.llm.LLMConfig;

/**
 * Modal dialog for configuring a per-seat LLM player. Returns the canonical
 * profile string (e.g. {@code "cerebras:gpt-oss-120b"} or
 * {@code "direct:cerebras:gpt-oss-120b"}) on OK, or {@code null} on Cancel.
 *
 * <p>Plain Swing dialog (no Forge skinning) — kept lightweight and self-contained
 * so it works regardless of whether {@code FSkin} has been initialised.
 */
public final class LlmConfigDialog {

    /** Provider names that appear in the combo, in display order. */
    private static final String[] PROVIDERS = {
            "cerebras", "openrouter", "groq", "openai", "anthropic", "ollama", "openai-compat"
    };

    /** Static fallback model lists per provider (used when relay /v1/models is unreachable). */
    private static final Map<String, String[]> DEFAULT_MODELS = new LinkedHashMap<>();
    static {
        DEFAULT_MODELS.put("cerebras", new String[] {
                "qwen-3-235b-a22b-instruct-2507"
        });
        DEFAULT_MODELS.put("openrouter", new String[] {
                "inclusionai/ling-2.6-1t:free",
                "nvidia/nemotron-3-nano-30b-a3b:free",
                "openai/gpt-oss-120b:free",
                "deepseek/deepseek-chat-v3-0324"
        });
        DEFAULT_MODELS.put("groq", new String[] {
                "llama-3.3-70b-versatile",
                "meta-llama/llama-4-scout-17b-16e-instruct"
        });
        DEFAULT_MODELS.put("openai", new String[] {
                "gpt-4.1-mini",
                "gpt-4o-mini",
                "o4-mini"
        });
        DEFAULT_MODELS.put("anthropic", new String[] {
                "claude-sonnet-4-20250514",
                "claude-opus-4-20250514"
        });
        DEFAULT_MODELS.put("ollama", new String[] {
                "llama3"
        });
        DEFAULT_MODELS.put("openai-compat", new String[] {
                "openai/gpt-oss-20b"
        });
    }

    private LlmConfigDialog() { }

    /**
     * Show the dialog modally and return the canonical profile string, or
     * {@code null} if the user pressed Cancel / Esc.
     *
     * @param owner          the parent window (may be {@code null})
     * @param seatLabel      e.g. {@code "Seat 2"} — shown in the title
     * @param currentProfile current profile string to pre-populate from, or {@code null}
     */
    public static String show(Frame owner, String seatLabel, String currentProfile) {
        final JDialog dialog = new JDialog(owner,
                "LLM player" + (seatLabel == null || seatLabel.isEmpty() ? "" : " — " + seatLabel),
                true);

        // ─── Provider / model rows ─────────────────────────────────────────
        final JComboBox<String> providerCombo = new JComboBox<>(PROVIDERS);
        final JComboBox<String> modelCombo = new JComboBox<>();
        modelCombo.setEditable(true);
        final JButton refreshBtn = new JButton("Refresh");
        refreshBtn.setToolTipText("Fetch the current model list from the relay's /v1/models endpoint");

        final JCheckBox bypassRelay = new JCheckBox("Bypass relay (direct API call)");
        bypassRelay.setSelected(!LLMConfig.isRelayDefault());

        final JLabel compatLabel = new JLabel("OpenAI-compat URL:");
        final JTextField compatUrlField = new JTextField(24);
        String compatPrefill = LLMConfig.loadProviderApiKey("OPENAI_COMPAT_URL");
        if (compatPrefill == null || compatPrefill.isEmpty()) {
            compatPrefill = LLMConfig.loadProviderApiKey("RELAY_CUSTOM_URL");
        }
        if (compatPrefill != null) compatUrlField.setText(compatPrefill);

        final JPasswordField apiKeyField = new JPasswordField(24);
        apiKeyField.setToolTipText(
                "<html>Storing keys via the GUI isn't wired up yet.<br>"
                        + "Set the env var (e.g. CEREBRAS_API_KEY) instead.</html>");

        final JLabel errorLabel = new JLabel(" ");
        errorLabel.setForeground(java.awt.Color.RED);

        // ─── Initial state from currentProfile ─────────────────────────────
        ParsedProfile parsed = parseProfile(currentProfile);
        if (parsed != null) {
            providerCombo.setSelectedItem(parsed.provider);
            populateModels(modelCombo, parsed.provider);
            modelCombo.setSelectedItem(parsed.model);
            if (parsed.forcedRelay != null) {
                bypassRelay.setSelected(!parsed.forcedRelay);
            }
        } else {
            populateModels(modelCombo, (String) providerCombo.getSelectedItem());
        }

        // ─── Provider switch handler ───────────────────────────────────────
        Runnable updateCompatVisibility = () -> {
            boolean isCompat = "openai-compat".equals(providerCombo.getSelectedItem());
            compatLabel.setVisible(isCompat);
            compatUrlField.setVisible(isCompat);
            dialog.revalidate();
        };
        providerCombo.addActionListener(e -> {
            String prov = (String) providerCombo.getSelectedItem();
            populateModels(modelCombo, prov);
            updateCompatVisibility.run();
        });

        // ─── Refresh handler ───────────────────────────────────────────────
        refreshBtn.addActionListener(e -> {
            String prov = (String) providerCombo.getSelectedItem();
            List<String> fetched = fetchRelayModels(prov);
            if (fetched != null && !fetched.isEmpty()) {
                String previousSelection = (String) modelCombo.getEditor().getItem();
                modelCombo.removeAllItems();
                for (String m : fetched) {
                    modelCombo.addItem(m);
                }
                if (previousSelection != null && !previousSelection.isEmpty()) {
                    modelCombo.setSelectedItem(previousSelection);
                }
            } else {
                // Silent fallback to static list — log only.
                System.err.println("[LLM] /v1/models fetch failed for provider '" + prov
                        + "', keeping static list.");
            }
        });

        // ─── Layout ────────────────────────────────────────────────────────
        JPanel content = new JPanel(new GridBagLayout());
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.anchor = GridBagConstraints.WEST;

        int row = 0;

        gc.gridx = 0; gc.gridy = row; gc.weightx = 0;
        content.add(new JLabel("Provider:"), gc);
        gc.gridx = 1; gc.gridwidth = 2; gc.weightx = 1.0;
        content.add(providerCombo, gc);
        gc.gridwidth = 1;
        row++;

        gc.gridx = 0; gc.gridy = row; gc.weightx = 0;
        content.add(new JLabel("Model:"), gc);
        gc.gridx = 1; gc.weightx = 1.0;
        content.add(modelCombo, gc);
        gc.gridx = 2; gc.weightx = 0;
        content.add(refreshBtn, gc);
        row++;

        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 3; gc.weightx = 1.0;
        content.add(bypassRelay, gc);
        gc.gridwidth = 1;
        row++;

        gc.gridx = 0; gc.gridy = row; gc.weightx = 0;
        content.add(compatLabel, gc);
        gc.gridx = 1; gc.gridwidth = 2; gc.weightx = 1.0;
        content.add(compatUrlField, gc);
        gc.gridwidth = 1;
        row++;

        gc.gridx = 0; gc.gridy = row; gc.weightx = 0;
        content.add(new JLabel("API key:"), gc);
        gc.gridx = 1; gc.gridwidth = 2; gc.weightx = 1.0;
        content.add(apiKeyField, gc);
        gc.gridwidth = 1;
        row++;

        gc.gridx = 0; gc.gridy = row; gc.gridwidth = 3; gc.weightx = 1.0;
        content.add(errorLabel, gc);
        gc.gridwidth = 1;
        row++;

        // ─── Buttons ───────────────────────────────────────────────────────
        final String[] result = new String[1];

        JButton okBtn = new JButton("OK");
        JButton cancelBtn = new JButton("Cancel");
        JPanel buttons = new JPanel();
        buttons.add(okBtn);
        buttons.add(cancelBtn);

        okBtn.addActionListener(e -> {
            String prov = (String) providerCombo.getSelectedItem();
            Object modelObj = modelCombo.getEditor().getItem();
            String model = modelObj == null ? "" : modelObj.toString().trim();
            if (prov == null || prov.isEmpty()) {
                errorLabel.setText("Please select a provider.");
                return;
            }
            if (model.isEmpty()) {
                errorLabel.setText("Please enter or pick a model.");
                return;
            }
            // openai-compat: URL required when bypassing relay (direct path needs
            // a base URL); also required when relay path is on (forwarded as
            // X-Custom-Url). Either way, we need a non-empty value.
            if ("openai-compat".equals(prov)) {
                String url = compatUrlField.getText().trim();
                if (url.isEmpty()) {
                    errorLabel.setText("OpenAI-compat URL is required for openai-compat.");
                    return;
                }
                String envUrl = LLMConfig.loadProviderApiKey("OPENAI_COMPAT_URL");
                if (envUrl == null || !envUrl.equals(url)) {
                    // Session-scoped override that loadProviderApiKey actually reads
                    // (forge.llm.env.<VAR> channel) — the validation probe below and
                    // the runtime config both see the user-entered URL. Persisting
                    // across restarts still requires the env var / .env entry.
                    System.setProperty("forge.llm.env.OPENAI_COMPAT_URL", url);
                    System.err.println("[LLM] OpenAI-compat URL set for this session: " + url
                            + " — export OPENAI_COMPAT_URL=<url> in your env or .env to persist.");
                }
            }

            // Compose the canonical profile string. Defer to LLMConfig for
            // routing semantics: when the bypass-relay choice differs from the
            // env-driven default we emit an explicit `relay:` or `direct:`
            // prefix so the per-seat decision overrides the env flag.
            boolean envRelay = LLMConfig.isRelayDefault();
            boolean wantsDirect = bypassRelay.isSelected();
            boolean wantsRelay = !wantsDirect;
            String canonical;
            if (wantsRelay == envRelay) {
                canonical = LLMConfig.canonicalProfileString(prov, model);
            } else if (wantsDirect) {
                canonical = "direct:" + prov + ":" + model;
            } else {
                canonical = "relay:" + prov + ":" + model;
            }

            // Validate via LLMConfig.fromProfileString — surfaces missing
            // env-var prerequisites (e.g. ANTHROPIC direct path) as a null
            // return, which we report inline rather than crashing later.
            LLMConfig probe = LLMConfig.fromProfileString(canonical, null, 0.2, 0, false);
            if (probe == null) {
                errorLabel.setText("Profile is not valid (check stderr for details).");
                return;
            }

            result[0] = canonical;
            dialog.setVisible(false);
        });

        cancelBtn.addActionListener(e -> {
            result[0] = null;
            dialog.setVisible(false);
        });

        // Esc cancels.
        dialog.getRootPane().registerKeyboardAction(
                new AbstractAction() {
                    @Override public void actionPerformed(ActionEvent e) {
                        result[0] = null;
                        dialog.setVisible(false);
                    }
                },
                KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.getRootPane().setDefaultButton(okBtn);

        dialog.getContentPane().setLayout(new BorderLayout());
        dialog.getContentPane().add(content, BorderLayout.CENTER);
        dialog.getContentPane().add(buttons, BorderLayout.SOUTH);

        // Apply initial visibility for openai-compat row.
        updateCompatVisibility.run();

        dialog.pack();
        dialog.setMinimumSize(new Dimension(480, dialog.getHeight()));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        dialog.dispose();
        return result[0];
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private static void populateModels(JComboBox<String> combo, String provider) {
        combo.removeAllItems();
        String[] defaults = DEFAULT_MODELS.get(provider);
        if (defaults != null) {
            for (String m : defaults) {
                combo.addItem(m);
            }
        }
    }

    /**
     * Try to fetch the relay's model catalogue for a provider. Returns
     * {@code null} on any failure (network, timeout, non-200, parse error).
     * Caller falls back to the static list.
     */
    private static List<String> fetchRelayModels(String provider) {
        if (provider == null || provider.isEmpty()) return null;
        String base = LLMConfig.loadProviderApiKey("RELAY_BASE_URL");
        if (base == null || base.isEmpty()) base = "http://localhost:8787/v1";
        // Strip trailing slash, but keep an existing /v1 suffix as-is.
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        HttpURLConnection conn = null;
        try {
            URL url = new URL(base + "/models?x-provider="
                    + java.net.URLEncoder.encode(provider, "UTF-8"));
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(4_000);
            conn.setReadTimeout(4_000);
            conn.setRequestProperty("X-Provider", provider);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) return null;
            StringBuilder body = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) body.append(line);
            }
            return parseModelIds(body.toString());
        } catch (Exception ex) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Tiny dependency-free JSON scrape: pulls every {@code "id":"..."} value
     * out of an OpenAI-style {@code /v1/models} response. Avoids dragging in a
     * JSON library just for one endpoint.
     */
    private static List<String> parseModelIds(String json) {
        List<String> out = new ArrayList<>();
        if (json == null) return out;
        int idx = 0;
        while (true) {
            int key = json.indexOf("\"id\"", idx);
            if (key < 0) break;
            int colon = json.indexOf(':', key + 4);
            if (colon < 0) break;
            int firstQuote = json.indexOf('"', colon + 1);
            if (firstQuote < 0) break;
            int endQuote = firstQuote + 1;
            // Walk past escaped quotes.
            while (endQuote < json.length()) {
                char c = json.charAt(endQuote);
                if (c == '\\' && endQuote + 1 < json.length()) {
                    endQuote += 2;
                    continue;
                }
                if (c == '"') break;
                endQuote++;
            }
            if (endQuote >= json.length()) break;
            String id = json.substring(firstQuote + 1, endQuote);
            if (!id.isEmpty()) out.add(id);
            idx = endQuote + 1;
        }
        return out;
    }

    /** Parsed view of an existing profile string for pre-population. */
    private static final class ParsedProfile {
        final String provider;
        final String model;
        /** {@code null} = no override, {@code true} = relay forced, {@code false} = direct forced. */
        final Boolean forcedRelay;

        ParsedProfile(String provider, String model, Boolean forcedRelay) {
            this.provider = provider;
            this.model = model;
            this.forcedRelay = forcedRelay;
        }
    }

    private static ParsedProfile parseProfile(String profile) {
        if (profile == null) return null;
        String trimmed = profile.trim();
        if (trimmed.isEmpty()) return null;
        String lower = trimmed.toLowerCase();

        Boolean forcedRelay = null;
        String rest = trimmed;
        if (lower.startsWith("relay:")) {
            // Triple-segment relay:<provider>:<model>?
            int firstColon = trimmed.indexOf(':');
            int secondColon = trimmed.indexOf(':', firstColon + 1);
            if (secondColon > 0) {
                String maybeProv = trimmed.substring(firstColon + 1, secondColon).toLowerCase();
                if (Arrays.asList(PROVIDERS).contains(maybeProv)) {
                    forcedRelay = Boolean.TRUE;
                    rest = trimmed.substring(firstColon + 1);
                }
            }
        } else if (lower.startsWith("direct:")) {
            forcedRelay = Boolean.FALSE;
            rest = trimmed.substring("direct:".length());
        } else if (lower.startsWith("relay-")) {
            // Back-compat: relay-<provider>:<model> → relay forced.
            int colon = trimmed.indexOf(':');
            if (colon > "relay-".length()) {
                String upstream = trimmed.substring("relay-".length(), colon).toLowerCase();
                String mappedProv = "custom".equals(upstream) ? "openai-compat" : upstream;
                String model = trimmed.substring(colon + 1);
                if (Arrays.asList(PROVIDERS).contains(mappedProv)) {
                    return new ParsedProfile(mappedProv, model, Boolean.TRUE);
                }
            }
            return null;
        }

        int colon = rest.indexOf(':');
        if (colon <= 0) return null;
        String prov = rest.substring(0, colon).toLowerCase().trim();
        String model = rest.substring(colon + 1).trim();
        if (!Arrays.asList(PROVIDERS).contains(prov)) return null;
        return new ParsedProfile(prov, model, forcedRelay);
    }
}
