package semlang;

public class IfExpression implements Expression {
    private final Expression condition;
    private final Block ifResult;
    private final Block elseResult;

    private IfExpression(Expression condition, Block ifResult, Block elseResult) {
        this.condition = condition;
        this.ifResult = ifResult;
        this.elseResult = elseResult;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((condition == null) ? 0 : condition.hashCode());
        result = prime * result + ((elseResult == null) ? 0 : elseResult.hashCode());
        result = prime * result + ((ifResult == null) ? 0 : ifResult.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        IfExpression other = (IfExpression) obj;
        if (condition == null) {
            if (other.condition != null)
                return false;
        } else if (!condition.equals(other.condition))
            return false;
        if (elseResult == null) {
            if (other.elseResult != null)
                return false;
        } else if (!elseResult.equals(other.elseResult))
            return false;
        if (ifResult == null) {
            if (other.ifResult != null)
                return false;
        } else if (!ifResult.equals(other.ifResult))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "IfExpression [condition=" + condition + ", ifResult=" + ifResult + ", elseResult=" + elseResult + "]";
    }
}
