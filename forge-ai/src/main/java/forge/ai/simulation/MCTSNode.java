package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A node in the MCTS search tree.
 *
 * Each node represents a game state reached by playing a sequence of actions
 * from the root. Stores visit statistics for UCB1 selection.
 * Game states are NOT stored; they are reconstructed by replaying actions.
 */
public class MCTSNode {
    private final MCTSNode parent;
    private final MCTSAction action;
    private final List<MCTSNode> children = new ArrayList<>();

    // Legal actions at this node (discovered on first expansion)
    private List<MCTSAction> legalActions;
    private int nextUnexpandedIndex = 0;

    // Visit statistics
    private int visitCount = 0;
    private double totalReward = 0.0;

    // Terminal state
    private boolean terminal = false;
    private int terminalScore = 0;

    public MCTSNode(MCTSNode parent, MCTSAction action) {
        this.parent = parent;
        this.action = action;
    }

    /**
     * UCB1 score: Q/N + C * sqrt(ln(parentN) / N)
     * Unvisited nodes return positive infinity to ensure they get explored.
     */
    public double ucb1Score(double explorationConstant) {
        if (visitCount == 0) return Double.MAX_VALUE;
        double exploitation = totalReward / visitCount;
        double exploration = explorationConstant * Math.sqrt(Math.log(parent.visitCount) / visitCount);
        return exploitation + exploration;
    }

    /**
     * Select the child with the highest UCB1 score.
     */
    public MCTSNode selectChild(double explorationConstant) {
        MCTSNode best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (MCTSNode child : children) {
            double score = child.ucb1Score(explorationConstant);
            if (score > bestScore) {
                bestScore = score;
                best = child;
            }
        }
        return best;
    }

    /**
     * Whether all legal actions have been expanded as children.
     */
    public boolean isFullyExpanded() {
        return legalActions != null && nextUnexpandedIndex >= legalActions.size();
    }

    /**
     * Whether legal actions have been discovered for this node.
     */
    public boolean hasLegalActions() {
        return legalActions != null;
    }

    /**
     * Set the legal actions available at this node.
     */
    public void setLegalActions(List<MCTSAction> actions) {
        this.legalActions = actions;
        this.nextUnexpandedIndex = 0;
    }

    /**
     * Get the next action that hasn't been expanded yet.
     */
    public MCTSAction getNextUnexpandedAction() {
        if (legalActions == null || nextUnexpandedIndex >= legalActions.size()) {
            return null;
        }
        return legalActions.get(nextUnexpandedIndex);
    }

    /**
     * Create and add a child node for the given action, advancing the unexpanded index.
     */
    public MCTSNode expand(MCTSAction childAction) {
        MCTSNode child = new MCTSNode(this, childAction);
        children.add(child);
        nextUnexpandedIndex++;
        return child;
    }

    /**
     * Backpropagate a reward value up the tree from this node to root.
     */
    public void backpropagate(double reward) {
        MCTSNode node = this;
        while (node != null) {
            node.visitCount++;
            node.totalReward += reward;
            node = node.parent;
        }
    }

    /**
     * Get the best child by visit count (for final move selection).
     * Most-visited child is the standard MCTS selection criterion.
     */
    public MCTSNode bestChild() {
        MCTSNode best = null;
        int bestVisits = -1;
        for (MCTSNode child : children) {
            if (child.visitCount > bestVisits) {
                bestVisits = child.visitCount;
                best = child;
            }
        }
        return best;
    }

    /**
     * Get the action path from root to this node (excluding root).
     */
    public List<MCTSAction> getActionPath() {
        List<MCTSAction> path = new ArrayList<>();
        MCTSNode node = this;
        while (node.parent != null) {
            path.add(node.action);
            node = node.parent;
        }
        Collections.reverse(path);
        return path;
    }

    public MCTSAction getAction() { return action; }
    public MCTSNode getParent() { return parent; }
    public int getVisitCount() { return visitCount; }
    public double getMeanReward() { return visitCount == 0 ? 0.0 : totalReward / visitCount; }
    public List<MCTSNode> getChildren() { return children; }
    public boolean isTerminal() { return terminal; }
    public int getTerminalScore() { return terminalScore; }
    public boolean isLeaf() { return children.isEmpty(); }

    public void setTerminal(boolean terminal) { this.terminal = terminal; }
    public void setTerminalScore(int score) { this.terminalScore = score; }

    @Override
    public String toString() {
        String actionStr = action != null ? action.toString() : "ROOT";
        return String.format("%s [N=%d, Q=%.3f]", actionStr, visitCount, getMeanReward());
    }
}
