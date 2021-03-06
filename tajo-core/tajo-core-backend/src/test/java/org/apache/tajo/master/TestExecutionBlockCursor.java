/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.event.AsyncDispatcher;
import org.junit.BeforeClass;
import org.junit.Test;
import org.apache.tajo.QueryIdFactory;
import org.apache.tajo.TajoTestingCluster;
import org.apache.tajo.benchmark.TPCH;
import org.apache.tajo.catalog.*;
import org.apache.tajo.catalog.proto.CatalogProtos;
import org.apache.tajo.conf.TajoConf;
import org.apache.tajo.engine.parser.QueryAnalyzer;
import org.apache.tajo.engine.planner.LogicalPlanner;
import org.apache.tajo.engine.planner.PlanningContext;
import org.apache.tajo.engine.planner.global.MasterPlan;
import org.apache.tajo.engine.planner.logical.LogicalNode;
import org.apache.tajo.engine.planner.logical.LogicalRootNode;
import org.apache.tajo.storage.StorageManager;

import static org.junit.Assert.assertEquals;

public class TestExecutionBlockCursor {
  private static TajoTestingCluster util;
  private static TajoConf conf;
  private static CatalogService catalog;
  private static GlobalPlanner planner;
  private static QueryAnalyzer analyzer;
  private static LogicalPlanner logicalPlanner;

  @BeforeClass
  public static void setUp() throws Exception {
    util = new TajoTestingCluster();
    util.startCatalogCluster();

    conf = util.getConfiguration();
    catalog = util.getMiniCatalogCluster().getCatalog();
    TPCH tpch = new TPCH();
    tpch.loadSchemas();
    tpch.loadOutSchema();
    for (String table : tpch.getTableNames()) {
      TableMeta m = CatalogUtil.newTableMeta(tpch.getSchema(table), CatalogProtos.StoreType.CSV);
      TableDesc d = CatalogUtil.newTableDesc(table, m, new Path("file:///"));
      catalog.addTable(d);
    }

    analyzer = new QueryAnalyzer(catalog);
    logicalPlanner = new LogicalPlanner(catalog);

    StorageManager sm  = new StorageManager(conf);
    AsyncDispatcher dispatcher = new AsyncDispatcher();
    dispatcher.init(conf);
    dispatcher.start();
    planner = new GlobalPlanner(conf, catalog, sm, dispatcher.getEventHandler());
  }

  public static void tearDown() {
    util.shutdownCatalogCluster();
  }

  @Test
  public void testNextBlock() throws Exception {
    PlanningContext context = analyzer.parse(
        "select s_acctbal, s_name, n_name, p_partkey, p_mfgr, s_address, s_phone, s_comment, ps_supplycost, " +
            "r_name, p_type, p_size " +
            "from region join nation on n_regionkey = r_regionkey and r_name = 'AMERICA' " +
            "join supplier on s_nationkey = n_nationkey " +
            "join partsupp on s_suppkey = ps_suppkey " +
            "join part on p_partkey = ps_partkey and p_type like '%BRASS' and p_size = 15");
    LogicalNode logicalPlan = logicalPlanner.createPlan(context);
    MasterPlan plan = planner.build(QueryIdFactory.newQueryId(), (LogicalRootNode) logicalPlan);

    ExecutionBlockCursor cursor = new ExecutionBlockCursor(plan);

    int count = 0;
    while(cursor.hasNext()) {
      cursor.nextBlock();
      count++;
    }

    // 4 input relations, 4 join, and 1 projection = 10 execution blocks
    assertEquals(10, count);
  }
}
