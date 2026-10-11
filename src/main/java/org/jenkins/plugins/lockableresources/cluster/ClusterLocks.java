/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.cluster;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStores;
import jenkins.persistence.RunningBuilds;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkins.plugins.lockableresources.actions.LockedResourcesBuildAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionListener;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Makes locks exclusive across controllers which share a Jenkins persistence store (cloud-native Jenkins).
 *
 * <p>Every controller keeps its own {@link LockableResourcesManager} (resources are configured identically,
 * e.g. by configuration as code), and its own waiting queue. Taking a resource additionally creates
 * {@code lockable-resources/<name>} in the store, a create-only write: of several controllers taking a resource
 * at once, exactly one succeeds, and the others queue as if it were locked locally. Releasing deletes the key.
 * Other controllers show the resource as locked elsewhere (through {@link LockableResource#setRemoteLockedBy})
 * and, when it is released, offer it to their own waiters.
 *
 * <p>A lock belongs to its build: if the build is adopted by another controller after its own died, the new owner
 * takes the lock over; if the build is gone for good, the lock is released.
 */
@Restricted(NoExternalUse.class)
public final class ClusterLocks {

    private static final Logger LOGGER = Logger.getLogger(ClusterLocks.class.getName());

    static final String PREFIX = "lockable-resources";
    /** Prefix of {@link LockableResource#getRemoteLockedBy} for resources locked on another controller. */
    static final String REMOTE = "cluster:";

    /** How this controller is named in lock holders, as in core's running build placeholders. */
    private static final String CONTROLLER =
            System.getProperty(RunningBuilds.class.getName() + ".controllerName", hostName());

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "unknown";
        }
    }

    private ClusterLocks() {}

    @CheckForNull
    static PersistenceStore store() {
        return PersistenceStores.get();
    }

    static String key(String resource) {
        return PREFIX + "/" + resource;
    }

    /** Whether locks are shared between controllers. */
    public static boolean isActive() {
        return store() != null;
    }

    private static byte[] holder(Run<?, ?> build) {
        PersistenceStore store = store();
        return ("build=" + build.getExternalizableId() + "\norigin=" + store.getOrigin() + "\ncontroller=" + CONTROLLER
                        + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    @CheckForNull
    static Properties read(PersistenceStore store, String resource) throws IOException {
        PersistenceStore.Entry e = store.read(key(resource));
        if (e == null) {
            return null;
        }
        Properties p = new Properties();
        p.load(new StringReader(new String(e.content(), StandardCharsets.UTF_8)));
        return p;
    }

    /**
     * Takes resources for a build across the cluster, all or none.
     * Must be called under {@link LockableResourcesManager#syncResources}.
     *
     * @return false if some resource is held on another controller (it is then shown as locked there)
     */
    public static boolean acquire(@NonNull List<LockableResource> resources, @NonNull Run<?, ?> build) {
        PersistenceStore store = store();
        if (store == null) {
            return true;
        }
        List<String> taken = new ArrayList<>();
        try {
            for (LockableResource r : resources) {
                try {
                    store.write(key(r.getName()), holder(build), PersistenceStore.ABSENT);
                    taken.add(r.getName());
                } catch (ConflictException e) {
                    Properties p = read(store, r.getName());
                    if (p != null && build.getExternalizableId().equals(p.getProperty("build"))) {
                        continue; // already ours (e.g. after adoption)
                    }
                    LOGGER.fine(() -> r.getName() + " is locked or reserved on another controller: " + p);
                    show(r, p);
                    rollback(store, taken);
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot lock " + resources + " across controllers; not locking for now", e);
            rollback(store, taken);
            return false;
        }
    }

    private static void rollback(PersistenceStore store, List<String> taken) {
        for (String name : taken) {
            try {
                store.delete(key(name), PersistenceStore.ANY);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to release " + name + " after a failed lock", e);
            }
        }
    }

    /**
     * Releases a resource across the cluster, if this build holds it.
     */
    public static void release(@NonNull LockableResource resource, @NonNull Run<?, ?> build) {
        PersistenceStore store = store();
        if (store == null) {
            return;
        }
        try {
            Properties p = read(store, resource.getName());
            if (p != null && build.getExternalizableId().equals(p.getProperty("build"))) {
                store.delete(key(resource.getName()), PersistenceStore.ANY);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to release " + resource.getName() + " across controllers", e);
        }
    }

    // ------------------------------------------------------------------ reservations (Target 15)

    static final String RESERVATION = "reservation";

    private static byte[] reservation(String user, @CheckForNull String reason, boolean stolen) {
        PersistenceStore store = store();
        Properties p = new Properties();
        p.setProperty("kind", RESERVATION);
        p.setProperty("user", user);
        if (reason != null) {
            p.setProperty("reason", reason);
        }
        p.setProperty("stolen", String.valueOf(stolen));
        p.setProperty("origin", store.getOrigin());
        p.setProperty("controller", CONTROLLER);
        StringWriter w = new StringWriter();
        try {
            p.store(w, null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return w.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isReservation(@CheckForNull Properties p) {
        return p != null && RESERVATION.equals(p.getProperty("kind"));
    }

    /**
     * Reserves resources for a user across the cluster, all or none: like a lock, a reservation is exclusive.
     * Must be called under {@link LockableResourcesManager#syncResources}.
     *
     * @return false if some resource is locked or reserved on another controller
     */
    public static boolean reserve(@NonNull List<LockableResource> resources, @NonNull String user, @CheckForNull String reason) {
        PersistenceStore store = store();
        if (store == null) {
            return true;
        }
        List<String> taken = new ArrayList<>();
        try {
            for (LockableResource r : resources) {
                try {
                    store.write(key(r.getName()), reservation(user, reason, false), PersistenceStore.ABSENT);
                    taken.add(r.getName());
                } catch (ConflictException e) {
                    show(r, read(store, r.getName()));
                    rollback(store, taken);
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot reserve " + resources + " across controllers", e);
            rollback(store, taken);
            return false;
        }
    }

    /** Ends a reservation across the cluster (not a lock held by a build). */
    public static void unreserve(@NonNull LockableResource r) {
        PersistenceStore store = store();
        if (store == null) {
            return;
        }
        try {
            if (isReservation(read(store, r.getName()))) {
                store.delete(key(r.getName()), PersistenceStore.ANY);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot unreserve " + r.getName() + " across controllers", e);
        }
    }

    /** Frees a resource across the cluster, whoever holds it (reset, or an unlock from the resources page). */
    public static void free(@NonNull LockableResource r) {
        PersistenceStore store = store();
        if (store == null) {
            return;
        }
        try {
            store.delete(key(r.getName()), PersistenceStore.ANY);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot free " + r.getName() + " across controllers", e);
        }
    }

    /**
     * Gives a resource to a user across the cluster, whoever holds it now: reassigning a reservation, or stealing
     * a resource locked by a build (its controller then frees it, as for a steal there).
     */
    public static void reserveForcibly(@NonNull LockableResource r, @NonNull String user, @CheckForNull String reason, boolean stolen) {
        PersistenceStore store = store();
        if (store == null) {
            return;
        }
        try {
            store.write(key(r.getName()), reservation(user, reason, stolen), PersistenceStore.ANY);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot reserve " + r.getName() + " across controllers", e);
        }
    }

    /** Whether a resource is held on another controller, as this one shows it. */
    public static boolean isHeldElsewhere(@NonNull LockableResource r) {
        return r.getRemoteLockedBy() != null && r.getRemoteLockedBy().startsWith(REMOTE);
    }

    /** Shows how a resource is held on another controller. */
    private static void show(LockableResource r, @CheckForNull Properties p) {
        if (p == null) {
            return;
        }
        if (isReservation(p)) {
            r.setRemoteLockedBy(null);
            r.setReservedBy(p.getProperty("user"));
            if (Boolean.parseBoolean(p.getProperty("stolen"))) {
                r.setStolen();
            }
            r.setLockReason(p.getProperty("reason"));
        } else {
            r.setRemoteLockedBy(REMOTE + p.getProperty("build") + " on " + p.getProperty("controller"));
        }
    }

    /** Applies a lock or reservation taken, changed or released on another controller. */
    static void changedElsewhere(@NonNull String resource, boolean locked) {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        synchronized (LockableResourcesManager.syncResources) {
            LockableResource r = lrm.fromName(resource);
            if (r == null) {
                return;
            }
            if (locked) {
                try {
                    Properties p = read(store(), resource);
                    if (p != null && r.getBuild() != null
                            && !(r.getBuild().getExternalizableId().equals(p.getProperty("build")))) {
                        // a build here held it: stolen, or reset, from another controller; free it here as a steal does
                        LOGGER.info(() -> resource + " was taken from " + r.getBuild() + " on another controller");
                        lrm.unlockResources(new ArrayList<>(List.of(r)), r.getBuild());
                    }
                    if (p != null && r.getBuild() == null) {
                        show(r, p);
                    }
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Cannot read the lock of " + resource, e);
                }
            } else {
                if (r.getBuild() != null) {
                    // unlocked or reset from another controller
                    LOGGER.info(() -> resource + " was freed on another controller while " + r.getBuild() + " held it");
                    lrm.unlockResources(new ArrayList<>(List.of(r)), r.getBuild());
                }
                if (r.isReserved()) {
                    r.unReserve();
                }
                if (isHeldElsewhere(r)) {
                    r.setRemoteLockedBy(null);
                }
            }
        }
        if (!locked) {
            // a local waiter may get it now: Pipeline lock steps wait in the manager's queue, freestyle builds in
            // Jenkins'
            lrm.refreshQueue();
        }
    }

    // ------------------------------------------------------------------ definitions and notes (Target 15)

    /** Resources created, edited or noted on the resources page, shared: {@code lockable-resources-meta/<name>}. */
    static final String META = "lockable-resources-meta";

    /** Shares a resource's definition (description, labels, properties) and note, as edited on this controller. */
    public static void publishDefinition(@NonNull LockableResource r) {
        PersistenceStore store = store();
        if (store == null) {
            return;
        }
        LockableResource def = new LockableResource(r.getName());
        def.setDescription(r.getDescription());
        def.setLabelsFromString(r.getLabels());
        def.setNote(r.getNote());
        def.setProperties(r.getProperties());
        try {
            store.write(META + "/" + r.getName(), jenkins.model.Jenkins.XSTREAM2.toXML(def).getBytes(StandardCharsets.UTF_8),
                    PersistenceStore.ANY);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot share the definition of " + r.getName(), e);
        }
    }

    /** Stops sharing a resource deleted on this controller. */
    public static void deleteDefinition(@NonNull String name) {
        PersistenceStore store = store();
        if (store == null) {
            return;
        }
        try {
            store.delete(META + "/" + name, PersistenceStore.ANY);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Cannot stop sharing " + name, e);
        }
    }

    /** Applies a definition written or deleted on another controller (or found at startup). */
    static void definitionChanged(@NonNull String name, boolean written) {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        try {
            PersistenceStore.Entry e = written ? store().read(META + "/" + name) : null;
            synchronized (LockableResourcesManager.syncResources) {
                LockableResource r = lrm.fromName(name);
                if (e == null) {
                    if (!written && r != null && r.isFree()) {
                        lrm.removeResources(new ArrayList<>(List.of(r)));
                        lrm.save();
                    }
                    return;
                }
                LockableResource def = (LockableResource)
                        jenkins.model.Jenkins.XSTREAM2.fromXML(new String(e.content(), StandardCharsets.UTF_8));
                if (r == null) {
                    lrm.addResource(def, true);
                } else {
                    r.setDescription(def.getDescription());
                    r.setLabelsFromString(def.getLabels());
                    r.setNote(def.getNote());
                    r.setProperties(def.getProperties());
                    lrm.save();
                }
            }
        } catch (IOException | RuntimeException x) {
            LOGGER.log(Level.WARNING, "Cannot apply the shared definition of " + name, x);
        }
    }

    // ------------------------------------------------------------------ events

    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void subscribe() throws IOException {
        PersistenceStore store = store();
        if (store == null) {
            return;
        }
        store.subscribe(e -> {
            if (store.getOrigin().equals(e.origin())) {
                return;
            }
            if (e.key().startsWith(META + "/")) {
                definitionChanged(e.key().substring(META.length() + 1), e.kind() == PersistenceStore.ChangeEvent.Kind.WRITTEN);
                return;
            }
            if (!e.key().startsWith(PREFIX + "/")) {
                return;
            }
            String name = e.key().substring(PREFIX.length() + 1);
            changedElsewhere(name, e.kind() == PersistenceStore.ChangeEvent.Kind.WRITTEN);
        });
        for (String name : store.children(META)) {
            definitionChanged(name, true); // first: locks may be on resources only defined there
        }
        for (String name : store.children(PREFIX)) {
            changedElsewhere(name, true);
        }
    }

    /**
     * A build adopted by this controller (resumed after its own controller died) takes over its locks.
     */
    @Extension
    public static final class Adoption extends FlowExecutionListener {
        @Override
        public void onResumed(@NonNull FlowExecution execution) {
            PersistenceStore store = store();
            if (store == null) {
                return;
            }
            Run<?, ?> run;
            try {
                run = execution.getOwner().getExecutable() instanceof Run<?, ?> r ? r : null;
            } catch (IOException e) {
                return;
            }
            LockedResourcesBuildAction action = run == null ? null : run.getAction(LockedResourcesBuildAction.class);
            if (action == null) {
                return;
            }
            LockableResourcesManager lrm = LockableResourcesManager.get();
            synchronized (LockableResourcesManager.syncResources) {
                for (String name : action.getCurrentUsedResourceNames()) {
                    LockableResource r = lrm.fromName(name);
                    try {
                        Properties p = read(store, name);
                        if (r != null && p != null && run.getExternalizableId().equals(p.getProperty("build"))) {
                            r.setRemoteLockedBy(null);
                            r.setBuild(run);
                            store.write(key(name), holder(run), PersistenceStore.ANY);
                            LOGGER.info(() -> run + " took over its lock on " + name);
                        }
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Failed to take over the lock on " + name + " for " + run, e);
                    }
                }
            }
        }
    }

    /**
     * Releases locks held by builds which no longer run anywhere: their controller is gone and nobody adopted them.
     */
    @Extension
    public static final class Orphans extends AsyncPeriodicWork {
        public Orphans() {
            super("Release locks of builds gone with their controller");
        }

        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.SECONDS.toMillis(60);
        }

        @Override
        protected void execute(TaskListener listener) throws IOException {
            PersistenceStore store = store();
            Set<String> live = store == null ? null : store.liveOrigins();
            if (live == null) {
                return;
            }
            for (String name : store.children(PREFIX)) {
                Properties p = read(store, name);
                if (p == null || isReservation(p) || live.contains(p.getProperty("origin"))) {
                    continue; // a reservation belongs to its user, not to the controller it was made on
                }
                String build = p.getProperty("build", "");
                int hash = build.lastIndexOf('#');
                String jobKey = hash < 0 ? null : jobKey(build.substring(0, hash));
                if (jobKey != null && store.read(jobKey + "/running/" + build.substring(hash + 1) + ".xml") != null) {
                    continue; // still running (or being adopted) somewhere
                }
                LOGGER.info(() -> "Releasing " + name + ", held by " + build + " which is gone");
                store.delete(key(name), PersistenceStore.ANY);
                changedElsewhere(name, false);
            }
        }

        /** {@code folder/job} → {@code jobs/folder/jobs/job} */
        private static String jobKey(String fullName) {
            StringBuilder b = new StringBuilder();
            for (String part : fullName.split("/")) {
                b.append(b.length() == 0 ? "" : "/").append("jobs/").append(part);
            }
            return b.toString();
        }
    }
}
