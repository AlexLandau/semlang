package semlang;

import java.util.List;

public class Package {
    private final List<String> packagePathComponents;

    private Package(List<String> packagePathComponents) {
        this.packagePathComponents = packagePathComponents;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((packagePathComponents == null) ? 0 : packagePathComponents.hashCode());
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
        Package other = (Package) obj;
        if (packagePathComponents == null) {
            if (other.packagePathComponents != null)
                return false;
        } else if (!packagePathComponents.equals(other.packagePathComponents))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return "Package [packagePathComponents=" + packagePathComponents + "]";
    }
}
