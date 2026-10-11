/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.cluster;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import jenkins.persistence.InMemoryPersistenceStore;
import jenkins.persistence.PersistenceStore;
import jenkins.persistence.PersistenceStoreProvider;
import org.jenkins.plugins.lockableresources.LockableResource;
import org.jenkins.plugins.lockableresources.LockableResourcesManager;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Locks are exclusive across controllers sharing a store (the other controller is simulated by a store view with another origin).
 */
@WithJenkins
class ClusterLocksTest {

    static final InMemoryPersistenceStore STORE = new InMemoryPersistenceStore(key -> false);

    @Test
    void resourceLockedElsewhereIsWaitedFor(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("shared");
        PersistenceStore other = STORE.as("other-controller");
        other.write(
                "lockable-resources/shared",
                "build=elsewhere#1\norigin=other-controller\ncontroller=jenkins-b\n".getBytes(StandardCharsets.UTF_8),
                PersistenceStore.ABSENT);
        LockableResource r = LockableResourcesManager.get().fromName("shared");
        assertTrue(r.isLocked(), "shown as locked here");

        WorkflowJob p = j.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("lock('shared') { echo 'got the lock' }", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("[shared] is locked", b);
        Thread.sleep(3_000);
        j.assertLogNotContains("got the lock", b);

        other.delete("lockable-resources/shared", PersistenceStore.ANY);
        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        j.assertLogContains("got the lock", b);
        assertNull(STORE.read("lockable-resources/shared"), "released across controllers after the build");
    }

    @Test
    void lockTakenHereIsVisibleToOthers(JenkinsRule j) throws Exception {
        LockableResourcesManager.get().createResource("mine");
        WorkflowJob p = j.createProject(WorkflowJob.class, "q");
        p.setDefinition(new CpsFlowDefinition("lock('mine') { echo 'holding'; sleep 3 }", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("holding", b);

        PersistenceStore.Entry e = STORE.read("lockable-resources/mine");
        assertNotNull(e);
        String holder = new String(e.content(), StandardCharsets.UTF_8);
        assertTrue(holder.contains("build=q#1"), holder);

        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        assertNull(STORE.read("lockable-resources/mine"));
        LockableResource m = LockableResourcesManager.get().fromName("mine"); // ephemeral, so possibly removed
        assertTrue(m == null || m.getBuild() == null);
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out");
            }
            Thread.sleep(100);
        }
    }

    @Test
    void reservationsAreExclusiveAcrossControllers(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("res");
        LockableResource r = lrm.fromName("res");
        assertTrue(lrm.reserve(java.util.List.of(r), "alice", "testing"));
        String holder = new String(STORE.read("lockable-resources/res").content(), StandardCharsets.UTF_8);
        assertTrue(holder.contains("kind=reservation") && holder.contains("user=alice"), holder);
        lrm.unreserve(java.util.List.of(r));
        assertNull(STORE.read("lockable-resources/res"), "unreserved everywhere");

        // reserved on another controller: shown as reserved here, and cannot be reserved or locked here
        PersistenceStore other = STORE.as("other-controller");
        other.write("lockable-resources/res", "kind=reservation\nuser=bob\nstolen=false\norigin=other-controller\ncontroller=jenkins-b\n"
                .getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        awaitTrue(() -> "bob".equals(r.getReservedBy()));
        org.junit.jupiter.api.Assertions.assertFalse(lrm.reserve(java.util.List.of(r), "alice", null));
        other.delete("lockable-resources/res", PersistenceStore.ANY);
        awaitTrue(() -> r.getReservedBy() == null);
    }

    @Test
    void stealingFromABuildElsewhereFreesItThere(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("taken");
        WorkflowJob p = j.createProject(WorkflowJob.class, "holder");
        p.setDefinition(new CpsFlowDefinition("lock('taken') { echo 'holding'; sleep 30 }", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("holding", b);
        LockableResource r = lrm.fromName("taken");
        // stolen on another controller
        STORE.as("other-controller").write("lockable-resources/taken",
                "kind=reservation\nuser=carol\nstolen=true\norigin=other-controller\ncontroller=jenkins-b\n".getBytes(StandardCharsets.UTF_8),
                PersistenceStore.ANY);
        awaitTrue(() -> r.getBuild() == null && "carol".equals(r.getReservedBy()));
        assertTrue(r.isStolen());
        j.assertBuildStatusSuccess(j.waitForCompletion(b));
        assertTrue(new String(STORE.read("lockable-resources/taken").content(), StandardCharsets.UTF_8).contains("user=carol"),
                "the build's end does not release carol's reservation");
    }

    @Test
    void resourcesAndNotesMadeHereAreShared(JenkinsRule j) throws Exception {
        LockableResourcesManager lrm = LockableResourcesManager.get();
        lrm.createResource("noted");
        LockableResource r = lrm.fromName("noted");
        r.setNote("handle with care");
        ClusterLocks.publishDefinition(r);
        assertTrue(new String(STORE.read("lockable-resources-meta/noted").content(), StandardCharsets.UTF_8).contains("handle with care"));
        // created on another controller
        LockableResource def = new LockableResource("made-elsewhere");
        def.setDescription("from B");
        STORE.as("other-controller").write("lockable-resources-meta/made-elsewhere",
                jenkins.model.Jenkins.XSTREAM2.toXML(def).getBytes(StandardCharsets.UTF_8), PersistenceStore.ABSENT);
        awaitTrue(() -> lrm.fromName("made-elsewhere") != null);
        org.junit.jupiter.api.Assertions.assertEquals("from B", lrm.fromName("made-elsewhere").getDescription());
        STORE.as("other-controller").delete("lockable-resources-meta/made-elsewhere", PersistenceStore.ANY);
        awaitTrue(() -> lrm.fromName("made-elsewhere") == null);
    }

    @TestExtension
    public static class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE;
        }
    }
}
