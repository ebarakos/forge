package forge.ai.nn;

public enum DecisionType {
    SPELL_SELECTION,    // chooseSpellAbilityToPlay — pick spell from candidates
    MULLIGAN,           // mulliganKeepHand, tuckCardsViaMulligan
    ATTACK,             // declareAttackers — per-creature binary: attack or not
    BLOCK,              // declareBlockers — per-attacker: choose blocker or no-block
    CARD_CHOICE,        // generic pick-from-list (sacrifice, discard, target, etc.)
    BOOLEAN,            // confirmAction, chooseBinary, etc.
    NUMBER,             // chooseNumber, announceRequirements, etc.
    GENERIC             // fallback for anything else
}
