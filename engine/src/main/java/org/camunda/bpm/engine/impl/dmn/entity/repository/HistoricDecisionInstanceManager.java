/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.camunda.bpm.engine.impl.dmn.entity.repository;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.camunda.bpm.engine.history.HistoricDecisionInputInstance;
import org.camunda.bpm.engine.history.HistoricDecisionInstance;
import org.camunda.bpm.engine.history.HistoricDecisionOutputInstance;
import org.camunda.bpm.engine.impl.Page;
import org.camunda.bpm.engine.impl.persistence.AbstractHistoricManager;
import org.camunda.bpm.engine.variable.type.ValueType;

/**
 * Data base operations for {@link HistoricDecisionInstanceEntity}.
 *
 * @author Philipp Ossler
 */
public class HistoricDecisionInstanceManager extends AbstractHistoricManager {

  public void deleteHistoricDecisionInstancesByDecisionDefinitionKey(String decisionDefinitionKey) {
    if (isHistoryEnabled()) {
      List<HistoricDecisionInstanceEntity> decisionInstances = findHistoricDecisionInstancesByDecisionDefinitionKey(decisionDefinitionKey);

      Set<String> decisionInstanceKeys = new HashSet<String>();
      for(HistoricDecisionInstanceEntity decisionInstance : decisionInstances) {
        decisionInstanceKeys.add(decisionInstance.getId());
        // delete decision instance
        decisionInstance.delete();
      }

      if(!decisionInstanceKeys.isEmpty()) {
        deleteHistoricDecisionInputInstancesByDecisionInstanceKeys(decisionInstanceKeys);

        deleteHistoricDecisionOutputInstancesByDecisionInstanceKeys(decisionInstanceKeys);
      }
    }
  }

  @SuppressWarnings("unchecked")
  protected List<HistoricDecisionInstanceEntity> findHistoricDecisionInstancesByDecisionDefinitionKey(String decisionDefinitionKey) {
    return getDbEntityManager().selectList("selectHistoricDecisionInstancesByDecisionDefinitionKey", decisionDefinitionKey);
  }

  protected void deleteHistoricDecisionInputInstancesByDecisionInstanceKeys(Set<String> decisionInstanceKeys) {
    List<HistoricDecisionInputInstanceEntity> decisionInputInstances = findHistoricDecisionInputInstancesByDecisionInstanceIds(decisionInstanceKeys);
    for (HistoricDecisionInputInstanceEntity decisionInputInstance : decisionInputInstances) {
      // delete input instance and byte array value if exists
      decisionInputInstance.delete();
    }
  }

  protected void deleteHistoricDecisionOutputInstancesByDecisionInstanceKeys(Set<String> decisionInstanceKeys) {
    List<HistoricDecisionOutputInstanceEntity> decisionOutputInstances = findHistoricDecisionOutputInstancesByDecisionInstanceIds(decisionInstanceKeys);
    for (HistoricDecisionOutputInstanceEntity decisionOutputInstance : decisionOutputInstances) {
      // delete output instance and byte array value if exists
      decisionOutputInstance.delete();
    }
  }

  public void insertHistoricDecisionInstance(HistoricDecisionInstanceEntity historicDecisionInstance) {
    if (isHistoryEnabled()) {
      getDbEntityManager().insert(historicDecisionInstance);

      insertHistoricDecisionInputInstances(historicDecisionInstance.getInputs(), historicDecisionInstance.getId());
      insertHistoricDecisionOutputInstances(historicDecisionInstance.getOutputs(), historicDecisionInstance.getId());
    }
  }

  protected void insertHistoricDecisionInputInstances(List<HistoricDecisionInputInstance> inputs, String decisionInstanceId) {
    for (HistoricDecisionInputInstance input : inputs) {
      HistoricDecisionInputInstanceEntity inputEntity = (HistoricDecisionInputInstanceEntity) input;
      inputEntity.setDecisionInstanceId(decisionInstanceId);

      getDbEntityManager().insert(inputEntity);
    }
  }

  protected void insertHistoricDecisionOutputInstances(List<HistoricDecisionOutputInstance> outputs, String decisionInstanceId) {
    for (HistoricDecisionOutputInstance output : outputs) {
      HistoricDecisionOutputInstanceEntity outputEntity = (HistoricDecisionOutputInstanceEntity) output;
      outputEntity.setDecisionInstanceId(decisionInstanceId);

      getDbEntityManager().insert(outputEntity);
    }
  }

 public List<HistoricDecisionInstance> findHistoricDecisionInstancesByQueryCriteria(HistoricDecisionInstanceQueryImpl query, Page page) {
    if (isHistoryEnabled()) {
      getAuthorizationManager().configureHistoricDecisionInstanceQuery(query);

      @SuppressWarnings("unchecked")
      List<HistoricDecisionInstance> decisionInstances = getDbEntityManager().selectList("selectHistoricDecisionInstancesByQueryCriteria", query, page);

      Map<String, HistoricDecisionInstanceEntity> decisionInstancesById = new HashMap<String, HistoricDecisionInstanceEntity>();
      for(HistoricDecisionInstance decisionInstance : decisionInstances) {
        decisionInstancesById.put(decisionInstance.getId(), (HistoricDecisionInstanceEntity) decisionInstance);
      }

      if (query.isIncludeInput()) {
        appendHistoricDecisionInputInstances(decisionInstancesById, query);
      }

      if(query.isIncludeOutputs()) {
        appendHistoricDecisionOutputInstances(decisionInstancesById, query);
      }

      return decisionInstances;
    } else {
      return Collections.emptyList();
    }
  }

  protected void appendHistoricDecisionInputInstances(Map<String, HistoricDecisionInstanceEntity> decisionInstancesById, HistoricDecisionInstanceQueryImpl query) {
    List<HistoricDecisionInputInstanceEntity> decisionInputInstances = findHistoricDecisionInputInstancesByDecisionInstanceIds(decisionInstancesById.keySet());

    for (HistoricDecisionInputInstance decisionInputInstance : decisionInputInstances) {

      HistoricDecisionInstanceEntity historicDecisionInstance = decisionInstancesById.get(decisionInputInstance.getDecisionInstanceId());
      historicDecisionInstance.addInput(decisionInputInstance);

      // do not fetch values for byte arrays eagerly (unless requested by the user)
      if (!isByteArrayValue(decisionInputInstance) || query.isByteArrayFetchingEnabled()) {
        fetchVariableValue(decisionInputInstance, query.isCustomObjectDeserializationEnabled());
      }
    }
  }

  @SuppressWarnings("unchecked")
  protected List<HistoricDecisionInputInstanceEntity> findHistoricDecisionInputInstancesByDecisionInstanceIds(Set<String> historicDecisionInstanceKeys) {
    return getDbEntityManager().selectList("selectHistoricDecisionInputInstancesByDecisionInstanceIds", historicDecisionInstanceKeys);
  }

  protected boolean isByteArrayValue(HistoricDecisionInputInstance decisionInputInstance) {
    return ValueType.BYTES.getName().equals(decisionInputInstance.getTypeName());
  }

  protected void fetchVariableValue(HistoricDecisionInputInstance decisionInputInstance, boolean isCustomObjectDeserializationEnabled) {
    try {
      decisionInputInstance.getTypedValue(isCustomObjectDeserializationEnabled);
    } catch(Exception t) {
      // do not fail if one of the variables fails to load
      LOG.failedTofetchVariableValue(t);
    }
  }

  protected void appendHistoricDecisionOutputInstances(Map<String, HistoricDecisionInstanceEntity> decisionInstancesById, HistoricDecisionInstanceQueryImpl query) {
    List<HistoricDecisionOutputInstanceEntity> decisionInputInstances = findHistoricDecisionOutputInstancesByDecisionInstanceIds(decisionInstancesById.keySet());

    for (HistoricDecisionOutputInstance decisionOutputInstance : decisionInputInstances) {

      HistoricDecisionInstanceEntity historicDecisionInstance = decisionInstancesById.get(decisionOutputInstance.getDecisionInstanceId());
      historicDecisionInstance.addOutput(decisionOutputInstance);

      // do not fetch values for byte arrays eagerly (unless requested by the user)
      if(!isByteArrayValue(decisionOutputInstance) || query.isByteArrayFetchingEnabled()) {
        fetchVariableValue(decisionOutputInstance, query.isCustomObjectDeserializationEnabled());
      }
    }
  }

  @SuppressWarnings("unchecked")
  protected List<HistoricDecisionOutputInstanceEntity> findHistoricDecisionOutputInstancesByDecisionInstanceIds(Set<String> decisionInstanceKeys) {
    return getDbEntityManager().selectList("selectHistoricDecisionOutputInstancesByDecisionInstanceIds", decisionInstanceKeys);
  }

  protected boolean isByteArrayValue(HistoricDecisionOutputInstance decisionOutputInstance) {
    return ValueType.BYTES.getName().equals(decisionOutputInstance.getTypeName());
  }

  protected void fetchVariableValue(HistoricDecisionOutputInstance decisionOutputInstance, boolean isCustomObjectDeserializationEnabled) {
    try {
      decisionOutputInstance.getTypedValue(isCustomObjectDeserializationEnabled);
    } catch(Exception t) {
      // do not fail if one of the variables fails to load
      LOG.failedTofetchVariableValue(t);
    }
  }

  public long findHistoricDecisionInstanceCountByQueryCriteria(HistoricDecisionInstanceQueryImpl query) {
    if (isHistoryEnabled()) {
      getAuthorizationManager().configureHistoricDecisionInstanceQuery(query);
      return (Long) getDbEntityManager().selectOne("selectHistoricDecisionInstanceCountByQueryCriteria", query);
    } else {
      return 0;
    }
  }

  @SuppressWarnings("unchecked")
  public List<HistoricDecisionInstance> findHistoricDecisionInstancesByNativeQuery(Map<String, Object> parameterMap, int firstResult, int maxResults) {
    return getDbEntityManager().selectListWithRawParameter("selectHistoricDecisionInstancesByNativeQuery", parameterMap, firstResult, maxResults);
  }

  public long findHistoricDecisionInstanceCountByNativeQuery(Map<String, Object> parameterMap) {
    return (Long) getDbEntityManager().selectOne("selectHistoricDecisionInstanceCountByNativeQuery", parameterMap);
  }
}
