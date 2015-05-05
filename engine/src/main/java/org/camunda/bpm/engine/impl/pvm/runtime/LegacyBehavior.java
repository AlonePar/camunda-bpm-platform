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
package org.camunda.bpm.engine.impl.pvm.runtime;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.impl.bpmn.behavior.EventSubProcessActivityBehavior;
import org.camunda.bpm.engine.impl.bpmn.behavior.MultiInstanceActivityBehavior;
import org.camunda.bpm.engine.impl.bpmn.behavior.ReceiveTaskActivityBehavior;
import org.camunda.bpm.engine.impl.bpmn.behavior.SequentialMultiInstanceActivityBehavior;
import org.camunda.bpm.engine.impl.bpmn.behavior.SubProcessActivityBehavior;
import org.camunda.bpm.engine.impl.persistence.entity.EventSubscriptionEntity;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.engine.impl.pvm.PvmActivity;
import org.camunda.bpm.engine.impl.pvm.delegate.ActivityBehavior;
import org.camunda.bpm.engine.impl.pvm.delegate.ActivityExecution;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.impl.tree.ExecutionWalker;
import org.camunda.bpm.engine.impl.tree.FlowScopeWalker;
import org.camunda.bpm.engine.impl.tree.ScopeCollector;
import org.camunda.bpm.engine.impl.tree.ScopeExecutionCollector;

/**
 * This class encapsulates legacy runtime behavior for the process engine.
 *<p>
 * Since 7.3 the behavior of certain bpmn elements has changed slightly.
 *<p>
 *
 * 1. Some elements which did not used to be scopes are now scopes:
 * <ul>
 *  <li>Sequential multi instance Embedded Subprocess: is now a scope, used to be non-scope.</li>
 *  <li>Event subprocess: is now a scope, used to be non-scope.</li>
 * </ul>
 *
 * 2. In certain situations, executions which were both scope and concurrent were created.
 * This used to be the case if a scope execution already had a single scope child execution
 * and then concurrency was introduced (by a on interrupting boundary event or
 * a non-interrupting event subprocess).  In that case the existing scope execution
 * was made concurrent. Starting from 7.3 this behavior is considered legacy.
 * The new behavior is that the existing scope execution will not be made concurrent, instead,
 * a new, concurrent execution will be created and be interleaved between the parent and the
 * existing scope execution.
 *<p>
 *
 * @author Daniel Meyer
 * @since 7.3
 */
public class LegacyBehavior {

  private final static Logger log = Logger.getLogger(LegacyBehavior.class.getName());

  // concurrent scopes ///////////////////////////////////////////

  /**
   * Prunes a concurrent scope. This can only happen if
   * (a) the process instance has been migrated from a previous version to a new version of the process engine
   *
   * This is an inverse operation to {@link #createConcurrentScope(PvmExecutionImpl)}.
   *
   * See: javadoc of this class for note about concurrent scopes.
   *
   * @param execution
   */
  public static void pruneConcurrentScope(PvmExecutionImpl execution) {
    ensureConcurrentScope(execution);
    log.fine("[LEGACY BEHAVIOR]: concurrent scope execution is pruned "+execution);
    execution.setConcurrent(false);
  }

  /**
   * Cancels an execution which is both concurrent and scope. This can only happen if
   * (a) the process instance has been migrated from a previous version to a new version of the process engine
   *
   * See: javadoc of this class for note about concurrent scopes.
   *
   * @param execution the concurrent scope execution to destroy
   * @param cancellingActivity the activity that cancels the execution; it must hold that
   *   cancellingActivity's event scope is the scope the execution is responsible for
   */
  public static void cancelConcurrentScope(PvmExecutionImpl execution, PvmActivity cancelledScopeActivity) {
    ensureConcurrentScope(execution);
    log.fine("[LEGACY BEHAVIOR]: cancel concurrent scope execution "+execution);

    execution.interrupt("Scope "+cancelledScopeActivity+" cancelled.");
    // <!> HACK set to event scope activity and leave activity instance
    execution.setActivity(cancelledScopeActivity);
    execution.leaveActivityInstance();
    execution.interrupt("Scope "+cancelledScopeActivity+" cancelled.");
    execution.destroy();
  }

  /**
   * Destroys a concurrent scope Execution. This can only happen if
   * (a) the process instance has been migrated from a previous version to a 7.3+ version of the process engine
   *
   * See: javadoc of this class for note about concurrent scopes.
   *
   * @param execution the execution to destroy
   */
  public static void destroyConcurrentScope(PvmExecutionImpl execution) {
    ensureConcurrentScope(execution);
    log.fine("[LEGACY BEHAVIOR]: destroy concurrent scope execution "+execution);

    execution.destroy();
  }

  // sequential multi instance /////////////////////////////////

  public static boolean eventSubprocessComplete(ActivityExecution scopeExecution) {
    boolean performLegacyBehavior = isLegacyBehaviorRequired(scopeExecution);

    if(performLegacyBehavior) {
      log.fine("[LEGACY BEHAVIOR]: complete non-scope event subprocess.");
      scopeExecution.end(false);
    }

    return performLegacyBehavior;
  }

  public static boolean eventSubprocessConcurrentChildExecutionEnded(ActivityExecution scopeExecution, ActivityExecution endedExecution) {
    boolean performLegacyBehavior = isLegacyBehaviorRequired(endedExecution);

    if(performLegacyBehavior) {
      log.fine("[LEGACY BEHAVIOR]: end concurrent execution in event subprocess.");
      endedExecution.end(false);
    }

    return performLegacyBehavior;
  }

  /**
   * Destroy an execution for an activity that was previously not a scope and now is
   * (e.g. event subprocess)
   */
  public static boolean destroySecondNonScope(PvmExecutionImpl execution) {
    ensureScope(execution);
    boolean performLegacyBehavior = isLegacyBehaviorRequired(execution);

    if(performLegacyBehavior) {
      log.fine("[LEGACY BEHAVIOR]: end scope execution in previously non-scope activity");
      // legacy behavior is to do nothing
    }

    return performLegacyBehavior;
  }

  /**
   * This method
   * @param scopeExecution
   * @param isLegacyBehaviorTurnedOff
   * @return
   */
  protected static boolean isLegacyBehaviorRequired(ActivityExecution scopeExecution) {
    // legacy behavior is turned off: the current activity was parsed as scope.
    // now we need to check whether a scope execution was correctly created for the
    // event subprocess.

    // first create the mapping:
    Map<ScopeImpl, PvmExecutionImpl> activityExecutionMapping = scopeExecution.createActivityExecutionMapping();
    // if the scope execution for the current activity is the same as for the parent scope
    // -> we need to perform legacy behavior
    PvmActivity activity = scopeExecution.getActivity();
    return activityExecutionMapping.get(activity) == activityExecutionMapping.get(activity.getFlowScope());
  }

  /**
   * In case the process instance was migrated from a previous version, activities which are now parsed as scopes
   * do not have scope executions. Use the flow scopes of these activities in order to find their execution.
   * - For an event subprocess this is the scope execution of the scope in which the event subprocess is embeded in
   * - For a multi instance sequential subprocess this is the multi instace scope body.
   *
   * @param targetScope
   * @param activityExecutionMapping
   * @return
   */
  public static PvmExecutionImpl getScopeExecution(ScopeImpl scope, Map<ScopeImpl, PvmExecutionImpl> activityExecutionMapping) {
    ScopeImpl flowScope = scope.getFlowScope();
    return activityExecutionMapping.get(flowScope);
  }

  // helpers ////////////////////////////////////////////////

  protected static void ensureConcurrentScope(PvmExecutionImpl execution) {
    ensureScope(execution);
    ensureConcurrent(execution);
  }

  protected static void ensureConcurrent(PvmExecutionImpl execution) {
    if(!execution.isConcurrent()) {
      throw new ProcessEngineException("Execution must be concurrent.");
    }
  }

  protected static void ensureScope(PvmExecutionImpl execution) {
    if(!execution.isScope()) {
      throw new ProcessEngineException("Execution must be scope.");
    }
  }

  /**
   * Creates an activity execution mapping, when the scope hierarchy and the execution hierarchy are out of sync.
   *
   * @param scopeExecutions
   * @param scopes
   * @return
   */
  public static Map<ScopeImpl, PvmExecutionImpl> createActivityExecutionMapping(List<PvmExecutionImpl> scopeExecutions, List<ScopeImpl> scopes) {
    // The trees are out of sync.
    // We are missing executions:
    int numOfMissingExecutions = scopes.size() - scopeExecutions.size();

    // We need to find out which executions are missing.
    // Consider: elements which did not use to be scopes are now scopes.
    // But, this does not mean that all instances of elements which became scopes
    // are missing their executions. We could have created new instances in the
    // lower part of the tree after legacy behavior was turned off while instances of these elements
    // further up the hierarchy miss scopes. So we need to iterate from the top down and skip all scopes which
    // were not scopes before:
    Collections.reverse(scopeExecutions);
    Collections.reverse(scopes);

    Map<ScopeImpl, PvmExecutionImpl> mapping  = new HashMap<ScopeImpl, PvmExecutionImpl>();
    // process definition / process instance.
    mapping.put(scopes.get(0), scopeExecutions.get(0));
    // nested activities
    int executionCounter = 0;
    for(int i = 1; i < scopes.size(); i++) {
      ActivityImpl scope = (ActivityImpl) scopes.get(i);
      if(numOfMissingExecutions > 0 && wasNoScope(scope)) {
        // found a missing scope
        numOfMissingExecutions--;
      }
      else {
        executionCounter++;
      }

      if (executionCounter >= scopeExecutions.size()) {
        throw new ProcessEngineException("Cannot construct activity-execution mapping: there are "
            + "more scope executions missing than explained by the flow scope hierarchy.");
      }

      PvmExecutionImpl execution = scopeExecutions.get(executionCounter);
      mapping.put(scope, execution);
    }

    return mapping;
  }

  /**
   * Determines whether the given scope was a scope (boolean flag isScope)
   * when used as a multi-instance activity
   */
  protected static boolean wasNoScope(ActivityImpl activity) {
    ActivityBehavior activityBehavior = activity.getActivityBehavior();
    ActivityBehavior parentActivityBehavior = (ActivityBehavior) (activity.getFlowScope() != null ? activity.getFlowScope().getActivityBehavior() : null);
    return (activityBehavior instanceof EventSubProcessActivityBehavior)
        || (activityBehavior instanceof SubProcessActivityBehavior
              && parentActivityBehavior instanceof SequentialMultiInstanceActivityBehavior)
        || (activityBehavior instanceof ReceiveTaskActivityBehavior
              && parentActivityBehavior instanceof MultiInstanceActivityBehavior);
  }

  /**
   * Tolerates the broken execution trees fixed with CAM-3727 where there may be more
   * ancestor scope executions than ancestor flow scopes;
   *
   * In that case, the argument execution is removed, the parent execution of the argument
   * is returned such that one level of mismatch is corrected.
   *
   * Note that this does not necessarily skip the correct scope execution, since
   * the broken parent-child relationships may be anywhere in the tree (e.g. consider a non-interrupting
   * boundary event followed by a subprocess (i.e. scope), when the subprocess ends, we would
   * skip the subprocess's execution).
   *
   */
  public static PvmExecutionImpl determinePropagatingExecutionOnEnd(PvmExecutionImpl propagatingExecution) {
    // TODO: use activity execution mapping
    if (!propagatingExecution.isScope()) {
      // non-scope executions may end in the "wrong" flow scope
      return propagatingExecution;
    }

    ScopeExecutionCollector scopeExecutionCollector = new ScopeExecutionCollector();
    new ExecutionWalker(propagatingExecution)
      .addPreCollector(scopeExecutionCollector)
      .walkUntil();
    List<PvmExecutionImpl> scopeExecutions = scopeExecutionCollector.getExecutions();

    ScopeCollector scopeCollector = new ScopeCollector();
    new FlowScopeWalker(propagatingExecution.getActivity().getFlowScope())
      .addPreCollector(scopeCollector)
      .walkUntil();

    List<ScopeImpl> flowScopes = scopeCollector.getScopes();

    if (scopeExecutions.size() > flowScopes.size()) {
      // skip one scope
      propagatingExecution.remove();
      PvmExecutionImpl parent = propagatingExecution.getParent();
      parent.setActivity(propagatingExecution.getActivity());
      return propagatingExecution.getParent();
    }
    else {
      return propagatingExecution;
    }
  }

  /**
   * Concurrent + scope executions are legacy and could occur in processes with non-interrupting
   * boundary events or event subprocesses
   */
  public static boolean isConcurrentScope(PvmExecutionImpl propagatingExecution) {
    return propagatingExecution.isConcurrent() && propagatingExecution.isScope();
  }

  /**
   * <p>Required for migrating active sequential MI receive tasks. These activities were formerly not scope,
   * but are now. This has the following implications:
   *
   * <p>Before migration:
   * <ul><li> the event subscription is attached to the miBody scope execution</ul>
   *
   * <p>After migration:
   * <ul><li> a new subscription is created for every instance
   * <li> the new subscription is attached to a dedicated scope execution as a child of the miBody scope
   *   execution</ul>
   *
   * <p>Thus, this method removes the subscription on the miBody scope
   */
  public static void removeLegacySubscriptionOnParent(ExecutionEntity execution, EventSubscriptionEntity eventSubscription) {
    ActivityImpl activity = execution.getActivity();
    if (activity == null) {
      return;
    }

    ActivityBehavior behavior = activity.getActivityBehavior();
    ActivityBehavior parentBehavior = (ActivityBehavior) (activity.getFlowScope() != null ? activity.getFlowScope().getActivityBehavior() : null);

    if (behavior instanceof ReceiveTaskActivityBehavior &&
        parentBehavior instanceof MultiInstanceActivityBehavior) {
      List<EventSubscriptionEntity> parentSubscriptions = execution.getParent().getEventSubscriptions();

      for (EventSubscriptionEntity subscription : parentSubscriptions) {
        // distinguish a boundary event on the mi body with the same message name from the receive task subscription
        if (areEqualEventSubscriptions(subscription, eventSubscription)) {
          subscription.delete();
        }
      }
    }

  }

  /**
   * Checks if the parameters are the same apart from the execution id
   */
  protected static boolean areEqualEventSubscriptions(EventSubscriptionEntity subscription1, EventSubscriptionEntity subscription2) {
    return !valuesDiffer(subscription1.getEventType(), subscription2.getEventType())
        && !valuesDiffer(subscription1.getEventName(), subscription2.getEventName())
        && !valuesDiffer(subscription1.getActivityId(), subscription2.getActivityId());

  }

  protected static <T> boolean valuesDiffer(T value1, T value2) {
    if ((value1 != null && !value1.equals(value2))
        || (value1 == null && value2 != null)) {
      return true;
    }

    return false;
  }


}
