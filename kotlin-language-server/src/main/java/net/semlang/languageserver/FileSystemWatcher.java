package net.semlang.languageserver;

import org.eclipse.lsp4j.jsonrpc.validation.NonNull;
import org.eclipse.xtext.xbase.lib.Pure;
import org.eclipse.xtext.xbase.lib.util.ToStringBuilder;

public class FileSystemWatcher {
    @NonNull
    private String globPattern;

    private WatchKind kind;

    public FileSystemWatcher() {
    }

    public FileSystemWatcher(@NonNull final String globPattern) {
        this.globPattern = globPattern;
    }

    @Pure
    @NonNull
    public String getGlobPattern() {
        return this.globPattern;
    }

    public void setLabel(@NonNull final String globPattern) {
        this.globPattern = globPattern;
    }

    @Pure
    public WatchKind getKind() {
        return this.kind;
    }

    public void setKind(final WatchKind kind) {
        this.kind = kind;
    }

    @Override
    @Pure
    public String toString() {
        ToStringBuilder b = new ToStringBuilder(this);
        b.add("globPattern", this.globPattern);
        b.add("kind", this.kind);
        return b.toString();
    }

    @Override
    @Pure
    public boolean equals(final Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        FileSystemWatcher other = (FileSystemWatcher) obj;
        if (this.globPattern == null) {
            if (other.globPattern != null)
                return false;
        } else if (!this.globPattern.equals(other.globPattern))
            return false;
        if (this.kind == null) {
            if (other.kind != null)
                return false;
        } else if (!this.kind.equals(other.kind))
            return false;
        return true;
    }

    @Override
    @Pure
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((this.globPattern== null) ? 0 : this.globPattern.hashCode());
        result = prime * result + ((this.kind== null) ? 0 : this.kind.hashCode());
        return result;
    }
}
