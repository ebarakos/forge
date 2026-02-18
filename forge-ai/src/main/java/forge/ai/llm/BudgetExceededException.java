package forge.ai.llm;

public class BudgetExceededException extends LLMException {
    private final double spent;
    private final double budget;

    public BudgetExceededException(double spent, double budget) {
        super("LLM budget exceeded: $" + String.format("%.4f", spent)
              + " > $" + String.format("%.4f", budget));
        this.spent = spent;
        this.budget = budget;
    }

    public double getSpent() { return spent; }
    public double getBudget() { return budget; }
}
