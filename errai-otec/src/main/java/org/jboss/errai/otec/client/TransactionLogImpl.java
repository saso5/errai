/*
 * Copyright 2013 JBoss, by Red Hat, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.errai.otec.client;

import org.jboss.errai.otec.client.mutation.Mutation;
import org.jboss.errai.otec.client.operation.OTOperation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

/**
 * @author Christian Sadilek <csadilek@redhat.com>
 * @author Mike Brock
 */
public class TransactionLogImpl implements TransactionLog {
  private final Object lock = new Object();

  private volatile boolean logDirty = false;
  private final List<StateSnapshot> stateSnapshots = new LinkedList<StateSnapshot>();
  private final List<OTOperation> transactionLog = new LinkedList<OTOperation>();
  private final OTEntity entity;

  private TransactionLogImpl(final OTEntity entity) {
    this.entity = entity;
    stateSnapshots.add(new StateSnapshot(entity.getRevision(), entity.getState().snapshot()));
  }

  public static TransactionLog createTransactionLog(final OTEntity entity) {
    return new TransactionLogImpl(entity);
  }

  @Override
  public Object getLock() {
    return lock;
  }

  @Override
  public List<OTOperation> getLog() {
    synchronized (lock) {
      return transactionLog;
    }
  }

  @Override
  public int purgeTo(final int revision) {
    if (revision < 0) {
      return 0;
    }
    synchronized (lock) {
      int purged = 0;
      final Iterator<OTOperation> iterator = transactionLog.iterator();
      while (iterator.hasNext()) {
        if (iterator.next().getRevision() < revision) {
          purged++;
          iterator.remove();
        }
        else {
          break;
        }
      }

      if (stateSnapshots.size() > 1) {
        final Iterator<StateSnapshot> iterator1 = stateSnapshots.iterator();
        while (iterator1.hasNext()) {
          if (iterator1.next().getRevision() < revision) {
            iterator1.remove();
          }
          else {
            break;
          }
        }
      }

      return purged;
    }
  }

  @Override
  public void pruneFromOperation(final OTOperation operation) {
    synchronized (lock) {
      final int index = transactionLog.indexOf(operation);
      if (index == -1) {
        return;
      }

      final ListIterator<OTOperation> delIter = transactionLog.listIterator(index);
      while (delIter.hasNext()) {
        entity.decrementRevisionCounter();
        delIter.next().removeFromCanonHistory();
      }
      logDirty = true;
    }
  }

  @Override
  public List<OTOperation> getLogFromId(final int revision, boolean includeNonCanon) {
    synchronized (lock) {
      if (transactionLog.isEmpty()) {
        return Collections.emptyList();
      }

      final ListIterator<OTOperation> operationListIterator = transactionLog.listIterator(transactionLog.size());
      final List<OTOperation> operationList = new ArrayList<OTOperation>();

      while (operationListIterator.hasPrevious()) {
        final OTOperation previous = operationListIterator.previous();
        if (!includeNonCanon && !previous.isCanon()) {
          continue;
        }
        operationList.add(previous);
        if (previous.getRevision() == revision) {
          Collections.reverse(operationList);
          return operationList;
        }
      }

      if ((revision - 1) == transactionLog.get(transactionLog.size() - 1).getRevision()) {
        return Collections.emptyList();
      }
      else {
//        LogUtil.log("Could not find revision: " + revision);
//        LogUtil.log("Current Log:\n");
//        for (OTOperation operation : transactionLog) {
//          LogUtil.log("Rev:" + operation.getRevision() + ":" + operation);
//        }


        throw new OTException("unable to find revision in log: " + revision);
      }
    }
  }

  @Override
  public List<OTOperation> getCanonLog() {
    synchronized (lock) {
      if (logDirty) {
        final List<OTOperation> canonLog = new ArrayList<OTOperation>(transactionLog.size());
        for (final OTOperation operation : transactionLog) {
          if (operation.isCanon()) {
            canonLog.add(operation);
          }
        }
        return canonLog;
      }
      else {
        return transactionLog;
      }
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public State getEffectiveStateForRevision(final int revision) {
    synchronized (lock) {
      final StateSnapshot latestSnapshotState = getLatestParentSnapshot(revision);
      final State stateToTranslate = latestSnapshotState.getState().snapshot();
      final ListIterator<OTOperation> operationListIterator
          = transactionLog.listIterator(transactionLog.size());

      while (operationListIterator.hasPrevious()) {
        if (operationListIterator.previous().getRevision() == latestSnapshotState.getRevision()) {
          break;
        }
      }

      while (operationListIterator.hasNext()) {
        final OTOperation op = operationListIterator.next();
        if (!op.isCanon()) continue;

        if (op.getRevision() < revision) {
          for (final Mutation mutation : op.getMutations()) {
            mutation.apply(stateToTranslate);
          }
        }
      }

      makeSnapshot(revision, stateToTranslate);
      return stateToTranslate;
    }
  }

  private StateSnapshot getLatestParentSnapshot(final int revision) {
    synchronized (lock) {
      final ListIterator<StateSnapshot> snapshotListIterator = stateSnapshots.listIterator(stateSnapshots.size());

      while (snapshotListIterator.hasPrevious()) {
        final StateSnapshot stateSnapshot = snapshotListIterator.previous();
        if (stateSnapshot.getRevision() <= revision) {
          return stateSnapshot;
        }
      }

      throw new RuntimeException("no parent state for: " + revision);
    }
  }

  private void makeSnapshot(final int revision, final State state) {
    stateSnapshots.add(new StateSnapshot(revision, state));
  }

  @Override
  public void appendLog(final OTOperation operation) {
    synchronized (lock) {
      if (operation.isNoop()) {
        return;
      }

      transactionLog.add(operation);
    }
  }

  @Override
  public void insertLog(int revision, OTOperation operation) {
    synchronized (lock) {
      final ListIterator<OTOperation> operationListIterator
          = transactionLog.listIterator(transactionLog.size());

      while (operationListIterator.hasPrevious()) {
        if (operationListIterator.previous().getRevision() == revision) {
          operationListIterator.set(operation);
          break;
        }
      }
    }
  }

  @Override
  public void markDirty() {
    synchronized (lock) {
      this.logDirty = true;
    }
  }

  @Override
  public void cleanLog() {
    synchronized (lock) {
      final Iterator<OTOperation> iterator = transactionLog.iterator();
      while (iterator.hasNext()) {
        if (!iterator.next().isCanon()) {
          iterator.remove();
        }
      }
      logDirty = false;
    }
  }

  @Override
  public String toString() {
    return Arrays.toString(getCanonLog().toArray());
  }

  private static class StateSnapshot {
    private final int revision;
    private final State state;

    private StateSnapshot(final int revision, final State state) {
      this.revision = revision;
      this.state = state;
    }

    private int getRevision() {
      return revision;
    }

    private State getState() {
      return state;
    }
  }
}