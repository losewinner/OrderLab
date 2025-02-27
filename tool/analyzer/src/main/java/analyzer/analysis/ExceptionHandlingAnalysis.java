package analyzer.analysis;

import soot.*;
import soot.jimple.DefinitionStmt;
import soot.jimple.InvokeExpr;
import soot.jimple.NewExpr;
import soot.jimple.ThrowStmt;
import soot.toolkits.graph.UnitGraph;

import java.util.*;

public final class ExceptionHandlingAnalysis {
    public final SootMethod method;
    public final Body body;
    public final UnitGraph graph;
    public final PatchingChain<Unit> units;

    public final Map<Unit, Integer> ids = new HashMap<>(); // unit -> unit number
    public final Map<Unit, SootMethod> libCalls = new HashMap<>(); // units that invoke library calls and may throw exceptions
    public final Map<Unit, SootMethod> internalCalls = new HashMap(); // units that invoke internal calls
    public final Map<SootMethod, Set<Unit>> methodOccurrences = new HashMap<>(); // internal method -> occurrence locations
    public final Map<Unit, SootClass> newExceptions = new HashMap<>(); // units that initialize an exception

    private void markMethodOccurrence(final SootMethod method, final Unit unit) {
        if (methodOccurrences.containsKey(method)) {
            methodOccurrences.get(method).add(unit);
        } else {
            methodOccurrences.put(method, new HashSet<>(Collections.singletonList(unit)));
        }
    }

    // exception that may be thrown by this method -> units that may throw this uncaught exception
    public final Map<SootClass, Set<Unit>> methodExceptions = new HashMap<>();

    // unit -> potential exceptions carried
    public final Map<Unit, Set<SootClass>> unitCarryingException = new HashMap<>();
    // invocation (not throw) unit -> potential exceptions thrown
    public final Map<Unit, Set<SootClass>> unitThrowingException = new HashMap<>();
    // unit carrying exception -> units throwing this exception
    public final Map<Unit, List<Unit>> throwLocations = new HashMap<>();

    // ThrowStmt -> exception type -> units that carry this thrown exception
    public final Map<Unit, Map<SootClass, Set<Unit>>> throw2transit = new HashMap<>();
    // Unit carrying an exception -> exception type -> units throwing this exception
    public final Map<Unit, Map<SootClass, Set<Unit>>> transit2throw = new HashMap<>();
    //Exception that is created and uncaught in this method.
    public final Set<SootClass> NewExceptionUncaught = new HashSet<>();

    public boolean enableExceptionReturn = false;

    public boolean updateWith(final SootMethod method, final Set<SootClass> exceptions) {
//        if (this.method.getDeclaringClass().getName()
//                .equals("org.apache.zookeeper.server.quorum.Leader$LearnerCnxAcceptor$LearnerCnxAcceptorHandler")) {
//            System.out.println(this.method.getName() + " -- " + method.getName());
//        }
        boolean flag = false;
        for (final Unit unit : methodOccurrences.get(method)) {
            if (unitThrowingException.get(unit).addAll(exceptions)) {
                flag = true;
            }
        }
        if (flag) {
            updateExceptions();
        }
        return update;
    }

    public boolean update = false; // for global interprocedural analysis

    public ExceptionHandlingAnalysis(final List<SootClass> classes, final SootMethod method, final Body body,
                                     final UnitGraph graph, final GlobalCallGraphAnalysis globalCallGraphAnalysis,
                                     final ThreadSchedulingAnalysis threadSchedulingAnalysis,
                                     final Map<SootMethod, ExceptionReturnAnalysis> exceptionReturnAnalysis,
                                     final boolean enableExceptionReturn ) {
        this.method = method;
        this.body = body;
        this.graph = graph;
        this.units = body.getUnits();
        this.enableExceptionReturn = enableExceptionReturn;
        Set<SootClass> classSet = new HashSet<>(classes);
        int counter = 0;
        // prepare ids, and infer throw locations for the variables carrying exceptions
        for (final Unit unit : units) {
            ids.put(unit, counter++);
            if (unit instanceof DefinitionStmt) {
                final Value lhs = ((DefinitionStmt) unit).getLeftOp();
                if (lhs instanceof Local) {
                    final Value rhs = ((DefinitionStmt) unit).getRightOp();
                    if (rhs instanceof NewExpr) {
                        final SootClass invocationClass = ((NewExpr) rhs).getBaseType().getSootClass();
                        if (SubTypingAnalysis.v().isThrowable(invocationClass)) {
                            final SootClass exception = invocationClass;
                            unitCarryingException.put(unit, new HashSet<>(Collections.singletonList(exception)));
                            newExceptions.put(unit, exception);
                            searchThrowLocation(unit, (Local) lhs, exceptionReturnAnalysis);
                        }
                    } else if (rhs instanceof InvokeExpr) {
                        // Deal with new created exceptions in wrappers
                        final SootMethod calleeMethod = ((InvokeExpr) rhs).getMethod();

                        if (enableExceptionReturn) {
                            if (ExceptionReturnAnalysis.isWrapper(calleeMethod)) {
                                for (SootClass exception : exceptionReturnAnalysis.get(calleeMethod).newExceptions) {
                                    unitCarryingException.put(unit, new HashSet<>(Collections.singletonList(exception)));
                                    searchThrowLocation(unit, (Local) lhs, exceptionReturnAnalysis);
                                }
                            }
                        }
                    }
                }
            }
        }
        // infer the remaining throw locations (the handlers)
        for (final Trap trap : body.getTraps()) {
            final Unit unit = trap.getHandlerUnit();
            final Value value = ((DefinitionStmt) unit).getLeftOp();
            if (value instanceof Local) {
                unitCarryingException.put(unit, new HashSet<>());
                searchThrowLocation(unit, (Local) value, exceptionReturnAnalysis);
            }
        }
        // infer the exception in newExceptions that is uncaught
        extractNewExceptionsUncaught();
        //if (method.getSubSignature().equals("void checkCellSizeLimit(org.apache.hadoop.hbase.regionserver.HRegion, org.apache.hadoop.hbase.client.Mutation)"))
        //        System.out.println(NewExceptionUncaught);





        // collect the invocations
        for (final Unit unit : units) {
            // Transparent Lambda Runnable
            if (threadSchedulingAnalysis.wrapper2Call.containsKey(unit)) {
                SootMethod call = threadSchedulingAnalysis.wrapper2Call.get(unit);
                internalCalls.put(unit, call);
                for (final SootMethod virtualMethod : globalCallGraphAnalysis.virtualCalls.get(call)) {
                    if (virtualMethod.hasActiveBody()) {
                        System.out.println(virtualMethod);
                        markMethodOccurrence(virtualMethod, unit);
                    }
                }
                unitThrowingException.put(unit, new HashSet<>());
                continue;
            }

            for (final ValueBox valueBox : unit.getUseBoxes()) {
                final Value value = valueBox.getValue();
                if (value instanceof InvokeExpr) {
                    final SootMethod invocation = ((InvokeExpr) value).getMethod();
                    if (classSet.contains(invocation.getDeclaringClass())) {
                        internalCalls.put(unit, invocation);
                        for (final SootMethod virtualMethod : globalCallGraphAnalysis.virtualCalls.get(invocation)) {
                            if (virtualMethod.hasActiveBody()) {
                                markMethodOccurrence(virtualMethod, unit);
                            }
                        }
                        unitThrowingException.put(unit, new HashSet<>());
                    } else {
                        final List<SootClass> exceptions = invocation.getExceptions();
                        if (!exceptions.isEmpty()) {
                            libCalls.put(unit, invocation);
                            unitThrowingException.put(unit, new HashSet<>(exceptions));
                        }
                    }
                    break;
                }
            }
            // Possible Transparent Runnable got from ThreadScheduling analysis
        }
        // update the exceptions
        updateExceptions();
    }

    private void extractNewExceptionsUncaught() {
        for (Unit unitCarryingNewException : newExceptions.keySet()){
            Set<Unit> visited = new HashSet<>(throwLocations.get(unitCarryingNewException));
            LinkedList<Unit> unitsThrowNewException = new LinkedList<>(throwLocations.get(unitCarryingNewException));
            SootClass exception = newExceptions.get(unitCarryingNewException);
            while (!unitsThrowNewException.isEmpty()) {
                Unit unitThrowException = unitsThrowNewException.pollFirst();
                int id = ids.get(unitThrowException);
                boolean caught = false;
                for (final Trap trap : body.getTraps()) {
                    if (ids.get(trap.getBeginUnit()) <= id && id < ids.get(trap.getEndUnit())
                                && SubTypingAnalysis.v().isSubtype(exception, trap.getException())) {
                        //Caught the exception
                        caught = true;
                        final Unit handler = trap.getHandlerUnit();
                        if (throwLocations.containsKey(handler)) {
                            //if (this.method.getSubSignature().equals("void complexExceptionUncaught(int)"))
                                //System.out.println(handler);
                            for (Unit newUnitThrowException : throwLocations.get(handler)) {
                                if (!visited.contains(newUnitThrowException)) {
                                    //System.out.println(newUnitThrowException);
                                    //Breadth first search for uncaught of a newException
                                    unitsThrowNewException.addLast(newUnitThrowException);
                                    //Avoid add one throw statement twice
                                    visited.add(newUnitThrowException);
                                }
                            }
                        }
                        break;
                    }
                }
                if (!caught) {
                    NewExceptionUncaught.add(exception);
                    break;
                }
            }
        }
    }

    public void ttt() {
//        if (method.getDeclaringClass().getName()
//                .equals("org.apache.zookeeper.server.quorum.Leader$LearnerCnxAcceptor$LearnerCnxAcceptorHandler")) {
//            for (final Unit u : transit2throw.keySet()) {
//                System.out.println(u);
//                for (final SootClass cl : transit2throw.get(u).keySet()) {
//                    System.out.println(cl);
//                }
//                System.out.println("===");
//            }
//            System.out.println(method.getDeclaringClass().getName() + " -- " + method.getName());
//            System.out.println("---------");
//        }
    }

    private void searchThrowLocation(final Unit unit, final Local v,
                                     final Map<SootMethod, ExceptionReturnAnalysis> exceptionReturnAnalysis) {
        final Set<Value> vs = new HashSet<>();
        vs.add(v);
        final Set<Unit> visited = new HashSet<>();
        visited.add(unit);
        final LinkedList<Unit> q = new LinkedList<>();
        q.add(unit);
        final List<Unit> locations = new LinkedList<>();
        throwLocations.put(unit, locations);
        while (!q.isEmpty()) {
            final Unit node = q.pollFirst();
            if (node instanceof DefinitionStmt) {
                if (vs.contains(((DefinitionStmt) node).getRightOp())) {
                    vs.add(((DefinitionStmt) node).getLeftOp());
                }
                // Deal with transparent wrapper
                Value rhs = ((DefinitionStmt) node).getRightOp();
                if (rhs instanceof InvokeExpr) {
                    final SootMethod calleeMethod = ((InvokeExpr) rhs).getMethod();
                    if (this.enableExceptionReturn && ExceptionReturnAnalysis.isWrapper(calleeMethod)
                            && exceptionReturnAnalysis.get(calleeMethod).transparent) {
                        for (Value param : ((InvokeExpr) rhs).getArgs()) {
                            if (vs.contains(param)) {
                                vs.add(((DefinitionStmt) node).getLeftOp());
                                break;
                            }
                        }

                    }
                }
            }
            if (node instanceof ThrowStmt) {
                if (vs.contains(((ThrowStmt) node).getOp())) {
                    locations.add(node);
                }
                continue;
            }
            for (final Unit succ : graph.getSuccsOf(node)) {
                if (!visited.contains(succ)) {
                    visited.add(succ);
                    boolean kill = false;
                    for (final ValueBox valueBox : succ.getDefBoxes()) {
                        if (valueBox.getValue() == v) {
                            kill = true;
                            break;
                        }
                    }
                    if (!kill) {
                        q.add(succ);
                    }
                }
            }
        }
    }

    public void updateExceptions() {
        for (final Map.Entry<Unit, Set<SootClass>> entry : unitThrowingException.entrySet()) {
            final Unit unit = entry.getKey();
            final Set<SootClass> exceptions = entry.getValue();
            updateThrow(unit, exceptions);
        }
        boolean shouldContinue = true;
        while (shouldContinue) {
            shouldContinue = false;
            for (final Map.Entry<Unit, Set<SootClass>> entry : unitCarryingException.entrySet()) {
                final Unit unit = entry.getKey();
                final Set<SootClass> exceptions = entry.getValue();
                for (final SootClass exception : exceptions) {
                    for (final Unit location : throwLocations.get(unit)) {
                        if (updateThrow2Transit(location, exception, unit)) {
                            shouldContinue = true;
                        }
                    }
                }
            }

            for (final Map.Entry<Unit, Map<SootClass, Set<Unit>>> entry : throw2transit.entrySet()) {
                final Unit unit = entry.getKey();
                final Set<SootClass> exceptions = entry.getValue().keySet();
                if (updateThrow(unit, exceptions)) {
                    shouldContinue = true;
                }
            }
        }
    }

    private boolean updateThrow(final Unit unit, final Set<SootClass> exceptions) {
        boolean shouldContinue = false;
        final int id = ids.get(unit);
        for (final SootClass exception : exceptions) {
            boolean caught = false;
            for (final Trap trap : body.getTraps()) {
                if (ids.get(trap.getBeginUnit()) <= id && id < ids.get(trap.getEndUnit())
                        && SubTypingAnalysis.v().isSubtype(exception, trap.getException())) {
                    caught = true;
                    final Unit handler = trap.getHandlerUnit();
                    if (updateTransit2Throw(handler, exception, unit)) {
                        unitCarryingException.get(handler).add(exception);
                        shouldContinue = true;
                    }
                    break;
                }
            }
            if (!caught) {
                if (updateMethodException(unit, exception)) {
                    shouldContinue = true;
                    this.update = true;
                }
            }
        }
        return shouldContinue;
    }

    private boolean updateMethodException(final Unit unit, final SootClass exception) {
        Set<Unit> set;
        if (methodExceptions.containsKey(exception)) {
            set = methodExceptions.get(exception);
        } else {
            set = new HashSet<>();
            methodExceptions.put(exception, set);
        }
        if (set.contains(unit)) {
            return false;
        } else {
            set.add(unit);
            return true;
        }
    }

    private boolean updateThrow2Transit(final Unit x, final SootClass ex, final Unit y) {
        return updateMap(throw2transit, x, ex, y);
    }

    private boolean updateTransit2Throw(final Unit x, final SootClass ex, final Unit y) {
        return updateMap(transit2throw, x, ex, y);
    }

    private boolean updateMap(final Map<Unit, Map<SootClass, Set<Unit>>> m, final Unit x, final SootClass ex, final Unit y) {
        Map<SootClass, Set<Unit>> map;
        if (m.containsKey(x)) {
            map = m.get(x);
        } else {
            map = new HashMap<>();
            m.put(x, map);
        }
        Set<Unit> set;
        if (map.containsKey(ex)) {
            set = map.get(ex);
        } else {
            set = new HashSet<>();
            map.put(ex, set);
        }
        if (set.contains(y)) {
            return false;
        } else {
            set.add(y);
            return true;
        }
    }
}
