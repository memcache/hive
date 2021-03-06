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

package org.apache.hadoop.hive.common.jsonexplain.tez;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class Vertex {
  public String name;
  // vertex's parent connections.
  public List<Connection> parentConnections;
  // vertex's children vertex.
  public List<Vertex> children;
  // the jsonObject for this vertex
  public JSONObject vertexObject;
  // whether this vertex is a union vertex
  public boolean union;
  // whether this vertex is dummy (which does not really exists but is created),
  // e.g., a dummy vertex for a mergejoin branch
  public boolean dummy;
  // the rootOps in this vertex
  public List<Op> rootOps;
  // we create a dummy vertex for a mergejoin branch for a self join if this
  // vertex is a mergejoin
  public List<Vertex> mergeJoinDummyVertexs;
  // whether this vertex has multiple reduce operators
  boolean hasMultiReduceOp;

  public Vertex(String name, JSONObject vertexObject) {
    super();
    this.name = name;
    if (this.name != null && this.name.contains("Union")) {
      this.union = true;
    } else {
      this.union = false;
    }
    this.dummy = false;
    this.vertexObject = vertexObject;
    this.parentConnections = new ArrayList<>();
    this.children = new ArrayList<>();
    this.rootOps = new ArrayList<>();
    this.mergeJoinDummyVertexs = new ArrayList<>();
    this.hasMultiReduceOp = false;
  }

  public void addDependency(Connection connection) throws JSONException {
    this.parentConnections.add(connection);
  }

  /**
   * @throws JSONException
   * @throws JsonParseException
   * @throws JsonMappingException
   * @throws IOException
   * @throws Exception
   *           We assume that there is a single top-level Map Operator Tree or a
   *           Reduce Operator Tree in a vertex
   */
  public void extractOpTree() throws JSONException, JsonParseException, JsonMappingException,
      IOException, Exception {
    if (vertexObject.length() != 0) {
      for (String key : JSONObject.getNames(vertexObject)) {
        if (key.equals("Map Operator Tree:")) {
          extractOp(vertexObject.getJSONArray(key).getJSONObject(0));
        } else if (key.equals("Reduce Operator Tree:") || key.equals("Processor Tree:")) {
          extractOp(vertexObject.getJSONObject(key));
        }
        // this is the case when we have a map-side SMB join
        // one input of the join is treated as a dummy vertex
        else if (key.equals("Join:")) {
          JSONArray array = vertexObject.getJSONArray(key);
          for (int index = 0; index < array.length(); index++) {
            JSONObject mpOpTree = array.getJSONObject(index);
            Vertex v = new Vertex("", mpOpTree);
            v.extractOpTree();
            v.dummy = true;
            mergeJoinDummyVertexs.add(v);
          }
        } else {
          throw new Exception("unsupported operator tree in vertex " + this.name);
        }
      }
    }
  }

  /**
   * @param operator
   * @param parent
   * @return
   * @throws JSONException
   * @throws JsonParseException
   * @throws JsonMappingException
   * @throws IOException
   * @throws Exception
   *           assumption: each operator only has one parent but may have many
   *           children
   */
  Op extractOp(JSONObject operator) throws JSONException, JsonParseException, JsonMappingException,
      IOException, Exception {
    String[] names = JSONObject.getNames(operator);
    if (names.length != 1) {
      throw new Exception("Expect only one operator in " + operator.toString());
    } else {
      String opName = names[0];
      JSONObject attrObj = (JSONObject) operator.get(opName);
      List<Attr> attrs = new ArrayList<>();
      List<Op> children = new ArrayList<>();
      String id = null;
      String outputVertexName = null;
      for (String attrName : JSONObject.getNames(attrObj)) {
        if (attrName.equals("children")) {
          Object childrenObj = attrObj.get(attrName);
          if (childrenObj instanceof JSONObject) {
            if (((JSONObject) childrenObj).length() != 0) {
              children.add(extractOp((JSONObject) childrenObj));
            }
          } else if (childrenObj instanceof JSONArray) {
            if (((JSONArray) childrenObj).length() != 0) {
              JSONArray array = ((JSONArray) childrenObj);
              for (int index = 0; index < array.length(); index++) {
                children.add(extractOp(array.getJSONObject(index)));
              }
            }
          } else {
            throw new Exception("Unsupported operator " + this.name
                + "'s children operator is neither a jsonobject nor a jsonarray");
          }
        } else {
          if (attrName.equals("OperatorId:")) {
            id = attrObj.get(attrName).toString();
          } else if (attrName.equals("outputname:")) {
            outputVertexName = attrObj.get(attrName).toString();
          } else {
            attrs.add(new Attr(attrName, attrObj.get(attrName).toString()));
          }
        }
      }
      Op op = new Op(opName, id, outputVertexName, children, attrs, operator, this);
      if (!children.isEmpty()) {
        for (Op child : children) {
          child.parent = op;
        }
      } else {
        this.rootOps.add(op);
      }
      return op;
    }
  }

  public void print(PrintStream out, List<Boolean> indentFlag, String type, Vertex callingVertex)
      throws JSONException, Exception {
    // print vertexname
    if (TezJsonParser.printSet.contains(this) && !hasMultiReduceOp) {
      if (type != null) {
        out.println(TezJsonParser.prefixString(indentFlag, "|<-")
            + " Please refer to the previous " + this.name + " [" + type + "]");
      } else {
        out.println(TezJsonParser.prefixString(indentFlag, "|<-")
            + " Please refer to the previous " + this.name);
      }
      return;
    }
    TezJsonParser.printSet.add(this);
    if (type != null) {
      out.println(TezJsonParser.prefixString(indentFlag, "|<-") + this.name + " [" + type + "]");
    } else if (this.name != null) {
      out.println(TezJsonParser.prefixString(indentFlag) + this.name);
    }
    // print operators
    if (hasMultiReduceOp) {
      // find the right op
      Op choose = null;
      for (Op op : this.rootOps) {
        if (op.outputVertexName.equals(callingVertex.name)) {
          choose = op;
        }
      }
      if (choose != null) {
        choose.print(out, indentFlag, false);
      } else {
        throw new Exception("Can not find the right reduce output operator for vertex " + this.name);
      }
    } else {
      for (Op op : this.rootOps) {
        // dummy vertex is treated as a branch of a join operator
        if (this.dummy) {
          op.print(out, indentFlag, true);
        } else {
          op.print(out, indentFlag, false);
        }
      }
    }
    if (this.union) {
      // print dependent vertexs
      for (int index = 0; index < this.parentConnections.size(); index++) {
        Connection connection = this.parentConnections.get(index);
        List<Boolean> unionFlag = new ArrayList<>();
        unionFlag.addAll(indentFlag);
        if (index != this.parentConnections.size() - 1) {
          unionFlag.add(true);
        } else {
          unionFlag.add(false);
        }
        connection.from.print(out, unionFlag, connection.type, this);
      }
    }
  }

  /**
   * We check if a vertex has multiple reduce operators.
   */
  public void checkMultiReduceOperator() {
    // check if it is a reduce vertex and its children is more than 1;
    if (!this.name.contains("Reduce") || this.rootOps.size() < 2) {
      return;
    }
    // check if all the child ops are reduce output operators
    for (Op op : this.rootOps) {
      if (!op.name.contains("Reduce"))
        return;
    }
    this.hasMultiReduceOp = true;
  }
}
