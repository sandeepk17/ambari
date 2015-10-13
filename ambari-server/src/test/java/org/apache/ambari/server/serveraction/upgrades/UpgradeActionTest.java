/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.server.serveraction.upgrades;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.ServiceComponentNotFoundException;
import org.apache.ambari.server.ServiceNotFoundException;
import org.apache.ambari.server.actionmanager.ExecutionCommandWrapper;
import org.apache.ambari.server.actionmanager.HostRoleCommand;
import org.apache.ambari.server.actionmanager.HostRoleCommandFactory;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.agent.ExecutionCommand;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.controller.AmbariCustomCommandExecutionHelper;
import org.apache.ambari.server.orm.GuiceJpaInitializer;
import org.apache.ambari.server.orm.InMemoryDefaultTestModule;
import org.apache.ambari.server.orm.OrmTestHelper;
import org.apache.ambari.server.orm.dao.ClusterVersionDAO;
import org.apache.ambari.server.orm.dao.HostDAO;
import org.apache.ambari.server.orm.dao.HostVersionDAO;
import org.apache.ambari.server.orm.dao.RepositoryVersionDAO;
import org.apache.ambari.server.orm.dao.StackDAO;
import org.apache.ambari.server.orm.entities.ClusterVersionEntity;
import org.apache.ambari.server.orm.entities.HostVersionEntity;
import org.apache.ambari.server.orm.entities.StackEntity;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.ConfigImpl;
import org.apache.ambari.server.state.Host;
import org.apache.ambari.server.state.RepositoryInfo;
import org.apache.ambari.server.state.RepositoryVersionState;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponent;
import org.apache.ambari.server.state.ServiceComponentFactory;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.ServiceComponentHostFactory;
import org.apache.ambari.server.state.ServiceFactory;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.State;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.persist.PersistService;
import com.google.inject.persist.UnitOfWork;

/**
 * Tests upgrade-related server side actions
 */
public class UpgradeActionTest {
  private static final String HDP_2_1_1_0 = "2.1.1.0-1";
  private static final String HDP_2_1_1_1 = "2.1.1.1-2";

  private static final String HDP_2_2_1_0 = "2.2.0.1-3";

  private static final StackId HDP_21_STACK = new StackId("HDP-2.1.1");
  private static final StackId HDP_22_STACK = new StackId("HDP-2.2.0");

  private static final String HDP_211_CENTOS6_REPO_URL = "http://s3.amazonaws.com/dev.hortonworks.com/HDP/centos6/2.x/BUILDS/2.1.1.0-118";

  private Injector m_injector;

  @Inject
  private OrmTestHelper m_helper;

  @Inject
  private RepositoryVersionDAO repoVersionDAO;

  @Inject
  private ClusterVersionDAO clusterVersionDAO;

  @Inject
  private HostVersionDAO hostVersionDAO;

  @Inject
  private HostDAO hostDAO;

  @Inject
  private HostRoleCommandFactory hostRoleCommandFactory;

  @Inject
  private ServiceFactory serviceFactory;

  @Inject
  private ServiceComponentFactory serviceComponentFactory;

  @Inject
  private ServiceComponentHostFactory serviceComponentHostFactory;

  @Before
  public void setup() throws Exception {
    m_injector = Guice.createInjector(new InMemoryDefaultTestModule());
    m_injector.getInstance(GuiceJpaInitializer.class);
    m_injector.injectMembers(this);
    m_injector.getInstance(UnitOfWork.class).begin();
  }

  @After
  public void teardown() throws Exception {
    m_injector.getInstance(UnitOfWork.class).end();
    m_injector.getInstance(PersistService.class).stop();
  }

  private void makeDowngradeCluster(StackId sourceStack, String sourceRepo, StackId targetStack, String targetRepo) throws Exception {
    String clusterName = "c1";
    String hostName = "h1";

    Clusters clusters = m_injector.getInstance(Clusters.class);
    clusters.addCluster(clusterName, sourceStack);

    Cluster c = clusters.getCluster(clusterName);

    // add a host component
    clusters.addHost(hostName);

    Host host = clusters.getHost(hostName);

    Map<String, String> hostAttributes = new HashMap<String, String>();
    hostAttributes.put("os_family", "redhat");
    hostAttributes.put("os_release_version", "6");
    host.setHostAttributes(hostAttributes);
    host.persist();

    // Create the starting repo version
    m_helper.getOrCreateRepositoryVersion(sourceStack, sourceRepo);
    c.createClusterVersion(sourceStack, sourceRepo, "admin", RepositoryVersionState.UPGRADING);
    c.transitionClusterVersion(sourceStack, sourceRepo, RepositoryVersionState.CURRENT);

    // Start upgrading the newer repo
    m_helper.getOrCreateRepositoryVersion(targetStack, targetRepo);
    c.createClusterVersion(targetStack, targetRepo, "admin", RepositoryVersionState.INSTALLING);
    c.transitionClusterVersion(targetStack, targetRepo, RepositoryVersionState.INSTALLED);
    c.transitionClusterVersion(targetStack, targetRepo, RepositoryVersionState.UPGRADING);

    c.mapHostVersions(Collections.singleton(hostName), c.getCurrentClusterVersion(),
        RepositoryVersionState.CURRENT);

    HostVersionEntity entity = new HostVersionEntity();
    entity.setHostEntity(hostDAO.findByName(hostName));
    entity.setRepositoryVersion(repoVersionDAO.findByStackAndVersion(targetStack, targetRepo));
    entity.setState(RepositoryVersionState.UPGRADING);
    hostVersionDAO.create(entity);
  }

  private void makeUpgradeCluster(StackId sourceStack, String sourceRepo, StackId targetStack, String targetRepo) throws Exception {
    String clusterName = "c1";
    String hostName = "h1";

    Clusters clusters = m_injector.getInstance(Clusters.class);
    clusters.addCluster(clusterName, sourceStack);

    StackDAO stackDAO = m_injector.getInstance(StackDAO.class);
    StackEntity stackEntitySource = stackDAO.find(sourceStack.getStackName(), sourceStack.getStackVersion());
    StackEntity stackEntityTarget = stackDAO.find(targetStack.getStackName(), targetStack.getStackVersion());
    assertNotNull(stackEntitySource);
    assertNotNull(stackEntityTarget);

    Cluster c = clusters.getCluster(clusterName);
    c.setDesiredStackVersion(sourceStack);

    // add a host component
    clusters.addHost(hostName);

    Host host = clusters.getHost(hostName);

    Map<String, String> hostAttributes = new HashMap<String, String>();
    hostAttributes.put("os_family", "redhat");
    hostAttributes.put("os_release_version", "6");
    host.setHostAttributes(hostAttributes);
    host.persist();

    // Create the starting repo version
    m_helper.getOrCreateRepositoryVersion(sourceStack, sourceRepo);
    c.createClusterVersion(sourceStack, sourceRepo, "admin", RepositoryVersionState.UPGRADING);
    c.transitionClusterVersion(sourceStack, sourceRepo, RepositoryVersionState.CURRENT);
    
    // Create the new repo version
    String urlInfo = "[{'repositories':["
        + "{'Repositories/base_url':'http://foo1','Repositories/repo_name':'HDP','Repositories/repo_id':'" + targetStack.getStackId() + "'}"
        + "], 'OperatingSystems/os_type':'redhat6'}]";
    repoVersionDAO.create(stackEntityTarget, targetRepo, String.valueOf(System.currentTimeMillis()), urlInfo);

    // Start upgrading the newer repo
    c.createClusterVersion(targetStack, targetRepo, "admin", RepositoryVersionState.INSTALLING);
    c.transitionClusterVersion(targetStack, targetRepo, RepositoryVersionState.INSTALLED);
    c.transitionClusterVersion(targetStack, targetRepo, RepositoryVersionState.UPGRADING);
    c.transitionClusterVersion(targetStack, targetRepo, RepositoryVersionState.UPGRADED);
    c.setCurrentStackVersion(targetStack);

    c.mapHostVersions(Collections.singleton(hostName), c.getCurrentClusterVersion(),
        RepositoryVersionState.CURRENT);

    HostDAO hostDAO = m_injector.getInstance(HostDAO.class);

    HostVersionEntity entity = new HostVersionEntity();
    entity.setHostEntity(hostDAO.findByName(hostName));
    entity.setRepositoryVersion(repoVersionDAO.findByStackAndVersion(targetStack, targetRepo));
    entity.setState(RepositoryVersionState.UPGRADED);
    hostVersionDAO.create(entity);
  }

  private void makeCrossStackUpgradeCluster(StackId sourceStack, String sourceRepo, StackId targetStack, String targetRepo) throws Exception {
    String clusterName = "c1";
    String hostName = "h1";

    Clusters clusters = m_injector.getInstance(Clusters.class);
    clusters.addCluster(clusterName, sourceStack);

    StackDAO stackDAO = m_injector.getInstance(StackDAO.class);
    StackEntity stackEntitySource = stackDAO.find(sourceStack.getStackName(), sourceStack.getStackVersion());
    StackEntity stackEntityTarget = stackDAO.find(targetStack.getStackName(), targetStack.getStackVersion());

    assertNotNull(stackEntitySource);
    assertNotNull(stackEntityTarget);

    Cluster c = clusters.getCluster(clusterName);
    c.setCurrentStackVersion(sourceStack);
    c.setDesiredStackVersion(sourceStack);

    // add a host component
    clusters.addHost(hostName);
    Host host = clusters.getHost(hostName);

    Map<String, String> hostAttributes = new HashMap<String, String>();
    hostAttributes.put("os_family", "redhat");
    hostAttributes.put("os_release_version", "6");
    host.setHostAttributes(hostAttributes);
    host.persist();

    clusters.mapHostToCluster(hostName, clusterName);

    // Create the starting repo version
    m_helper.getOrCreateRepositoryVersion(sourceStack, sourceRepo);
    c.createClusterVersion(sourceStack, sourceRepo, "admin", RepositoryVersionState.UPGRADING);
    c.transitionClusterVersion(sourceStack, sourceRepo, RepositoryVersionState.CURRENT);

    // Create the new repo version
    String urlInfo = "[{'repositories':["
        + "{'Repositories/base_url':'http://foo1','Repositories/repo_name':'HDP','Repositories/repo_id':'" + targetRepo + "'}"
        + "], 'OperatingSystems/os_type':'redhat6'}]";
    repoVersionDAO.create(stackEntityTarget, targetRepo, String.valueOf(System.currentTimeMillis()), urlInfo);

    // Start upgrading the newer repo
    c.createClusterVersion(targetStack, targetRepo, "admin", RepositoryVersionState.INSTALLING);
    c.transitionClusterVersion(targetStack, targetRepo, RepositoryVersionState.INSTALLED);
    c.transitionClusterVersion(targetStack, targetRepo, RepositoryVersionState.UPGRADING);
    c.transitionClusterVersion(targetStack, targetRepo, RepositoryVersionState.UPGRADED);

    c.mapHostVersions(Collections.singleton(hostName), c.getCurrentClusterVersion(),
        RepositoryVersionState.CURRENT);

    HostDAO hostDAO = m_injector.getInstance(HostDAO.class);

    HostVersionEntity entity = new HostVersionEntity();
    entity.setHostEntity(hostDAO.findByName(hostName));
    entity.setRepositoryVersion(repoVersionDAO.findByStackAndVersion(targetStack, targetRepo));
    entity.setState(RepositoryVersionState.UPGRADED);
    hostVersionDAO.create(entity);
  }

  @Test
  public void testFinalizeDowngrade() throws Exception {
    StackId sourceStack = HDP_21_STACK;
    StackId targetStack = HDP_21_STACK;
    String sourceRepo = HDP_2_1_1_0;
    String targetRepo = HDP_2_1_1_1;

    makeDowngradeCluster(sourceStack, sourceRepo, targetStack, targetRepo);

    Map<String, String> commandParams = new HashMap<String, String>();
    commandParams.put(FinalizeUpgradeAction.UPGRADE_DIRECTION_KEY, "downgrade");
    commandParams.put(FinalizeUpgradeAction.VERSION_KEY, sourceRepo);

    ExecutionCommand executionCommand = new ExecutionCommand();
    executionCommand.setCommandParams(commandParams);
    executionCommand.setClusterName("c1");

    HostRoleCommand hostRoleCommand = hostRoleCommandFactory.create(null, null, null, null);
    hostRoleCommand.setExecutionCommandWrapper(new ExecutionCommandWrapper(executionCommand));

    FinalizeUpgradeAction action = m_injector.getInstance(FinalizeUpgradeAction.class);
    action.setExecutionCommand(executionCommand);
    action.setHostRoleCommand(hostRoleCommand);

    CommandReport report = action.execute(null);
    assertNotNull(report);
    assertEquals(HostRoleStatus.COMPLETED.name(), report.getStatus());

    for (HostVersionEntity entity : hostVersionDAO.findByClusterAndHost("c1", "h1")) {
      if (entity.getRepositoryVersion().getVersion().equals(sourceRepo)) {
        assertEquals(RepositoryVersionState.CURRENT, entity.getState());
      } else if (entity.getRepositoryVersion().getVersion().equals(targetRepo)) {
        assertEquals(RepositoryVersionState.INSTALLED, entity.getState());
      }
    }

    for (ClusterVersionEntity entity : clusterVersionDAO.findByCluster("c1")) {
      if (entity.getRepositoryVersion().getVersion().equals(sourceRepo)) {
        assertEquals(RepositoryVersionState.CURRENT, entity.getState());
      } else if (entity.getRepositoryVersion().getVersion().equals(targetRepo)) {
        assertEquals(RepositoryVersionState.INSTALLED, entity.getState());
      }
    }
  }

  @Test
  public void testFinalizeUpgrade() throws Exception {
    StackId sourceStack = HDP_21_STACK;
    StackId targetStack = HDP_21_STACK;
    String sourceRepo = HDP_2_1_1_0;
    String targetRepo = HDP_2_1_1_1;

    makeUpgradeCluster(sourceStack, sourceRepo, targetStack, targetRepo);

    // Verify the repo before calling Finalize
    AmbariMetaInfo metaInfo = m_injector.getInstance(AmbariMetaInfo.class);
    AmbariCustomCommandExecutionHelper helper = m_injector.getInstance(AmbariCustomCommandExecutionHelper.class);
    Clusters clusters = m_injector.getInstance(Clusters.class);
    Host host = clusters.getHost("h1");
    Cluster cluster = clusters.getCluster("c1");

    RepositoryInfo repo = metaInfo.getRepository(sourceStack.getStackName(), sourceStack.getStackVersion(), "redhat6", sourceStack.getStackId());
    assertEquals(HDP_211_CENTOS6_REPO_URL, repo.getBaseUrl());
    verifyBaseRepoURL(helper, cluster, host, HDP_211_CENTOS6_REPO_URL);

    // Finalize the upgrade
    Map<String, String> commandParams = new HashMap<String, String>();
    commandParams.put(FinalizeUpgradeAction.UPGRADE_DIRECTION_KEY, "upgrade");
    commandParams.put(FinalizeUpgradeAction.VERSION_KEY, targetRepo);

    ExecutionCommand executionCommand = new ExecutionCommand();
    executionCommand.setCommandParams(commandParams);
    executionCommand.setClusterName("c1");

    HostRoleCommand hostRoleCommand = hostRoleCommandFactory.create(null, null, null, null);
    hostRoleCommand.setExecutionCommandWrapper(new ExecutionCommandWrapper(executionCommand));

    FinalizeUpgradeAction action = m_injector.getInstance(FinalizeUpgradeAction.class);
    action.setExecutionCommand(executionCommand);
    action.setHostRoleCommand(hostRoleCommand);

    CommandReport report = action.execute(null);
    assertNotNull(report);
    assertEquals(HostRoleStatus.COMPLETED.name(), report.getStatus());

    // Verify the metainfo url
    verifyBaseRepoURL(helper, cluster, host, "http://foo1");
  }

  private void verifyBaseRepoURL(AmbariCustomCommandExecutionHelper helper, Cluster cluster, Host host, String expectedRepoBaseURL) throws AmbariException {
    String repoInfo = helper.getRepoInfo(cluster, host);
    Gson gson = new Gson();
    JsonElement element = gson.fromJson(repoInfo, JsonElement.class);
    assertTrue(element.isJsonArray());
    JsonArray list = JsonArray.class.cast(element);
    assertEquals(1, list.size());

    JsonObject o = list.get(0).getAsJsonObject();
    assertTrue(o.has("baseUrl"));
    assertEquals(expectedRepoBaseURL, o.get("baseUrl").getAsString());
  }

  @Test
  public void testFinalizeUpgradeAcrossStacks() throws Exception {
    StackId sourceStack = HDP_21_STACK;
    StackId targetStack = HDP_22_STACK;
    String sourceRepo = HDP_2_1_1_0;
    String targetRepo = HDP_2_2_1_0;

    makeCrossStackUpgradeCluster(sourceStack, sourceRepo, targetStack, targetRepo);

    Clusters clusters = m_injector.getInstance(Clusters.class);
    Cluster cluster = clusters.getCluster("c1");

    // setup the cluster for the upgrade across stacks
    cluster.setCurrentStackVersion(sourceStack);
    cluster.setDesiredStackVersion(targetStack);

    Map<String, String> commandParams = new HashMap<String, String>();
    commandParams.put(FinalizeUpgradeAction.UPGRADE_DIRECTION_KEY, "upgrade");
    commandParams.put(FinalizeUpgradeAction.VERSION_KEY, targetRepo);
    commandParams.put(FinalizeUpgradeAction.ORIGINAL_STACK_KEY, sourceStack.getStackId());
    commandParams.put(FinalizeUpgradeAction.TARGET_STACK_KEY, targetStack.getStackId());

    ExecutionCommand executionCommand = new ExecutionCommand();
    executionCommand.setCommandParams(commandParams);
    executionCommand.setClusterName("c1");

    HostRoleCommand hostRoleCommand = hostRoleCommandFactory.create(null, null, null, null);

    hostRoleCommand.setExecutionCommandWrapper(new ExecutionCommandWrapper(executionCommand));

    FinalizeUpgradeAction action = m_injector.getInstance(FinalizeUpgradeAction.class);
    action.setExecutionCommand(executionCommand);
    action.setHostRoleCommand(hostRoleCommand);

    CommandReport report = action.execute(null);
    assertNotNull(report);
    assertEquals(HostRoleStatus.COMPLETED.name(), report.getStatus());

    StackId currentStackId = cluster.getCurrentStackVersion();
    StackId desiredStackId = cluster.getDesiredStackVersion();

    // verify current/desired stacks are updated to the new stack
    assertEquals(desiredStackId, currentStackId);
    assertEquals(targetStack, currentStackId);
    assertEquals(targetStack, desiredStackId);
  }

  /**
   * Tests some of the action items are completed when finalizing downgrade
   * across stacks (HDP 2.2 -> HDP 2.3).
   *
   * @throws Exception
   */
  @Test
  public void testFinalizeDowngradeAcrossStacks() throws Exception {
    StackId sourceStack = HDP_21_STACK;
    StackId targetStack = HDP_22_STACK;
    String sourceRepo = HDP_2_1_1_0;
    String targetRepo = HDP_2_2_1_0;

    makeCrossStackUpgradeCluster(sourceStack, sourceRepo, targetStack, targetRepo);

    Clusters clusters = m_injector.getInstance(Clusters.class);
    Cluster cluster = clusters.getCluster("c1");

    // install HDFS with some components
    Service service = installService(cluster, "HDFS");
    addServiceComponent(cluster, service, "NAMENODE");
    addServiceComponent(cluster, service, "DATANODE");
    createNewServiceComponentHost(cluster, "HDFS", "NAMENODE", "h1");
    createNewServiceComponentHost(cluster, "HDFS", "DATANODE", "h1");

    // create some configs
    createConfigs(cluster);

    // setup the cluster for the upgrade across stacks
    cluster.setCurrentStackVersion(sourceStack);
    cluster.setDesiredStackVersion(targetStack);

    // now that the desired version is set, we can create some new configs in
    // the new stack version
    createConfigs(cluster);

    // verify we have configs in both HDP stacks
    cluster = clusters.getCluster("c1");
    Collection<Config> configs = cluster.getAllConfigs();
    assertEquals(6, configs.size());

    Map<String, String> commandParams = new HashMap<String, String>();
    commandParams.put(FinalizeUpgradeAction.UPGRADE_DIRECTION_KEY, "downgrade");
    commandParams.put(FinalizeUpgradeAction.VERSION_KEY, sourceRepo);
    commandParams.put(FinalizeUpgradeAction.ORIGINAL_STACK_KEY, sourceStack.getStackId());
    commandParams.put(FinalizeUpgradeAction.TARGET_STACK_KEY, targetStack.getStackId());

    ExecutionCommand executionCommand = new ExecutionCommand();
    executionCommand.setCommandParams(commandParams);
    executionCommand.setClusterName("c1");

    HostRoleCommand hostRoleCommand = hostRoleCommandFactory.create(null, null, null, null);

    hostRoleCommand.setExecutionCommandWrapper(new ExecutionCommandWrapper(executionCommand));

    HostVersionDAO dao = m_injector.getInstance(HostVersionDAO.class);

    List<HostVersionEntity> hosts = dao.findByClusterStackAndVersion("c1", targetStack, targetRepo);
    assertFalse(hosts.isEmpty());
    for (HostVersionEntity hve : hosts) {
      assertFalse(hve.getState() == RepositoryVersionState.INSTALLED);
    }

    FinalizeUpgradeAction action = m_injector.getInstance(FinalizeUpgradeAction.class);
    action.setExecutionCommand(executionCommand);
    action.setHostRoleCommand(hostRoleCommand);

    CommandReport report = action.execute(null);
    assertNotNull(report);
    assertEquals(HostRoleStatus.COMPLETED.name(), report.getStatus());

    StackId currentStackId = cluster.getCurrentStackVersion();
    StackId desiredStackId = cluster.getDesiredStackVersion();

    // verify current/desired stacks are back to normal
    assertEquals(desiredStackId, currentStackId);
    assertEquals(sourceStack, currentStackId);
    assertEquals(sourceStack, desiredStackId);

    // verify we have configs in only 1 stack
    cluster = clusters.getCluster("c1");
    configs = cluster.getAllConfigs();
    assertEquals(3, configs.size());

    hosts = dao.findByClusterStackAndVersion("c1", targetStack, targetRepo);
    assertFalse(hosts.isEmpty());
    for (HostVersionEntity hve : hosts) {
      assertTrue(hve.getState() == RepositoryVersionState.INSTALLED);
    }
  }

  /**
   * Tests that finalization can occur when the cluster state is
   * {@link RepositoryVersionState#UPGRADING} if all of the hosts and components
   * are reporting correct versions and states.
   *
   * @throws Exception
   */
  @Test
  public void testFinalizeUpgradeWithClusterStateInconsistencies() throws Exception {
    StackId sourceStack = HDP_21_STACK;
    StackId targetStack = HDP_22_STACK;
    String sourceRepo = HDP_2_1_1_0;
    String targetRepo = HDP_2_2_1_0;

    makeCrossStackUpgradeCluster(sourceStack, sourceRepo, targetStack, targetRepo);

    Clusters clusters = m_injector.getInstance(Clusters.class);
    Cluster cluster = clusters.getCluster("c1");

    Service service = installService(cluster, "HDFS");
    addServiceComponent(cluster, service, "NAMENODE");
    addServiceComponent(cluster, service, "DATANODE");
    createNewServiceComponentHost(cluster, "HDFS", "NAMENODE", "h1");
    createNewServiceComponentHost(cluster, "HDFS", "DATANODE", "h1");

    // create some configs
    createConfigs(cluster);

    // setup the cluster for the upgrade across stacks
    cluster.setCurrentStackVersion(sourceStack);
    cluster.setDesiredStackVersion(targetStack);

    // set the SCH versions to the new stack so that the finalize action is
    // happy
    cluster.getServiceComponentHosts("HDFS", "NAMENODE").get(0).setVersion(targetRepo);
    cluster.getServiceComponentHosts("HDFS", "DATANODE").get(0).setVersion(targetRepo);

    // inject an unhappy path where the cluster repo version is still UPGRADING
    // even though all of the hosts are UPGRADED
    ClusterVersionEntity upgradingClusterVersion = clusterVersionDAO.findByClusterAndStackAndVersion(
        "c1", HDP_22_STACK, targetRepo);

    upgradingClusterVersion.setState(RepositoryVersionState.UPGRADING);
    upgradingClusterVersion = clusterVersionDAO.merge(upgradingClusterVersion);

    // verify the conditions for the test are met properly
    upgradingClusterVersion = clusterVersionDAO.findByClusterAndStackAndVersion("c1", HDP_22_STACK, targetRepo);
    List<HostVersionEntity> hostVersions = hostVersionDAO.findByClusterStackAndVersion("c1", HDP_22_STACK, targetRepo);

    assertEquals(RepositoryVersionState.UPGRADING, upgradingClusterVersion.getState());
    assertTrue(hostVersions.size() > 0);
    for (HostVersionEntity hostVersion : hostVersions) {
      assertEquals(RepositoryVersionState.UPGRADED, hostVersion.getState());
    }

    // now finalize and ensure we can transition from UPGRADING to UPGRADED
    // automatically before CURRENT
    Map<String, String> commandParams = new HashMap<String, String>();
    commandParams.put(FinalizeUpgradeAction.UPGRADE_DIRECTION_KEY, "upgrade");
    commandParams.put(FinalizeUpgradeAction.VERSION_KEY, targetRepo);
    commandParams.put(FinalizeUpgradeAction.ORIGINAL_STACK_KEY, sourceStack.getStackId());
    commandParams.put(FinalizeUpgradeAction.TARGET_STACK_KEY, targetStack.getStackId());

    ExecutionCommand executionCommand = new ExecutionCommand();
    executionCommand.setCommandParams(commandParams);
    executionCommand.setClusterName("c1");

    HostRoleCommand hostRoleCommand = hostRoleCommandFactory.create(null, null, null, null);

    hostRoleCommand.setExecutionCommandWrapper(new ExecutionCommandWrapper(executionCommand));

    FinalizeUpgradeAction action = m_injector.getInstance(FinalizeUpgradeAction.class);
    action.setExecutionCommand(executionCommand);
    action.setHostRoleCommand(hostRoleCommand);

    CommandReport report = action.execute(null);
    assertNotNull(report);
    assertEquals(HostRoleStatus.COMPLETED.name(), report.getStatus());

    StackId currentStackId = cluster.getCurrentStackVersion();
    StackId desiredStackId = cluster.getDesiredStackVersion();

    // verify current/desired stacks are updated to the new stack
    assertEquals(desiredStackId, currentStackId);
    assertEquals(targetStack, currentStackId);
    assertEquals(targetStack, desiredStackId);
  }

  private ServiceComponentHost createNewServiceComponentHost(Cluster cluster, String svc,
      String svcComponent, String hostName) throws AmbariException {
    Assert.assertNotNull(cluster.getConfigGroups());
    Service s = installService(cluster, svc);
    ServiceComponent sc = addServiceComponent(cluster, s, svcComponent);

    ServiceComponentHost sch = serviceComponentHostFactory.createNew(sc, hostName);

    sc.addServiceComponentHost(sch);
    sch.setDesiredState(State.INSTALLED);
    sch.setState(State.INSTALLED);
    sch.setDesiredStackVersion(cluster.getDesiredStackVersion());
    sch.setStackVersion(cluster.getCurrentStackVersion());

    sch.persist();
    return sch;
  }

  private Service installService(Cluster cluster, String serviceName) throws AmbariException {
    Service service = null;

    try {
      service = cluster.getService(serviceName);
    } catch (ServiceNotFoundException e) {
      service = serviceFactory.createNew(cluster, serviceName);
      cluster.addService(service);
      service.persist();
    }

    return service;
  }

  private ServiceComponent addServiceComponent(Cluster cluster, Service service,
      String componentName) throws AmbariException {
    ServiceComponent serviceComponent = null;
    try {
      serviceComponent = service.getServiceComponent(componentName);
    } catch (ServiceComponentNotFoundException e) {
      serviceComponent = serviceComponentFactory.createNew(service, componentName);
      service.addServiceComponent(serviceComponent);
      serviceComponent.setDesiredState(State.INSTALLED);
      serviceComponent.persist();
    }

    return serviceComponent;
  }

  private void createConfigs(Cluster cluster) {
    Map<String, String> properties = new HashMap<String, String>();
    Map<String, Map<String, String>> propertiesAttributes = new HashMap<String, Map<String, String>>();
    properties.put("a", "a1");
    properties.put("b", "b1");

    Config c1 = new ConfigImpl(cluster, "hdfs-site", properties, propertiesAttributes, m_injector);
    properties.put("c", "c1");
    properties.put("d", "d1");

    Config c2 = new ConfigImpl(cluster, "core-site", properties, propertiesAttributes, m_injector);
    Config c3 = new ConfigImpl(cluster, "foo-site", properties, propertiesAttributes, m_injector);

    cluster.addConfig(c1);
    cluster.addConfig(c2);
    cluster.addConfig(c3);
    c1.persist();
    c2.persist();
    c3.persist();
  }
}
