package semlang;

import java.util.List;

public class SemlangFunction {
    private final FunctionId id;
    private final List<FunctionArgument> arguments;
    private final Block contents;
    private final Type returnType;

    private SemlangFunction(FunctionId id, List<FunctionArgument> arguments, Block contents, Type returnType) {
        this.id = id;
        this.arguments = arguments;
        this.contents = contents;
        this.returnType = returnType;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((arguments == null) ? 0 : arguments.hashCode());
        result = prime * result + ((contents == null) ? 0 : contents.hashCode());
        result = prime * result + ((id == null) ? 0 : id.hashCode());
        result = prime * result + ((returnType == null) ? 0 : returnType.hashCode());
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
        SemlangFunction other = (SemlangFunction) obj;
        if (arguments == null) {
            if (other.arguments != null)
                return false;
        } else if (!arguments.equals(other.arguments))
            return false;
        if (contents == null) {
            if (other.contents != null)
                return false;
        } else if (!contents.equals(other.contents))
            return false;
        if (id == null) {
            if (other.id != null)
                return false;
        } else if (!id.equals(other.id))
            return false;
        if (returnType == null) {
            if (other.returnType != null)
                return false;
        } else if (!returnType.equals(other.returnType))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "SemlangFunction [id=" + id + ", arguments=" + arguments + ", contents=" + contents + ", returnType="
                + returnType + "]";
    }
}
