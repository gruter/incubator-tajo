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

package org.apache.tajo.catalog;

import com.google.protobuf.Message;
import org.apache.hadoop.fs.Path;

public interface TableDesc extends Cloneable {
  void setId(String tableId);
  
  String getId();
  
  void setPath(Path path);
  
  Path getPath();
  
  void setMeta(TableMeta info);
  
  TableMeta getMeta();
  
  Message getProto();
  
  void initFromProto();
  
  String toJSON();
 
  Object clone() throws CloneNotSupportedException;
}