package forge.player;

import forge.LobbyPlayer;
import forge.ai.AIOption;
import forge.ai.AiProfileUtil;
import forge.ai.LobbyPlayerAi;
import forge.ai.llm.LLMClient;
import forge.ai.llm.LLMConfig;
import forge.ai.llm.LLMStrictMode;
import forge.ai.llm.LobbyPlayerLLM;
import forge.gui.GuiBase;
import forge.gui.util.SOptionPane;
import forge.localinstance.properties.ForgeNetPreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.GuiDisplayUtil;
import forge.util.Localizer;
import forge.util.MyRandom;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

public final class GamePlayerUtil {
    private GamePlayerUtil() { }
    private static Localizer localizer = Localizer.getInstance();
    private static final LobbyPlayer guiPlayer = new LobbyPlayerHuman("Human");
    public static LobbyPlayer getGuiPlayer() {
        return guiPlayer;
    }
    public static LobbyPlayer getGuiPlayer(final String name, final int avatarIndex, final int sleeveIndex, final boolean writePref) {
        if (writePref) {
            if (!name.equals(guiPlayer.getName())) {
                guiPlayer.setName(name);
                FModel.getPreferences().setPref(FPref.PLAYER_NAME, name);
                FModel.getPreferences().save();
            }

            guiPlayer.setAvatarIndex(avatarIndex);
            guiPlayer.setSleeveIndex(sleeveIndex);
            return guiPlayer;
        }
        //use separate LobbyPlayerHuman instance for human players beyond first
        return new LobbyPlayerHuman(name, avatarIndex, sleeveIndex);
    }

    public static LobbyPlayer getQuestPlayer() {
        return guiPlayer; //TODO: Make this a separate player
    }

    public static LobbyPlayer createAiPlayer() {
        return createAiPlayer(GuiDisplayUtil.getRandomAiName());
    }
    public static LobbyPlayer createAiPlayer(final String name) {
        final int avatarCount = GuiBase.getInterface().getAvatarCount();
        final int sleeveCount = GuiBase.getInterface().getSleevesCount();
        return createAiPlayer(name, avatarCount == 0 ? 0 : MyRandom.getRandom().nextInt(avatarCount), sleeveCount == 0 ? 0 : MyRandom.getRandom().nextInt(sleeveCount));
    }
    public static LobbyPlayer createAiPlayer(final String name, final String profileOverride) {
        final int avatarCount = GuiBase.getInterface().getAvatarCount();
        final int sleeveCount = GuiBase.getInterface().getSleevesCount();
        // "sim" / "sim:<Profile>" enables the simulation engine for this seat —
        // CLI parity with the GUI "Use Simulation" checkbox. The remainder (or
        // the Simulation profile when bare) selects the heuristic dials.
        Set<AIOption> options = null;
        String profile = profileOverride;
        String lower = profile == null ? "" : profile.trim().toLowerCase(Locale.ROOT);
        if (lower.equals("sim") || lower.startsWith("sim:")) {
            options = Collections.singleton(AIOption.USE_SIMULATION);
            profile = lower.equals("sim") ? "" : profile.trim().substring("sim:".length()).trim();
            if (profile.isEmpty()) {
                profile = "Simulation";
            }
        }
        return createAiPlayer(name, avatarCount == 0 ? 0 : MyRandom.getRandom().nextInt(avatarCount), sleeveCount == 0 ? 0 : MyRandom.getRandom().nextInt(sleeveCount), options, profile);
    }
    public static LobbyPlayer createAiPlayer(final String name, final int avatarIndex) {
        final int sleeveCount = GuiBase.getInterface().getSleevesCount();
        return createAiPlayer(name, avatarIndex, sleeveCount == 0 ? 0 : MyRandom.getRandom().nextInt(sleeveCount), null, "");
    }
    public static LobbyPlayer createAiPlayer(final String name, final int avatarIndex, final int sleeveIndex) {
        return createAiPlayer(name, avatarIndex, sleeveIndex, null, "");
    }
    public static LobbyPlayer createAiPlayer(final String name, final int avatarIndex, final int sleeveIndex, final Set<AIOption> options) {
        return createAiPlayer(name, avatarIndex, sleeveIndex, options, "");
    }
    public static LobbyPlayer createAiPlayer(final String name, final int avatarIndex, final int sleeveIndex, final Set<AIOption> options, final String profileOverride) {
        final LobbyPlayerAi player = new LobbyPlayerAi(name, options);

        // TODO: implement specific AI profiles for quest mode.
        String profile = "";
        if (profileOverride.isEmpty()) {
            String lastProfileChosen = FModel.getPreferences().getPref(FPref.UI_CURRENT_AI_PROFILE);
            // Phase-2 migration shim: rewrite legacy "LLM (Provider)" display
            // strings — saved by older builds — into the canonical profile
            // grammar so they keep working after the dropdown was reduced to
            // a single "LLM…" entry.
            String migrated = migrateLegacyLlmDisplay(lastProfileChosen);
            if (migrated != null) {
                lastProfileChosen = migrated;
                FModel.getPreferences().setPref(FPref.UI_CURRENT_AI_PROFILE, migrated);
                FModel.getPreferences().save();
            }
            if (!AiProfileUtil.getProfilesDisplayList().contains(lastProfileChosen)
                    && !LLMConfig.isLlmProfile(lastProfileChosen)) {
                System.out.println("[AI Preferences] Unknown profile " + lastProfileChosen + " was requested, resetting to default.");
                lastProfileChosen = "Default";
                FModel.getPreferences().setPref(FPref.UI_CURRENT_AI_PROFILE, "Default");
                FModel.getPreferences().save();
            }
            player.setRotateProfileEachGame(lastProfileChosen.equals(AiProfileUtil.AI_PROFILE_RANDOM_DUEL));
            if (lastProfileChosen.equals(AiProfileUtil.AI_PROFILE_RANDOM_MATCH)) {
                lastProfileChosen = AiProfileUtil.getRandomProfile();
            }
            profile = lastProfileChosen;
        } else {
            profile = profileOverride;
        }

        assert (!profile.isEmpty());
        
        player.setAiProfile(profile);
        player.setAvatarIndex(avatarIndex);
        player.setSleeveIndex(sleeveIndex);
        return player;
    }

    /**
     * Create an LLM-backed AI player.
     *
     * @param name   player display name
     * @param client LLMClient instance for API calls
     */
    public static LobbyPlayer createLLMPlayer(String name, LLMClient client) {
        return createLLMPlayer(name, client, null);
    }

    /**
     * Create an LLM-backed AI player that plays under a named AI profile.
     *
     * <p>The model does not decide everything on an LLM seat: the heuristic AI
     * underneath it handles every decision the model is not asked about, and
     * takes its dials from an {@code .ai} profile. This used to be pinned to
     * {@code Default}, which meant no LLM seat could run a tuned profile.
     *
     * @param aiProfile name of a profile in {@code res/ai}; null or empty keeps
     *                  {@code Default}
     */
    public static LobbyPlayer createLLMPlayer(String name, LLMClient client, String aiProfile) {
        LobbyPlayerLLM player = new LobbyPlayerLLM(name, client);
        player.setAiProfile(aiProfile == null || aiProfile.trim().isEmpty()
                ? "Default" : aiProfile.trim());
        return player;
    }

    /**
     * Create an LLM-backed AI player from a canonical profile string
     * (e.g. {@code "cerebras:gpt-oss-120b"} or {@code "direct:openai:gpt-4o-mini"}).
     */
    public static LobbyPlayer createLLMPlayerFromProfile(String name, String profileString) {
        String apiKey = LLMConfig.loadApiKeyFromEnv();
        boolean debug = LLMConfig.isDebugEnabled();
        LLMConfig config = LLMConfig.fromProfileString(profileString, apiKey,
                0.2, 0, debug);
        if (config == null) {
            if (LLMStrictMode.isEnabled()) {
                throw new IllegalStateException(LLMStrictMode.silentHeuristicSeatMessage(
                        "'" + name + "'", profileString,
                        "the profile could not be turned into an LLM configuration"
                                + " (any [LLM] line printed above says which setting is missing)"));
            }
            System.err.println("[LLM] Could not build LLMConfig from profile '"
                    + profileString + "' — falling back to heuristic AI for seat '" + name + "'.");
            return createAiPlayer(name);
        }
        boolean hasAuth = (config.getApiKey() != null && !config.getApiKey().isEmpty())
                || (config.getUserApiKey() != null && !config.getUserApiKey().isEmpty())
                || "ollama".equals(config.getProvider());
        String upstream = config.getRelayProvider() != null ? "→" + config.getRelayProvider() : "";
        System.err.println("[LLM] Creating LLM player '" + name + "' from '" + profileString
                + "' → " + config.getProvider() + upstream + ":" + config.getModel()
                + " (auth=" + (hasAuth ? "ok" : "MISSING") + ", debug=" + debug + ")");
        LLMClient client = new LLMClient(config);
        return createLLMPlayer(name, client, LLMConfig.aiProfilePart(profileString));
    }

    /**
     * One-shot migration for persisted profile strings written by builds before
     * Phase 2 collapsed the four {@code "LLM (Provider)"} dropdown entries
     * into a single {@code "LLM…"} dialog. Returns the canonical equivalent,
     * or {@code null} if the input is not a legacy display string.
     */
    private static String migrateLegacyLlmDisplay(String profile) {
        if (profile == null) return null;
        switch (profile) {
            case "LLM (Custom)":     return "openai-compat:openai/gpt-oss-20b";
            case "LLM (Local)":      return "ollama:llama3";
            case "LLM (OpenRouter)": return "openrouter:inclusionai/ling-2.6-1t:free";
            case "LLM (Cerebras)":   return "cerebras:qwen-3-235b-a22b-instruct-2507";
            default:                 return null;
        }
    }

    public static void setPlayerName() {
        final String oldPlayerName = FModel.getPreferences().getPref(FPref.PLAYER_NAME);

        String newPlayerName;
        try {
            if (StringUtils.isBlank(oldPlayerName)) {
                newPlayerName = getVerifiedPlayerName(getPlayerNameUsingFirstTimePrompt(), oldPlayerName);
            } else {
                newPlayerName = getVerifiedPlayerName(getPlayerNameUsingStandardPrompt(oldPlayerName), oldPlayerName);
            }
        } catch (final IllegalStateException ise){
            //now is not a good time for this...
            newPlayerName = StringUtils.isBlank(oldPlayerName) ? "Human" : oldPlayerName;
        }

        FModel.getPreferences().setPref(FPref.PLAYER_NAME, newPlayerName);
        FModel.getPreferences().save();

        if (StringUtils.isBlank(oldPlayerName) && !newPlayerName.equals("Human")) {
            showThankYouPrompt(newPlayerName);
        }
    }

    public static void setServerPort() {
        final int oldPort = FModel.getNetPreferences().getPrefInt(ForgeNetPreferences.FNetPref.NET_PORT);
        int newPort = getServerPortPrompt(oldPort);
        FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.NET_PORT, String.valueOf(newPort));
        FModel.getNetPreferences().save();
    }

    private static void showThankYouPrompt(final String playerName) {
        SOptionPane.showMessageDialog("Thank you, " + playerName + ". "
                + "You will not be prompted again but you can change\n"
                + "your name at any time using the \"Player Name\" setting in Preferences\n"
                + "or via the constructed match setup screen\n");
    }

    private static String getPlayerNameUsingFirstTimePrompt() {
        return SOptionPane.showInputDialog(
                "By default, Forge will refer to you as the \"Human\" during gameplay.\n" +
                        "If you would prefer a different name please enter it now.",
                        "Personalize Forge Gameplay",
                        SOptionPane.QUESTION_ICON);
    }

    private static String getPlayerNameUsingStandardPrompt(final String playerName) {
        return SOptionPane.showInputDialog(
                "Please enter a new name. (alpha-numeric only)",
                "Personalize Forge Gameplay",
                null,
                playerName);
    }

    private static Integer getServerPortPrompt(final Integer serverPort) {
        String input = SOptionPane.showInputDialog(
                localizer.getMessage("sOPServerPromptMessage"),
                localizer.getMessage("sOPServerPromptTitle"),
                null,
                serverPort.toString(),
                null,
                true
        );
        Integer port;
        try {
             port = Integer.parseInt(input);
        } catch (NumberFormatException nfe) {
            SOptionPane.showErrorDialog(localizer.getMessage("sOPServerPromptError", input));
            return serverPort;
        }
        if(port < 0 || port > 65535) {
            SOptionPane.showErrorDialog(localizer.getMessage("sOPServerPromptError", input));
            return serverPort;
        }
        return  port;
    }

    private static String getVerifiedPlayerName(String newName, final String oldName) {
        if (newName == null || !StringUtils.isAlphanumericSpace(newName)) {
            newName = (StringUtils.isBlank(oldName) ? "Human" : oldName);
        } else if (StringUtils.isWhitespace(newName)) {
            newName = "Human";
        } else {
            newName = newName.trim();
        }
        return newName;
    }


}
