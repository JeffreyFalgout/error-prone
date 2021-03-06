/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
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

package com.google.errorprone.bugpatterns.threadsafety;

import static com.google.errorprone.BugPattern.Category.JDK;
import static com.google.errorprone.BugPattern.MaturityLevel.MATURE;
import static com.google.errorprone.BugPattern.SeverityLevel.ERROR;

import com.google.common.base.Joiner;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;

/**
 * @author cushon@google.com (Liam Miller-Cushon)
 */
@BugPattern(
  name = "GuardedByChecker",
  altNames = "GuardedBy",
  summary = "Checks for unguarded accesses to fields and methods with @GuardedBy annotations",
  category = JDK,
  severity = ERROR,
  maturity = MATURE
)
public class GuardedByChecker extends GuardedByValidator
    implements BugChecker.VariableTreeMatcher, BugChecker.MethodTreeMatcher {

  private static final String JUC_READ_WRITE_LOCK = "java.util.concurrent.locks.ReadWriteLock";

  @Override
  public Description matchMethod(MethodTree tree, final VisitorState state) {
    // Constructors (and field initializers, instance initalizers, and class initalizers) are free
    // to mutate guarded state without holding the necessary locks. It is assumed that all objects
    // (and classes) are thread-local during initialization.
    if (ASTHelpers.getSymbol(tree).isConstructor()) {
      return Description.NO_MATCH;
    }

    HeldLockAnalyzer.analyze(state, new HeldLockAnalyzer.LockEventListener() {
      @Override
      public void handleGuardedAccess(
          ExpressionTree tree, GuardedByExpression guard, HeldLockSet live) {
        report(GuardedByChecker.this.checkGuardedAccess(tree, guard, live, state), state);
      }
    });

    return GuardedByValidator.validate(this, tree, state);
  }

  @Override
  public Description matchVariable(VariableTree tree, VisitorState state) {
    // We only want to check field declarations for @GuardedBy usage. The VariableTree might be
    // for a local or a parameter, but they won't have @GuardedBy annotations.
    //
    // Field initializers (like constructors) are not checked for accesses of guarded fields.
    return GuardedByValidator.validate(this, tree, state);
  }

  protected Description checkGuardedAccess(Tree tree, GuardedByExpression guard,
      HeldLockSet locks, VisitorState state) {

    // TODO(cushon): support ReadWriteLocks
    //
    // A common pattern with ReadWriteLocks is to create a copy (either a field or a local
    // variable) to refer to the read and write locks. The analysis currently can't
    // recognize that locking the copies is equivalent to locking the read or write
    // locks directly.
    //
    // Also - there are currently no annotations to specify an access policy for
    // members guarded by ReadWriteLocks. We could allow accesses when either the
    // read or write locks are held, but that's not much better than enforcing
    // nothing.
    if (isRWLock(guard, state)) {
      return Description.NO_MATCH;
    }

    if (locks.allLocks().contains(guard)) {
      return Description.NO_MATCH;
    }

    return buildDescription(tree).setMessage(buildMessage(guard, locks)).build();
  }

  /**
   * Construct a diagnostic message, e.g.:
   *
   * <ul>
   * <li>This access should be guarded by 'this', which is not currently held
   * <li>This access should be guarded by 'this'; instead found 'mu'
   * <li>This access should be guarded by 'this'; instead found: 'mu1', 'mu2'
   * </ul>
   */
  private String buildMessage(GuardedByExpression guard, HeldLockSet locks) {
    StringBuilder message = new StringBuilder();
    message.append(
        String.format(
            "This access should be guarded by '%s'",
            guard));
    int heldLocks = locks.allLocks().size();
    if (heldLocks == 0) {
      message.append(", which is not currently held");
    } else {
      message.append(String.format("; instead found: '%s'",
          Joiner.on("', '").join(locks.allLocks())));
    }
    String content = message.toString();
    return content;
  }

  /**
   * Returns true if the lock expression corresponds to a
   * {@code java.util.concurrent.locks.ReadWriteLock}.
   */
  private static boolean isRWLock(GuardedByExpression guard, VisitorState state) {
    Type guardType = guard.type();
    if (guardType == null) {
      return false;
    }

    Symbol rwLockSymbol = state.getSymbolFromString(JUC_READ_WRITE_LOCK);
    if (rwLockSymbol  == null) {
      return false;
    }

    return state.getTypes().isSubtype(guardType, rwLockSymbol.type);
  }

  // TODO(cushon) - this is a hack. Provide an abstraction for matchers that need to do
  // stateful visiting? (e.g. a traversal that passes along a set of held locks...)
  private void report(Description description, VisitorState state) {
    if (description == null || description == Description.NO_MATCH) {
      return;
    }
    state.reportMatch(description);
  }
}
