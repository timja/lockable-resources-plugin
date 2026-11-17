/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Util;
import java.util.Objects;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;

/**
 * Who holds a lockable resource: a build (by {@link hudson.model.Run#getExternalizableId}), a client of the remote lock
 * API (by lock id), a user who reserved it, or nobody. The build need not run on this controller: with a {@link
 * ResourceStateStore} shared by several controllers, it may run on another one. Immutable; the {@code with…} methods
 * return the state after a change.
 *
 * <p>A resource may be locked and reserved at once: a user who steals a resource reserves it while the build keeps its
 * lock until it ends.
 */
@Restricted(Beta.class)
public final class ResourceState {

    /** Nobody holds the resource. */
    public static final ResourceState FREE = new ResourceState(null, null, null, "", false, 0);

    @CheckForNull
    private final String build;

    @CheckForNull
    private final String remoteLock;

    @CheckForNull
    private final String reservedBy;

    @NonNull
    private final String reason;

    private final boolean stolen;

    private final long since;

    /**
     * @param build the externalizable id of the build holding the lock
     * @param remoteLock the id of the remote lock holding the lock
     * @param reservedBy the user who reserved the resource
     * @param reason why it is locked or reserved
     * @param stolen whether the reservation was taken from a holder
     * @param since when it was last locked or reserved, in milliseconds since the epoch, 0 if unknown
     */
    public ResourceState(
            @CheckForNull String build,
            @CheckForNull String remoteLock,
            @CheckForNull String reservedBy,
            @CheckForNull String reason,
            boolean stolen,
            long since) {
        this.build = build;
        this.remoteLock = remoteLock;
        this.reservedBy = Util.fixEmptyAndTrim(reservedBy);
        this.reason = Util.fixNull(reason);
        this.stolen = stolen;
        this.since = since;
    }

    @CheckForNull
    public String getBuild() {
        return build;
    }

    @CheckForNull
    public String getRemoteLock() {
        return remoteLock;
    }

    @CheckForNull
    public String getReservedBy() {
        return reservedBy;
    }

    @NonNull
    public String getReason() {
        return reason;
    }

    public boolean isStolen() {
        return stolen;
    }

    public long getSince() {
        return since;
    }

    /** Whether nobody holds the resource. */
    public boolean isFree() {
        return build == null && remoteLock == null && reservedBy == null;
    }

    /** Locked by a build. A reason that is empty keeps the current one. */
    @NonNull
    public ResourceState withBuild(@NonNull String build, @CheckForNull String reason) {
        return new ResourceState(
                build,
                remoteLock,
                reservedBy,
                reason == null || reason.isEmpty() ? this.reason : reason,
                stolen,
                System.currentTimeMillis());
    }

    /** Released by its build (or by whoever unlocks it). */
    @NonNull
    public ResourceState withoutBuild() {
        return new ResourceState(null, remoteLock, reservedBy, null, stolen, 0);
    }

    @NonNull
    public ResourceState withRemoteLock(@CheckForNull String lockId) {
        return new ResourceState(build, lockId, reservedBy, reason, stolen, since);
    }

    /** Reserved by a user, or no longer reserved ({@code null}), keeping a build's lock. */
    @NonNull
    public ResourceState withReservation(@CheckForNull String user, @CheckForNull String reason, boolean stolen) {
        if (user == null) {
            return new ResourceState(build, remoteLock, null, null, false, 0);
        }
        return new ResourceState(build, remoteLock, user, reason, stolen, System.currentTimeMillis());
    }

    @NonNull
    public ResourceState withReason(@CheckForNull String reason) {
        return new ResourceState(build, remoteLock, reservedBy, reason, stolen, since);
    }

    @NonNull
    public ResourceState withSince(long since) {
        return new ResourceState(build, remoteLock, reservedBy, reason, stolen, since);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ResourceState s
                && stolen == s.stolen
                && since == s.since
                && Objects.equals(build, s.build)
                && Objects.equals(remoteLock, s.remoteLock)
                && Objects.equals(reservedBy, s.reservedBy)
                && reason.equals(s.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(build, remoteLock, reservedBy, reason, stolen, since);
    }

    @Override
    public String toString() {
        return isFree()
                ? "free"
                : "build=" + build + " remoteLock=" + remoteLock + " reservedBy=" + reservedBy + " stolen=" + stolen;
    }
}
