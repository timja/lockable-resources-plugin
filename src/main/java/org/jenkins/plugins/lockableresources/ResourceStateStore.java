/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Keeps the {@link ResourceState} of every lockable resource: which build, remote lock or user holds it. The resources
 * themselves (names, labels, properties) stay in {@link LockableResourcesManager}.
 *
 * <p>The default keeps the state in memory and saves it with {@link LockableResourcesManager}. Another implementation
 * may keep it where several controllers share it: a resource is then held by one build of the whole cluster, which may
 * run on any controller, and a build resumed on another controller still holds what it held. When the state changes
 * on another controller, the implementation calls {@link LockableResourcesManager#refreshQueue()}, so that builds
 * waiting here look again.
 *
 * <p>The first {@linkplain #isEnabled() enabled} implementation (by ordinal) is used.
 */
@Restricted(Beta.class)
public abstract class ResourceStateStore implements ExtensionPoint {

    /** The state of a resource. Called often, holding {@link LockableResourcesManager#syncResources}: no I/O. */
    @NonNull
    public abstract ResourceState get(@NonNull LockableResource resource);

    /**
     * Changes the state of several resources at once, or of none: of two controllers taking the same resources at once,
     * one succeeds. Called holding {@link LockableResourcesManager#syncResources}.
     *
     * @param change the next state of a resource from its current one, or {@code null} if the change does not apply to
     *     it (it is held by someone else, say): then no resource changes
     * @return whether the resources changed
     */
    public abstract boolean update(
            @NonNull List<LockableResource> resources, @NonNull UnaryOperator<ResourceState> change);

    /**
     * Whether other controllers share the state. A resource may then be held by a build this controller does not run,
     * or does not know at all; it is the store's job to free resources held by builds gone from every controller.
     */
    public boolean isShared() {
        return false;
    }

    /** Whether this store is in use (say, configured): the first one that is, by ordinal, keeps the state. */
    public boolean isEnabled() {
        return true;
    }

    @NonNull
    public static ResourceStateStore get() {
        for (ResourceStateStore store : ExtensionList.lookup(ResourceStateStore.class)) {
            if (store.isEnabled()) {
                return store;
            }
        }
        throw new IllegalStateException("no ResourceStateStore"); // the default one is always enabled
    }

    /** Whether a resource in this state may be taken: nobody holds it, or only a build that is gone. */
    static boolean isFree(@NonNull ResourceStateStore store, @NonNull ResourceState s) {
        if (s.getRemoteLock() != null || s.getReservedBy() != null) {
            return false;
        }
        @CheckForNull String build = s.getBuild();
        return build == null || (!store.isShared() && hudson.model.Run.fromExternalizableId(build) == null);
    }

    /** In memory, saved with {@link LockableResourcesManager}. */
    @Extension(ordinal = -1000)
    @Restricted(NoExternalUse.class)
    public static final class Local extends ResourceStateStore {

        @NonNull
        @Override
        public ResourceState get(@NonNull LockableResource resource) {
            return resource.getLocalState();
        }

        @Override
        public boolean update(@NonNull List<LockableResource> resources, @NonNull UnaryOperator<ResourceState> change) {
            synchronized (LockableResourcesManager.syncResources) {
                List<ResourceState> next = new ArrayList<>();
                for (LockableResource r : resources) {
                    ResourceState s = change.apply(r.getLocalState());
                    if (s == null) {
                        return false;
                    }
                    next.add(s);
                }
                for (int i = 0; i < resources.size(); i++) {
                    resources.get(i).setLocalState(next.get(i));
                }
                return true;
            }
        }
    }
}
