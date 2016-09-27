package semlang;

import java.util.List;

public class FunctionCallExpression implements Expression {
    private final FunctionId functionId;
    private final List<Expression> arguments;

    private FunctionCallExpression(FunctionId functionId, List<Expression> arguments) {
        this.functionId = functionId;
        this.arguments = arguments;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((arguments == null) ? 0 : arguments.hashCode());
        result = prime * result + ((functionId == null) ? 0 : functionId.hashCode());
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
        FunctionCallExpression other = (FunctionCallExpression) obj;
        if (arguments == null) {
            if (other.arguments != null)
                return false;
        } else if (!arguments.equals(other.arguments))
            return false;
        if (functionId == null) {
            if (other.functionId != null)
                return false;
        } else if (!functionId.equals(other.functionId))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "FunctionCallExpression [functionId=" + functionId + ", arguments=" + arguments + "]";
    }
}
