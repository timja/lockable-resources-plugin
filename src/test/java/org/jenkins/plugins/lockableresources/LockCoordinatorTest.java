package org.jenkins.plugins.lockableresources;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.util.NameValuePair;
import org.jenkins.plugins.lockableresources.remote.RemoteLockManager;
import org.jenkins.plugins.lockableresources.remote.RemoteLockRecord;
import org.jenkins.plugins.lockableresources.remote.RemoteLockRequest;
import org.jenkins.plugins.lockableresources.remote.RemoteLockState;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LockCoordinatorTest {

    private JenkinsRule j;
    private LockableResourcesManager lrm;
    private Fake fake;

    @BeforeEach
    void setUp(JenkinsRule j) {
        this.j = j;
        lrm = LockableResourcesManager.get();
        fake = j.jenkins.getExtensionList(LockCoordinator.class).get(Fake.class);
        assertSame(fake, LockCoordinator.get());
        lrm.addResource(new LockableResource("res"), true);
    }

    @Test
    void lockHeldElsewhereWaitsUntilReleasedThere() throws Exception {
        LockableResource res = lrm.fromName("res");
        fake.elsewhere.add("res");
        res.setHeldElsewhereBy("other#1 on controller-2");
        assertTrue(res.isLocked());
        assertFalse(res.isFree());
        assertEquals("[res] is locked by other#1 on controller-2", res.getLockCause());

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("lock('res') { echo 'inside' }", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[res] is locked by other#1 on controller-2", b);
        assertTrue(b.isBuilding());

        synchronized (LockableResourcesManager.syncResources) {
            fake.elsewhere.remove("res");
            res.setHeldElsewhereBy(null);
        }
        lrm.refreshQueue();
        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        j.assertLogContains("inside", b);
        assertThat(fake.calls, hasItem("tryLock [res] " + b.getExternalizableId()));
        assertThat(fake.calls, hasItem("released res " + b.getExternalizableId()));
    }

    @Test
    void lockTakenElsewhereInTheMeantimeQueuesTheStep() throws Exception {
        // the resource looks free here, but another controller takes it first: the step waits instead of
        // completing without its body
        fake.refuseNextLocks = 1;
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("lock('res') { semaphore 'inside' }", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[Resource: res] is not free, waiting for execution ...", b);
        assertNull(lrm.fromName("res").getBuild());

        lrm.refreshQueue();
        SemaphoreStep.waitForStart("inside/1", b);
        assertSame(b, lrm.fromName("res").getBuild());
        SemaphoreStep.success("inside/1", null);
        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        assertNull(lrm.fromName("res").getBuild());
    }

    @Test
    void reservationRefusedElsewhere() {
        LockableResource res = lrm.fromName("res");
        fake.elsewhere.add("res");
        assertFalse(lrm.reserve(List.of(res), "alice", "testing"));
        assertNull(res.getReservedBy());

        fake.elsewhere.remove("res");
        assertTrue(lrm.reserve(List.of(res), "alice", "testing"));
        assertEquals("alice", res.getReservedBy());
        lrm.unreserve(List.of(res));
        assertThat(fake.calls, contains("tryReserve [res] alice", "tryReserve [res] alice", "unreserved res"));
    }

    @Test
    void stealTellsTheCoordinatorBeforeTheBuildReleases() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("lock('res') { semaphore 'inside' }", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("inside/1", b);
        assertEquals(List.of("res"), LockableResourcesManager.getResourceNamesHeldBy(b));
        LockableResource res = lrm.fromName("res");
        lrm.steal(List.of(res), "alice", "mine");
        int forcibly = fake.calls.indexOf("reservedForcibly res alice true");
        int released = fake.calls.indexOf("released res " + b.getExternalizableId());
        assertTrue(forcibly >= 0 && forcibly < released, fake.calls::toString);
        assertEquals("alice", res.getReservedBy());
        SemaphoreStep.success("inside/1", null);
        j.waitForCompletion(b);

        lrm.reassign(List.of(res), "bob");
        assertThat(fake.calls, hasItem("reservedForcibly res bob false"));
    }

    @Test
    void unlockAndResetFromThePageFreeEverywhere() {
        LockableResource res = lrm.fromName("res");
        fake.elsewhere.add("res");
        res.setHeldElsewhereBy("other#1 on controller-2");
        lrm.unlockResources(List.of(res));
        assertThat(fake.calls, contains("freed res"));
        assertNull(res.getHeldElsewhereBy());

        fake.calls.clear();
        lrm.reset(List.of(res));
        assertThat(fake.calls, contains("freed res"));
    }

    @Test
    void definitionsEditedOnThePage() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new FullControlOnceLoggedInAuthorizationStrategy());
        j.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = j.createWebClient().login("admin");
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setRedirectEnabled(false);
        post(wc, "createResource", "name", "made");
        post(wc, "saveNote", "resource", "made", "note", "hello");
        post(wc, "deleteResource", "resource", "made");
        assertThat(fake.calls, contains("definitionChanged made", "definitionChanged made", "definitionDeleted made"));
    }

    private static RemoteLockRequest remoteRequest(String resource, boolean skipIfLocked) {
        return new RemoteLockRequest(
                resource, null, 0, null, false, "SEQUENTIAL", skipIfLocked, null, 0, 0, "MINUTES", null);
    }

    private void exposeRes() {
        lrm.setRemoteApiEnabled(true);
        lrm.setExposeLabel("remote-ok");
        lrm.fromName("res").setLabelsFromString("remote-ok");
    }

    @Test
    void remoteLockHeldElsewhereWaitsUntilReleasedThere() {
        exposeRes();
        fake.elsewhere.add("res"); // taken elsewhere, not known here yet: it looks free
        RemoteLockRecord record = RemoteLockManager.get().enqueue(remoteRequest("res", false), "client-1");
        assertEquals(RemoteLockState.QUEUED, record.getState());
        assertTrue(record.getLockId().startsWith("fake-"), "the coordinator's id");
        assertNull(lrm.fromName("res").getRemoteLockedBy());
        assertThat(fake.calls, hasItem("tryLockForRemote [res] " + record.getLockId()));
        assertEquals(
                RemoteLockState.SKIPPED,
                RemoteLockManager.get()
                        .enqueue(remoteRequest("res", true), "client-2")
                        .getState());

        fake.elsewhere.remove("res");
        lrm.refreshQueue();
        assertEquals(RemoteLockState.ACQUIRED, record.getState());
        assertEquals(record.getLockId(), lrm.fromName("res").getRemoteLockedBy());

        RemoteLockManager.get().release(record.getLockId());
        assertNull(lrm.fromName("res").getRemoteLockedBy());
        assertThat(fake.calls, hasItem("releasedForRemote res " + record.getLockId()));
    }

    @Test
    void remoteLockEndedByTheCoordinator() {
        exposeRes();
        RemoteLockRecord record = RemoteLockManager.get().enqueue(remoteRequest("res", false), "client-1");
        assertEquals(RemoteLockState.ACQUIRED, record.getState());
        // freed or taken on another controller meanwhile
        lrm.releaseRemoteLock(record.getLockId());
        assertNull(lrm.fromName("res").getRemoteLockedBy());
        assertEquals(RemoteLockState.FAILED, record.getState());
        lrm.releaseRemoteLock(record.getLockId()); // again: nothing
        lrm.releaseRemoteLock("unknown");
    }

    private void post(JenkinsRule.WebClient wc, String action, String... params) throws Exception {
        WebRequest req = new WebRequest(new URL(j.getURL(), "lockable-resources/" + action), HttpMethod.POST);
        List<NameValuePair> pairs = new ArrayList<>();
        for (int i = 0; i < params.length; i += 2) {
            pairs.add(new NameValuePair(params[i], params[i + 1]));
        }
        req.setRequestParameters(pairs);
        wc.getPage(req);
    }

    /** Holds the resources named in {@link #elsewhere} on an imaginary other controller. */
    @TestExtension
    public static class Fake implements LockCoordinator {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final Set<String> elsewhere = ConcurrentHashMap.newKeySet();
        volatile int refuseNextLocks;

        private static String names(List<LockableResource> resources) {
            return resources.stream().map(LockableResource::getName).toList().toString();
        }

        @Override
        public boolean tryLock(@NonNull List<LockableResource> resources, @NonNull Run<?, ?> build) {
            calls.add("tryLock " + names(resources) + " " + build.getExternalizableId());
            if (refuseNextLocks > 0) {
                refuseNextLocks--;
                return false;
            }
            return resources.stream().noneMatch(r -> elsewhere.contains(r.getName()));
        }

        @Override
        public boolean tryLockForRemote(@NonNull List<LockableResource> resources, @NonNull String lockId) {
            calls.add("tryLockForRemote " + names(resources) + " " + lockId);
            return resources.stream().noneMatch(r -> elsewhere.contains(r.getName()));
        }

        @Override
        public void releasedForRemote(@NonNull LockableResource resource, @NonNull String lockId) {
            calls.add("releasedForRemote " + resource.getName() + " " + lockId);
        }

        @NonNull
        @Override
        public String newRemoteLockId() {
            return "fake-" + UUID.randomUUID();
        }

        @Override
        public void released(@NonNull LockableResource resource, @NonNull Run<?, ?> build) {
            calls.add("released " + resource.getName() + " " + build.getExternalizableId());
        }

        @Override
        public boolean tryReserve(
                @NonNull List<LockableResource> resources, @NonNull String user, @CheckForNull String reason) {
            calls.add("tryReserve " + names(resources) + " " + user);
            return resources.stream().noneMatch(r -> elsewhere.contains(r.getName()));
        }

        @Override
        public void reservedForcibly(
                @NonNull LockableResource r, @NonNull String user, @CheckForNull String reason, boolean stolen) {
            calls.add("reservedForcibly " + r.getName() + " " + user + " " + stolen);
            elsewhere.remove(r.getName());
            r.setHeldElsewhereBy(null);
        }

        @Override
        public void unreserved(@NonNull LockableResource r) {
            calls.add("unreserved " + r.getName());
        }

        @Override
        public void freed(@NonNull LockableResource r) {
            calls.add("freed " + r.getName());
            elsewhere.remove(r.getName());
            r.setHeldElsewhereBy(null);
        }

        @Override
        public boolean isHeldElsewhere(@NonNull LockableResource r) {
            return elsewhere.contains(r.getName());
        }

        @Override
        public void definitionChanged(@NonNull LockableResource r) {
            calls.add("definitionChanged " + r.getName());
        }

        @Override
        public void definitionDeleted(@NonNull String name) {
            calls.add("definitionDeleted " + name);
        }
    }
}
