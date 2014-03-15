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
package org.camunda.bpm.engine.impl.cmd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.impl.ExecutionQueryImpl;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.persistence.entity.ActivityInstanceImpl;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.engine.impl.persistence.entity.ProcessElementInstanceImpl;
import org.camunda.bpm.engine.impl.persistence.entity.TransitionInstanceImpl;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.runtime.ActivityInstance;
import org.camunda.bpm.engine.runtime.TransitionInstance;

/**
 * @author Daniel Meyer
 *
 */
public class GetActivityInstanceCmd implements Command<ActivityInstance> {

  protected String processInstanceId;

  /**
   * @param processInstanceId
   */
  public GetActivityInstanceCmd(String processInstanceId) {
    this.processInstanceId = processInstanceId;
  }

  @SuppressWarnings({ "unchecked", "rawtypes" })
  public ActivityInstance execute(CommandContext commandContext) {

    if(processInstanceId == null) {
      throw new ProcessEngineException("processInstanceId cannot be null");
    }

    List<ExecutionEntity> executionList = (List) new ExecutionQueryImpl(commandContext)
      .processInstanceId(processInstanceId)
      .list();

    if(executionList.isEmpty()) {
      return null;
    }

    ExecutionEntity processInstance = null;

    // find process instance && index executions by parentActivityInstanceId
    Map<String, List<ExecutionEntity>> executionsByParentActIds = new HashMap<String, List<ExecutionEntity>>();
    for (ExecutionEntity executionEntity : executionList) {
      if(executionEntity.isProcessInstance()) {
        processInstance = executionEntity;
      }
      String parentActivityInstanceId = executionEntity.getParentActivityInstanceId();
      List<ExecutionEntity> exeForThisParentActInst = executionsByParentActIds.get(parentActivityInstanceId);
      if(exeForThisParentActInst == null) {
        exeForThisParentActInst = new ArrayList<ExecutionEntity>();
        executionsByParentActIds.put(parentActivityInstanceId, exeForThisParentActInst);
      }
      exeForThisParentActInst.add(executionEntity);
    }

    // create act instance for process instance
    ActivityInstanceImpl processActInst = new ActivityInstanceImpl();

    processActInst.setId(processInstanceId);
    processActInst.setParentActivityInstanceId(null);
    processActInst.setProcessInstanceId(processInstanceId);
    processActInst.setProcessDefinitionId(processInstance.getProcessDefinitionId());
    processActInst.setExecutionIds(new String[]{processInstanceId});
    processActInst.setBusinessKey(processInstance.getBusinessKey());
    processActInst.setActivityId(processInstance.getProcessDefinitionId());
    processActInst.setActivityName(processInstance.getProcessDefinition().getName());
    processActInst.setBusinessKey(processInstance.getBusinessKey());
    processActInst.setActivityType("processDefinition");

    initActivityInstanceTree(processActInst, executionsByParentActIds);

    return processActInst;
  }

  protected void initActivityInstanceTree(ActivityInstanceImpl parentActInst, Map<String, List<ExecutionEntity>> executionsByParentActIds) {

    Map<String, ActivityInstanceImpl> childActivityInstances = new HashMap<String, ActivityInstanceImpl>();
    List<TransitionInstance> childTransitionInstances = new ArrayList<TransitionInstance>();
    List<ExecutionEntity> childExecutions = executionsByParentActIds.get(parentActInst.getId());

    if(childExecutions == null) {
      return;
    }

    for (ExecutionEntity execution : childExecutions) {

      if(execution.getActivityInstanceId() == null) {
        TransitionInstanceImpl transitionInstance = new TransitionInstanceImpl();

        initProcessElementInstance(transitionInstance, parentActInst, execution);

        // can use execution id as persistent ID for transition as an execution can execute as most one transition at a time.
        transitionInstance.setId(execution.getId());
        transitionInstance.setExecutionId(execution.getId());
        transitionInstance.setTargetActivityId(execution.getActivityId());

        childTransitionInstances.add(transitionInstance);

      } else if (!isInactiveConcurrentRoot(execution) && !execution.getActivityInstanceId().equals(parentActInst.getId())) {

        ActivityInstanceImpl activityInstance = childActivityInstances.get(execution.getActivityInstanceId());
        if (activityInstance != null) {
          // instance already created -> add executionId
          String[] executionIds = activityInstance.getExecutionIds();
          executionIds = Arrays.copyOf(executionIds, executionIds.length + 1);
          executionIds[executionIds.length] = execution.getId();
          activityInstance.setExecutionIds(executionIds);

        } else {
          // create new activity instance
          ActivityInstanceImpl actInstance = new ActivityInstanceImpl();

          initProcessElementInstance(actInstance, parentActInst, execution);

          actInstance.setBusinessKey(execution.getBusinessKey());
          actInstance.setExecutionIds(new String[]{execution.getId()});

          ScopeImpl activity = getActivity(execution);
          actInstance.setActivityId(activity.getId());
          Object name = activity.getProperty("name");
          if(name!=null) {
            actInstance.setActivityName((String) name);
          }
          Object type = activity.getProperty("type");
          if(type != null) {
            actInstance.setActivityType((String) type);
          }

          childActivityInstances.put(actInstance.getId(), actInstance);

        }
      }
    }

    parentActInst.setChildActivityInstances(childActivityInstances.values().toArray(new ActivityInstance[0]));
    parentActInst.setChildTransitionInstances(childTransitionInstances.toArray(new TransitionInstance[0]));
    for (ActivityInstance childActInstance : parentActInst.getChildActivityInstances()) {
      initActivityInstanceTree((ActivityInstanceImpl) childActInstance, executionsByParentActIds);
    }

  }

  private void initProcessElementInstance(ProcessElementInstanceImpl inst, ActivityInstance parentActInst, ExecutionEntity execution) {

    inst.setId(execution.getActivityInstanceId());
    inst.setParentActivityInstanceId(parentActInst.getId());
    inst.setProcessInstanceId(parentActInst.getProcessInstanceId());
    inst.setProcessDefinitionId(parentActInst.getProcessDefinitionId());

  }

  /** returns true if execution is a concurrent root. */
  protected boolean isInactiveConcurrentRoot(ExecutionEntity execution) {
    List<ExecutionEntity> executions = execution.getExecutions();
//    ActivityImpl activity = execution.getActivity();
    return execution.isScope() && !executions.isEmpty() && executions.get(0).isConcurrent() && !execution.isActive();
  }

  protected ScopeImpl getActivity(ExecutionEntity executionEntity) {
    if(executionEntity.getActivityId() != null) {
      return executionEntity.getActivity();

    } else {
      int i = 0;
      while(!executionEntity.getExecutions().isEmpty()) {
        ExecutionEntity childExecution = executionEntity.getExecutions().get(0);
        if(!executionEntity.getActivityInstanceId().equals(childExecution.getActivityInstanceId())) {
          i++;
        }
        executionEntity = childExecution;
      }
      ActivityImpl scope = executionEntity.getActivity();
      for (int j = 0; j < i; j++) {
        if(scope.getParentActivity() != null) {
        scope = scope.getParentActivity();
        }
      }
      return scope;
    }
  }


}
