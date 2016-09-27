package semlang;

import java.util.List;

public class Block {
    private final List<Assignment> assignments;

    private Block(List<Assignment> assignments) {
        this.assignments = assignments;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((assignments == null) ? 0 : assignments.hashCode());
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
        Block other = (Block) obj;
        if (assignments == null) {
            if (other.assignments != null)
                return false;
        } else if (!assignments.equals(other.assignments))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "Block [assignments=" + assignments + "]";
    }
}
