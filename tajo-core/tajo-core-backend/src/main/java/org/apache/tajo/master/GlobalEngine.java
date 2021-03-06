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

package org.apache.tajo.master;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.protocolrecords.GetNewApplicationResponse;
import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.client.YarnClient;
import org.apache.hadoop.yarn.client.YarnClientImpl;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnRemoteException;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.service.AbstractService;
import org.apache.hadoop.yarn.util.Records;
import org.apache.tajo.QueryConf;
import org.apache.tajo.QueryId;
import org.apache.tajo.catalog.CatalogUtil;
import org.apache.tajo.catalog.TableDesc;
import org.apache.tajo.catalog.TableMeta;
import org.apache.tajo.catalog.statistics.TableStat;
import org.apache.tajo.engine.exception.EmptyClusterException;
import org.apache.tajo.engine.exception.IllegalQueryStatusException;
import org.apache.tajo.engine.exception.NoSuchQueryIdException;
import org.apache.tajo.engine.exception.UnknownWorkerException;
import org.apache.tajo.engine.parser.QueryAnalyzer;
import org.apache.tajo.engine.parser.StatementType;
import org.apache.tajo.engine.planner.LogicalOptimizer;
import org.apache.tajo.engine.planner.LogicalPlanner;
import org.apache.tajo.engine.planner.PlanningContext;
import org.apache.tajo.engine.planner.global.GlobalOptimizer;
import org.apache.tajo.engine.planner.global.MasterPlan;
import org.apache.tajo.engine.planner.logical.CreateTableNode;
import org.apache.tajo.engine.planner.logical.ExprType;
import org.apache.tajo.engine.planner.logical.LogicalNode;
import org.apache.tajo.engine.planner.logical.LogicalRootNode;
import org.apache.tajo.engine.query.exception.TQLSyntaxError;
import org.apache.tajo.master.TajoMaster.MasterContext;
import org.apache.tajo.storage.StorageManager;
import org.apache.tajo.storage.StorageUtil;
import org.apache.tajo.util.TajoIdUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.EnumSet;
import java.util.Set;

@SuppressWarnings("unchecked")
public class GlobalEngine extends AbstractService {
  /** Class Logger */
  private final static Log LOG = LogFactory.getLog(GlobalEngine.class);

  private final MasterContext context;
  private final StorageManager sm;

  private QueryAnalyzer analyzer;
  private LogicalPlanner planner;
  private GlobalPlanner globalPlanner;
  private GlobalOptimizer globalOptimizer;

  // Yarn
  private final RecordFactory recordFactory =
      RecordFactoryProvider.getRecordFactory(null);
  protected YarnClient yarnClient;
  protected InetSocketAddress rmAddress;

  public GlobalEngine(final MasterContext context, final StorageManager sm)
      throws IOException {
    super(GlobalEngine.class.getName());
    this.context = context;
    this.sm = sm;
  }

  public void start() {
    try  {
      connectYarnClient();
      analyzer = new QueryAnalyzer(context.getCatalog());
      planner = new LogicalPlanner(context.getCatalog());

      globalPlanner = new GlobalPlanner(context.getConf(), context.getCatalog(),
          sm, context.getEventHandler());

      globalOptimizer = new GlobalOptimizer();
    } catch (Throwable t) {
      t.printStackTrace();
    }
    super.start();
  }

  public void stop() {
    super.stop();
    yarnClient.stop();
  }

  private String createTable(LogicalRootNode root) throws IOException {
    // create table queries are executed by the master
    CreateTableNode createTable = (CreateTableNode) root.getSubNode();
    TableMeta meta;
    if (createTable.hasOptions()) {
      meta = CatalogUtil.newTableMeta(createTable.getSchema(),
          createTable.getStorageType(), createTable.getOptions());
    } else {
      meta = CatalogUtil.newTableMeta(createTable.getSchema(),
          createTable.getStorageType());
    }

    FileSystem fs = createTable.getPath().getFileSystem(context.getConf());
    if(fs.exists(createTable.getPath()) && fs.isFile(createTable.getPath())) {
    	throw new IOException("ERROR: LOCATION must be a directory.");
    }

    long totalSize = 0;
    try {
      totalSize = sm.calculateSize(createTable.getPath());
    } catch (IOException e) {
      LOG.error("Cannot calculate the size of the relation", e);
    }
    TableStat stat = new TableStat();
    stat.setNumBytes(totalSize);
    meta.setStat(stat);

    StorageUtil.writeTableMeta(context.getConf(), createTable.getPath(), meta);
    TableDesc desc = CatalogUtil.newTableDesc(createTable.getTableName(), meta,
        createTable.getPath());
    context.getCatalog().addTable(desc);
    return desc.getId();
  }
  
  public QueryId executeQuery(String tql)
      throws InterruptedException, IOException,
      NoSuchQueryIdException, IllegalQueryStatusException,
      UnknownWorkerException, EmptyClusterException {
    long querySubmittionTime = context.getClock().getTime();
    LOG.info("TQL: " + tql);
    // parse the query
    PlanningContext planningContext = analyzer.parse(tql);
    LogicalRootNode plan = (LogicalRootNode) createLogicalPlan(planningContext);

    if (plan.getSubNode().getType() == ExprType.CREATE_TABLE) {
      createTable(plan);

      return TajoIdUtils.NullQueryId;
    } else {
      ApplicationAttemptId appAttemptId = submitQuery();
      QueryId queryId = TajoIdUtils.createQueryId(appAttemptId);
      MasterPlan masterPlan = createGlobalPlan(queryId, plan);
      QueryConf queryConf = new QueryConf(context.getConf());
      queryConf.setUser(UserGroupInformation.getCurrentUser().getShortUserName());

      // the output table is given by user
      if (planningContext.hasExplicitOutputTable()) {
        queryConf.setOutputTable(planningContext.getExplicitOutputTable());
      }
      /*
        Path warehousePath = new Path(queryConf.getVar(ConfVars.WAREHOUSE_PATH));
        Path outputDir = new Path(warehousePath, planningContext.getExplicitOutputTable());
        queryConf.setOutputDir(outputDir);
      } else {
        Path queryTmpPath = new Path(queryConf.getVar(ConfVars.QUERY_TMP_DIR));
        Path outputDir = new Path(queryTmpPath, queryId.toString());
        queryConf.setOutputDir(outputDir);
      } */

      QueryMaster query = new QueryMaster(context, appAttemptId,
          context.getClock(), querySubmittionTime, masterPlan);
      startQuery(queryId, queryConf, query);

      return queryId;
    }
  }

  private ApplicationAttemptId submitQuery() throws YarnRemoteException {
    GetNewApplicationResponse newApp = getNewApplication();
    // Get a new application id
    ApplicationId appId = newApp.getApplicationId();
    System.out.println("Get AppId: " + appId);
    LOG.info("Setting up application submission context for ASM");
    ApplicationSubmissionContext appContext = Records
        .newRecord(ApplicationSubmissionContext.class);

    // set the application id
    appContext.setApplicationId(appId);
    // set the application name
    appContext.setApplicationName("Tajo");

    // Set the priority for the application master
    org.apache.hadoop.yarn.api.records.Priority
        pri = Records.newRecord(org.apache.hadoop.yarn.api.records.Priority.class);
    pri.setPriority(5);
    appContext.setPriority(pri);

    // Set the queue to which this application is to be submitted in the RM
    appContext.setQueue("default");

    // Set up the container launch context for the application master
    ContainerLaunchContext amContainer = Records
        .newRecord(ContainerLaunchContext.class);
    appContext.setAMContainerSpec(amContainer);

    // unmanaged AM
    appContext.setUnmanagedAM(true);
    LOG.info("Setting unmanaged AM");

    // Submit the application to the applications manager
    LOG.info("Submitting application to ASM");
    yarnClient.submitApplication(appContext);

    // Monitor the application to wait for launch state
    ApplicationReport appReport = monitorApplication(appId,
        EnumSet.of(YarnApplicationState.ACCEPTED));
    ApplicationAttemptId attemptId = appReport.getCurrentApplicationAttemptId();
    LOG.info("Launching application with id: " + attemptId);

    return attemptId;
  }

  private LogicalNode createLogicalPlan(PlanningContext planningContext)
      throws IOException {

    LogicalNode plan = planner.createPlan(planningContext);
    plan = LogicalOptimizer.optimize(planningContext, plan);
    LogicalNode optimizedPlan = LogicalOptimizer.pushIndex(plan, sm);
    LOG.info("LogicalPlan:\n" + plan);

    return optimizedPlan;
  }

  private MasterPlan createGlobalPlan(QueryId id, LogicalRootNode rootNode)
      throws IOException {
    MasterPlan globalPlan = globalPlanner.build(id, rootNode);
    return globalOptimizer.optimize(globalPlan);
  }

  private void startQuery(final QueryId queryId, final QueryConf queryConf,
                          final QueryMaster query) {
    context.getAllQueries().put(queryId, query);
    query.init(queryConf);
    query.start();
  }

  public boolean updateQuery(String tql) throws IOException {
    LOG.info("TQL: " + tql);

    PlanningContext planningContext = analyzer.parse(tql);
    if (planningContext.getParseTree().getStatementType()
        == StatementType.CREATE_TABLE) {
      LogicalRootNode plan = (LogicalRootNode) createLogicalPlan(planningContext);
      createTable(plan);
      return true;
    } else {
      throw new TQLSyntaxError(tql, "updateQuery cannot handle such query");
    }
  }

  private void connectYarnClient() {
    this.yarnClient = new YarnClientImpl();
    this.yarnClient.init(getConfig());
    this.yarnClient.start();
  }

  private static InetSocketAddress getRmAddress(Configuration conf) {
    return conf.getSocketAddr(YarnConfiguration.RM_ADDRESS,
        YarnConfiguration.DEFAULT_RM_ADDRESS, YarnConfiguration.DEFAULT_RM_PORT);
  }

  public GetNewApplicationResponse getNewApplication()
      throws YarnRemoteException {
    return yarnClient.getNewApplication();
  }

  /**
   * Monitor the submitted application for completion. Kill application if time
   * expires.
   *
   * @param appId
   *          Application Id of application to be monitored
   * @return true if application completed successfully
   * @throws YarnRemoteException
   */
  private ApplicationReport monitorApplication(ApplicationId appId,
                                               Set<YarnApplicationState> finalState) throws YarnRemoteException {

    while (true) {

      // Check app status every 1 second.
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        LOG.debug("Thread sleep in monitoring loop interrupted");
      }

      // Get application report for the appId we are interested in
      ApplicationReport report = yarnClient.getApplicationReport(appId);

      LOG.info("Got application report from ASM for" + ", appId="
          + appId.getId() + ", appAttemptId="
          + report.getCurrentApplicationAttemptId() + ", clientToken="
          + report.getClientToken() + ", appDiagnostics="
          + report.getDiagnostics() + ", appMasterHost=" + report.getHost()
          + ", appQueue=" + report.getQueue() + ", appMasterRpcPort="
          + report.getRpcPort() + ", appStartTime=" + report.getStartTime()
          + ", yarnAppState=" + report.getYarnApplicationState().toString()
          + ", distributedFinalState="
          + report.getFinalApplicationStatus().toString() + ", appTrackingUrl="
          + report.getTrackingUrl() + ", appUser=" + report.getUser());

      YarnApplicationState state = report.getYarnApplicationState();
      if (finalState.contains(state)) {
        return report;
      }
    }
  }
}
