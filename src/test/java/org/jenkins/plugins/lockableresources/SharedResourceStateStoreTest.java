package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import org.jenkins.plugins.lockableresources.queue.LockRunListener;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** A {@link ResourceStateStore} shared with another controller, which holds the resources in {@link Shared#elsewhere}. */
@WithJenkins
class SharedResourceStateStoreTest {

    private JenkinsRule j;
    private LockableResourcesManager lrm;
    private Shared store;

    @BeforeEach
    void setUp(JenkinsRule j) {
        this.j = j;
        lrm = LockableResourcesManager.get();
        store = j.jenkins.getExtensionList(ResourceStateStore.class).get(Shared.class);
        assertSame(store, ResourceStateStore.get());
        lrm.addResource(new LockableResource("res"), true);
    }

    /** Taken by a build of another controller, which this one does not know. */
    private void takenElsewhere(String name) {
        synchronized (LockableResourcesManager.syncResources) {
            store.states.put(name, ResourceState.FREE.withBuild("other-job#7", "theirs"));
        }
    }

    private void releasedElsewhere(String name) {
        synchronized (LockableResourcesManager.syncResources) {
            store.states.remove(name);
        }
        lrm.refreshQueue(); // as the store does when told of a change elsewhere
    }

    @Test
    void lockStepWaitsForAResourceHeldByABuildOfAnotherController() throws Exception {
        takenElsewhere("res");
        LockableResource res = lrm.fromName("res");
        assertTrue(res.isLocked(), "held, though this controller does not know the build");
        assertNull(res.getBuild());
        assertTrue(res.getLockCauseDetail().startsWith("The resource [res] is locked by other-job#7 since"));

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("lock('res') { echo 'inside' }", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("is locked by other-job#7", b);
        Thread.sleep(500);
        assertTrue(b.isBuilding());

        releasedElsewhere("res");
        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        j.assertLogContains("inside", b);
        assertTrue(store.states.isEmpty(), "released: " + store.states);
    }

    @Test
    void reservationRefusedWhileHeldElsewhere() {
        LockableResource res = lrm.fromName("res");
        takenElsewhere("res");
        assertFalse(lrm.reserve(List.of(res), "alice", "testing"));
        releasedElsewhere("res");
        assertTrue(lrm.reserve(List.of(res), "alice", "testing"));
        assertEquals("alice", store.states.get("res").getReservedBy());
        lrm.unreserve(List.of(res));
        assertTrue(store.states.isEmpty());
    }

    @Test
    void freestyleBuildWaitsForAResourceTakenElsewhereAfterItsDispatch() throws Exception {
        // dispatched here while the resource looked free (another controller took it meanwhile): the build waits for
        // it rather than run without it
        LockRunListener.WAIT_FOR_LOCK_MILLIS = 100;
        store.takeOnNextLock = "res";
        AtomicBoolean ran = new AtomicBoolean();
        FreeStyleProject f = j.createFreeStyleProject("f");
        f.addProperty(new RequiredResourcesProperty("res", null, null, null, null));
        f.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener l) {
                ran.set(true);
                return true;
            }
        });
        FreeStyleBuild b = f.scheduleBuild2(0).waitForStart();
        j.waitForMessage("waiting for [res], taken by another controller", b);
        Thread.sleep(500);
        assertFalse(ran.get());

        releasedElsewhere("res");
        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        assertTrue(ran.get());
        j.assertLogContains("acquired lock on [res]", b);
        j.assertLogNotContains("failed to lock", b);
        assertTrue(store.states.isEmpty(), "released: " + store.states);
    }

    @TestExtension
    public static final class Shared extends ResourceStateStore {
        final Map<String, ResourceState> states = new ConcurrentHashMap<>();

        /** A resource another controller takes just before the next lock here (after the queue dispatched it). */
        volatile String takeOnNextLock;

        @NonNull
        @Override
        public ResourceState get(@NonNull LockableResource resource) {
            return states.getOrDefault(resource.getName(), ResourceState.FREE);
        }

        @Override
        public boolean update(@NonNull List<LockableResource> resources, @NonNull UnaryOperator<ResourceState> change) {
            synchronized (LockableResourcesManager.syncResources) {
                String taken = takeOnNextLock;
                if (taken != null) {
                    takeOnNextLock = null;
                    states.put(taken, ResourceState.FREE.withBuild("other-job#7", null));
                }
                Map<String, ResourceState> next = new ConcurrentHashMap<>();
                for (LockableResource r : resources) {
                    ResourceState s = change.apply(get(r));
                    if (s == null) {
                        return false;
                    }
                    next.put(r.getName(), s);
                }
                next.forEach((name, s) -> {
                    if (s.isFree()) {
                        states.remove(name);
                    } else {
                        states.put(name, s);
                    }
                });
                return true;
            }
        }

        @Override
        public boolean isShared() {
            return true;
        }
    }
}
