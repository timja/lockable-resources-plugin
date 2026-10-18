/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Run;
import java.util.List;
import java.util.UUID;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Coordinates lockable resources with other Jenkins controllers that share them, so that a resource is locked or
 * reserved on at most one of them at a time.
 *
 * <p>Each controller keeps its own {@link LockableResourcesManager}, with its own resources and its own queue of
 * waiting builds. The manager tells the coordinator what it is about to do and what it did; the coordinator tells the
 * manager about changes made elsewhere through the manager's public API ({@link
 * LockableResourcesManager#unlockResources(List, Run)}, the {@link LockableResource} setters, {@link
 * LockableResource#setHeldElsewhereBy} and {@link LockableResourcesManager#refreshQueue()}).
 *
 * <p>The methods about locks and reservations are called while holding {@link
 * LockableResourcesManager#syncResources}; the ones about definitions after the change was saved. With no coordinator
 * installed, resources are local to this controller, as they always were.
 *
 * <p>A coordinator that learns that a resource this controller holds for a remote lock was freed or taken on another
 * controller ends that lock with {@link LockableResourcesManager#releaseRemoteLock(String)}.
 */
@Restricted(Beta.class)
public interface LockCoordinator extends ExtensionPoint {

    /**
     * About to lock resources for a build. All or none: if the result is false, nothing is held elsewhere on behalf
     * of the build.
     *
     * @return false if some resource is held elsewhere; the build then keeps waiting, and is offered the resources
     *     again when the coordinator calls {@link LockableResourcesManager#refreshQueue()}
     */
    boolean tryLock(@NonNull List<LockableResource> resources, @NonNull Run<?, ?> build);

    /** A build released a resource here. */
    void released(@NonNull LockableResource resource, @NonNull Run<?, ?> build);

    /**
     * About to lock resources for a client of the remote lock API ({@code /lockable-resources/api/v1}), under the
     * remote lock's id. All or none.
     *
     * @return false if some resource is held elsewhere; the request then waits, or is skipped, as it would for a
     *     resource locked here
     */
    boolean tryLockForRemote(@NonNull List<LockableResource> resources, @NonNull String lockId);

    /** A remote lock released a resource here: released by its client, or force-released on the resources page. */
    void releasedForRemote(@NonNull LockableResource resource, @NonNull String lockId);

    /**
     * The id of a new remote lock. Its client polls, renews and releases the lock under this id, possibly through
     * another controller sharing the resources, which can then tell which controller holds the lock from it.
     *
     * @return a new id, unique across the controllers, made of URL path-safe characters
     */
    @NonNull
    default String newRemoteLockId() {
        return UUID.randomUUID().toString();
    }

    /**
     * About to reserve resources for a user. All or none.
     *
     * @return false if some resource is locked or reserved elsewhere
     */
    boolean tryReserve(@NonNull List<LockableResource> resources, @NonNull String user, @CheckForNull String reason);

    /**
     * A resource is about to be given to a user whoever holds it now: stolen from a build ({@code stolen}), or a
     * reservation reassigned. Called before the resource is freed here. Afterwards the resource is no longer
     * {@linkplain #isHeldElsewhere held elsewhere}: it is reserved here.
     */
    void reservedForcibly(
            @NonNull LockableResource resource, @NonNull String user, @CheckForNull String reason, boolean stolen);

    /** A reservation was ended here. */
    void unreserved(@NonNull LockableResource resource);

    /** A resource was unlocked or reset from the resources page: it is to be freed everywhere. */
    void freed(@NonNull LockableResource resource);

    /**
     * Whether a resource is held elsewhere, as last known. Answered from memory, without remote calls: this is
     * called while rendering pages and while holding the manager's lock.
     */
    boolean isHeldElsewhere(@NonNull LockableResource resource);

    /** A resource was created or edited (description, labels, properties, note) on the resources page. */
    void definitionChanged(@NonNull LockableResource resource);

    /** A resource was deleted on the resources page. */
    void definitionDeleted(@NonNull String name);

    /**
     * The coordinator in use.
     *
     * @return the first one registered, or null if resources are local to this controller
     */
    @CheckForNull
    static LockCoordinator get() {
        ExtensionList<LockCoordinator> all = ExtensionList.lookup(LockCoordinator.class);
        return all.isEmpty() ? null : all.get(0);
    }
}
