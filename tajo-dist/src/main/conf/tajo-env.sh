# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Set Tajo-specific environment variables here.

# The only required environment variable is JAVA_HOME.  All others are
# optional.  When running a distributed configuration it is best to
# set JAVA_HOME in this file, so that it is correctly defined on
# remote nodes.

# The java implementation to use.  Required.
# export JAVA_HOME=/usr

# Extra Java CLASSPATH elements.  Optional.
# export TAJO_CLASSPATH=

# The maximum amount of heap to use, in MB. Default is 1000.
# export TAJO_HEAPSIZE=1000

# Extra Java runtime options.  Empty by default.
# export TAJO_OPTS=-server
export TAJO_OPTS=-XX:+PrintGCTimeStamps

# Where log files are stored.  $TAJO_HOME/logs by default.
# export TAJO_LOG_DIR=${TAJO_HOME}/logs

# The directory where pid files are stored. /tmp by default.
# export TAJO_PID_DIR=/var/hadoop/pids

# A string representing this instance of hadoop. $USER by default.
# export TAJO_IDENT_STRING=$USER

# The scheduling priority for daemon processes.  See 'man nice'.
# export TAJO_NICENESS=10
