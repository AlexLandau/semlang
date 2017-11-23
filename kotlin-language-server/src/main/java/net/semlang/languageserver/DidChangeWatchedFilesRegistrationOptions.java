package net.semlang.languageserver;

import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DynamicRegistrationCapabilities;
import org.eclipse.lsp4j.jsonrpc.validation.NonNull;
import org.eclipse.xtext.xbase.lib.Pure;
import org.eclipse.xtext.xbase.lib.util.ToStringBuilder;

import java.util.ArrayList;
import java.util.List;

public class DidChangeWatchedFilesRegistrationOptions {
    @NonNull
    private List<FileSystemWatcher> watchers;

    public DidChangeWatchedFilesRegistrationOptions() {
        this(new ArrayList<FileSystemWatcher>());
    }

    public DidChangeWatchedFilesRegistrationOptions(@NonNull final List<FileSystemWatcher> watchers) {
        this.watchers = watchers;
    }

    @Pure
    @NonNull
    public List<FileSystemWatcher> getWatchers() {
        return this.watchers;
    }

    public void setWatchers(@NonNull final List<FileSystemWatcher> changes) {
        this.watchers = watchers;
    }

    @Override
    @Pure
    public String toString() {
        ToStringBuilder b = new ToStringBuilder(this);
        b.add("watchers", this.watchers);
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
        DidChangeWatchedFilesRegistrationOptions other = (DidChangeWatchedFilesRegistrationOptions) obj;
        if (this.watchers == null) {
            if (other.watchers != null)
                return false;
        } else if (!this.watchers.equals(other.watchers))
            return false;
        return true;
    }

    @Override
    @Pure
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((this.watchers== null) ? 0 : this.watchers.hashCode());
        return result;
    }
}
