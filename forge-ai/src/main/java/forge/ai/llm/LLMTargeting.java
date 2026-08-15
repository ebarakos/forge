package forge.ai.llm;

import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetChoices;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Target choice for {@link LLMFullController}.
 *
 * <p>Until this existed, an "LLM seat" never decided where a spell pointed. The
 * model chose <em>which</em> spell to cast; the targets on it had already been
 * set as a side effect of the heuristic AI's own {@code canPlaySa} check, so
 * face-versus-creature on a burn spell was decided by the heuristic under every
 * flag combination. Clearing the targets and hoping the engine asks again does
 * not work either: the engine asks through
 * {@link forge.ai.PlayerControllerAi#chooseTargetsFor}, which hands the question
 * straight back to the heuristic.
 *
 * <p>So this class re-points an already-targeted ability. The heuristic still
 * decides whether the ability is worth playing at all and <em>how many</em>
 * things it points at; the model decides <em>which</em> of the legal candidates
 * those are. Splitting it that way keeps a single decision in the model's hands
 * and leaves the count — which interacts with costs, divided damage and
 * "target up to X" wording — where it already worked.
 *
 * <p>Every step is checked against the engine, and any failure restores the
 * heuristic's original targets exactly, so the worst case is the behaviour that
 * shipped before. The whole class is unreachable unless
 * {@code FORGE_LLM_HEURISTIC_TARGETS} is off (see
 * {@link LLMFullController#HEURISTIC_TARGETS}).
 */
final class LLMTargeting {

    /**
     * Upper bound on candidates shown to the model. A prompt listing every card
     * in every graveyard is expensive and unreadable; the heuristic's own picks
     * are always kept in the list past the cap so the model can at least agree
     * with the baseline. Generous on purpose — a Pauper battlefield rarely
     * comes near it, so this is a guard against pathological lists, not a
     * shaping decision like the top-K spell cap.
     */
    private static final int MAX_TARGET_OPTIONS = 24;

    private final LLMFullController ctrl;

    LLMTargeting(LLMFullController ctrl) {
        this.ctrl = ctrl;
    }

    /**
     * Ask the model where {@code sa} should point, and apply the answer.
     *
     * @param sa        an ability whose targets the heuristic AI has already set
     * @param callLabel label for the LLM call, used in fallback and debug output
     * @return true when the targets on {@code sa} are the model's; false when
     *         they are the heuristic's, either because this decision was not
     *         eligible or because the call failed. False is never an error —
     *         the ability is left exactly as it arrived.
     */
    boolean chooseTargets(SpellAbility sa, String callLabel) {
        if (LLMFullController.HEURISTIC_TARGETS || ctrl.client == null) return false;
        // Feasibility loops ask "could this be cast?" dozens of times per turn
        // and throw the answer away. Paying for a target decision inside one
        // would be pure waste — the real decision comes later, on the ability
        // that is actually being played.
        if (LLMFullController.isCheckingFeasibility()) return false;
        // Same reasoning one level up: while the heuristic AI is being asked
        // what it thinks, any ability it targets along the way is a hypothetical.
        // A card that lets a player choose targets routes the heuristic's own
        // check back through chooseTargetsFor, and this is what stops one pass
        // over the option list from becoming one target call per option.
        if (LLMFullController.isConsultingHeuristic()) return false;
        if (sa == null || !sa.usesTargeting()) return false;
        if (sa.getActivatingPlayer() == null) return false;

        TargetRestrictions tgt = sa.getTargetRestrictions();
        if (tgt == null) return false;
        // Divided damage ("divided as you choose") is a second decision — how
        // much goes where — that the model is not being given here. Re-pointing
        // the targets while leaving the heuristic's allocation behind would
        // produce an ability that is legal but incoherent.
        if (sa.isDividedAsYouChoose()) return false;
        // Targets on the stack are spells, not entities, and are enumerated by a
        // different path in the engine. Counterspell target choice is worth
        // having and is not handled here.
        if (tgt.getZone() != null && tgt.getZone().contains(ZoneType.Stack)) return false;

        TargetChoices original = sa.getTargets() == null ? null : sa.getTargets().clone();
        int want = original == null ? 0 : original.size();
        // Nothing chosen means the heuristic pointed this at nothing — there is
        // no pick to move, and inventing one would change what gets played.
        if (want <= 0) return false;

        try {
            return retarget(sa, tgt, original, want, callLabel);
        } catch (Exception e) {
            restore(sa, original);
            if (ctrl.client.isDebug()) {
                System.err.println("[LLM] target choice failed, keeping heuristic targets: "
                        + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
            return false;
        }
    }

    /** The body of {@link #chooseTargets}, with the original targets guaranteed restorable. */
    private boolean retarget(SpellAbility sa, TargetRestrictions tgt, TargetChoices original,
                              int want, String callLabel) {
        // Candidates are enumerated with nothing targeted, because
        // getAllCandidates excludes whatever is targeted already — otherwise
        // the heuristic's own picks would be missing from their own option list.
        sa.resetTargets();
        List<GameEntity> candidates = collectCandidates(sa, tgt, original);

        if (candidates.size() <= want) {
            // Every legal target has to be taken, or there are none. No decision.
            restore(sa, original);
            return false;
        }

        Player self = ctrl.getPlayer();
        List<String> rendered = new ArrayList<>(candidates.size());
        Set<Integer> heuristicPicks = new LinkedHashSet<>();
        for (int i = 0; i < candidates.size(); i++) {
            GameEntity e = candidates.get(i);
            rendered.add(OptionSerializer.describeTargetCandidate(e, self));
            if (original.contains(e)) heuristicPicks.add(i);
        }

        String options = OptionSerializer.serializeTargetOptions(
                describeAbility(sa), rendered, want, heuristicPicks);
        String response = ctrl.callLLMRaw(
                PromptTemplates.chooseTargets(ctrl.getActionHistoryText(),
                        ctrl.buildGameStateWithHistory(), options),
                callLabel, LLMResponseSchema.TARGETS);
        if (response == null) {
            // The call already recorded a fallback, which is what strict mode
            // watches; here the ability just keeps the heuristic's targets.
            restore(sa, original);
            return false;
        }

        List<Integer> picks = ResponseParser.parseTargetIndices(response, candidates.size());
        int applied = 0;
        for (int idx : picks) {
            if (applied >= want) break;
            GameEntity entity = candidates.get(idx);
            if (sa.getTargets().contains(entity)) continue;
            // Asked per pick rather than once up front: uniqueness, "different
            // controllers", total-CMC caps and the like depend on what is
            // already targeted, so legality changes as the list is filled.
            if (!sa.canTarget(entity)) continue;
            if (sa.getTargets().add(entity)) applied++;
        }

        if (applied != want || !sa.isTargetNumberValid()) {
            ctrl.client.recordFallback(self.getName(), callLabel
                    + ": answer named " + applied + " legal target(s) where " + want
                    + " were required (" + candidates.size() + " offered)");
            restore(sa, original);
            return false;
        }

        logShadowMode(sa, callLabel, original);
        return true;
    }

    /**
     * Legal targets for {@code sa}, with the heuristic's own picks guaranteed
     * present. Call with the ability's targets already cleared.
     */
    private List<GameEntity> collectCandidates(SpellAbility sa, TargetRestrictions tgt,
                                                TargetChoices original) {
        List<GameEntity> all = tgt.getAllCandidates(sa, true);
        List<GameEntity> kept = new ArrayList<>(all.size());
        for (GameEntity e : all) {
            if (kept.size() >= MAX_TARGET_OPTIONS && !original.contains(e)) continue;
            kept.add(e);
        }
        return kept;
    }

    /** Put the heuristic's targets back exactly as they were. */
    private static void restore(SpellAbility sa, TargetChoices original) {
        if (original != null) {
            sa.setTargets(original);
        }
    }

    /**
     * What the model is choosing targets for: card name plus the effect text
     * ("deals 3 damage to any target").
     *
     * <p>Deliberately not {@code getStackDescription()}, which is written in
     * terms of the targets currently set. By the time this is called those have
     * been cleared, so it reads as a half-sentence — and before they were
     * cleared it would have repeated the heuristic's own answer inside the
     * question.
     */
    private static String describeAbility(SpellAbility sa) {
        Card host = sa.getHostCard();
        String name = host != null ? host.getName() : sa.toString();
        String desc = sa.getDescription();
        if (isBlank(desc) && host != null) desc = host.getOracleText();
        if (isBlank(desc)) return name;
        // Ability text comes out of the card script with the card's own name
        // written as the CARDNAME placeholder; spell it out rather than send
        // the model a word that appears nowhere else in the prompt.
        desc = desc.replace("CARDNAME", name).replace("NICKNAME", name)
                .replace('\n', ' ').trim();
        if (desc.length() > 150) desc = desc.substring(0, 147) + "...";
        return name + " — " + desc;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Log heuristic-versus-model divergence when shadow mode is on. Called only
     * once the new targets are known legal, so a DIVERGE line always describes
     * a target change that really happened.
     */
    private void logShadowMode(SpellAbility sa, String callLabel, TargetChoices original) {
        if (!LLMFullController.SHADOW_MODE) return;
        boolean agree = sa.getTargets().size() == original.size();
        if (agree) {
            for (GameObject o : sa.getTargets()) {
                if (!original.contains(o)) { agree = false; break; }
            }
        }
        System.err.println("[LLM SHADOW] " + callLabel
                + " on " + describeAbility(sa)
                + " heuristic=" + names(original)
                + " llm=" + names(sa.getTargets())
                + (agree ? " AGREE" : " DIVERGE"));
    }

    private static String names(Iterable<GameObject> targets) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (GameObject o : targets) {
            if (!first) sb.append(',');
            first = false;
            sb.append(o instanceof GameEntity ? ((GameEntity) o).getName() : String.valueOf(o));
        }
        return sb.append(']').toString();
    }
}
