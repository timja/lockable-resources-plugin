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

    @TestExtension
    public static class Provider implements PersistenceStoreProvider {
        @Override
        public PersistenceStore create() {
            return STORE;
        }
    }
}
