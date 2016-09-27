package semlang;

public class FunctionId {
    private final Package thePackage;
    private final String name;

    private FunctionId(Package thePackage, String name) {
        this.thePackage = thePackage;
        this.name = name;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((name == null) ? 0 : name.hashCode());
        result = prime * result + ((thePackage == null) ? 0 : thePackage.hashCode());
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
        FunctionId other = (FunctionId) obj;
        if (name == null) {
            if (other.name != null)
                return false;
        } else if (!name.equals(other.name))
            return false;
        if (thePackage == null) {
            if (other.thePackage != null)
                return false;
        } else if (!thePackage.equals(other.thePackage))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "FunctionId [thePackage=" + thePackage + ", name=" + name + "]";
    }
}
