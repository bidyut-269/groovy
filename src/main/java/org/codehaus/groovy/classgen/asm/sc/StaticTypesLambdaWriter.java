/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.codehaus.groovy.classgen.asm.sc;

import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.InnerClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.LambdaExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.classgen.asm.BytecodeHelper;
import org.codehaus.groovy.classgen.asm.BytecodeVariable;
import org.codehaus.groovy.classgen.asm.CompileStack;
import org.codehaus.groovy.classgen.asm.LambdaWriter;
import org.codehaus.groovy.classgen.asm.OperandStack;
import org.codehaus.groovy.classgen.asm.WriterController;
import org.codehaus.groovy.classgen.asm.WriterControllerFactory;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.transform.stc.StaticTypesMarker;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.codehaus.groovy.classgen.asm.sc.StaticInvocationWriter.PARAMETER_TYPE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ALOAD;

/**
 * Writer responsible for generating lambda classes in statically compiled mode.
 */
public class StaticTypesLambdaWriter extends LambdaWriter {
    public static final String DO_CALL = "doCall";
    public static final String ORIGINAL_PARAMETERS_WITH_EXACT_TYPE = "__ORIGINAL_PARAMETERS_WITH_EXACT_TYPE";
    public static final String LAMBDA_SHARED_VARIABLES = "__LAMBDA_SHARED_VARIABLES";
    public static final String THIS = "__this";
    private StaticTypesClosureWriter staticTypesClosureWriter;
    private WriterController controller;
    private WriterControllerFactory factory;
    private final Map<Expression,ClassNode> lambdaClassMap = new HashMap<>();

    public StaticTypesLambdaWriter(WriterController wc) {
        super(wc);
        this.staticTypesClosureWriter = new StaticTypesClosureWriter(wc);
        this.controller = wc;
        this.factory = new WriterControllerFactory() {
            public WriterController makeController(final WriterController normalController) {
                return controller;
            }
        };
    }

    @Override
    public void writeLambda(LambdaExpression expression) {
        ClassNode parameterType = expression.getNodeMetaData(PARAMETER_TYPE);

        List<MethodNode> abstractMethodNodeList =
                parameterType.redirect().getMethods().stream()
                        .filter(MethodNode::isAbstract)
                        .collect(Collectors.toList());

        if (!(isFunctionInterface(parameterType) && abstractMethodNodeList.size() == 1)) {
            // if the parameter type is not real FunctionInterface, generate the default bytecode, which is actually a closure
            super.writeLambda(expression);
            return;
        }

        MethodNode abstractMethodNode = abstractMethodNodeList.get(0);
        String abstractMethodDesc = createMethodDescriptor(abstractMethodNode);

        ClassNode classNode = controller.getClassNode();
        boolean isInterface = classNode.isInterface();
        ClassNode lambdaClassNode = getOrAddLambdaClass(expression, ACC_PUBLIC | (isInterface ? ACC_STATIC : 0));
        MethodNode syntheticLambdaMethodNode = lambdaClassNode.getMethods(DO_CALL).get(0);

        MethodVisitor mv = controller.getMethodVisitor();

        OperandStack operandStack = controller.getOperandStack();

        if (controller.getMethodNode().isStatic()) {
            operandStack.pushConstant(ConstantExpression.NULL);
        } else {
            mv.visitVarInsn(ALOAD, 0);
            operandStack.push(classNode);
        }

        Parameter[] lambdaSharedVariableParameters = loadSharedVariables(syntheticLambdaMethodNode);

        mv.visitInvokeDynamicInsn(
                abstractMethodNode.getName(),
                createAbstractMethodDesc(syntheticLambdaMethodNode, parameterType),
                createBootstrapMethod(isInterface),
                createBootstrapMethodArguments(abstractMethodDesc, lambdaClassNode, syntheticLambdaMethodNode)
        );
        operandStack.replace(parameterType.redirect(), lambdaSharedVariableParameters.length + 1);
    }

    private Parameter[] loadSharedVariables(MethodNode syntheticLambdaMethodNode) {
        OperandStack operandStack = controller.getOperandStack();
        CompileStack compileStack = controller.getCompileStack();

        Parameter[] lambdaSharedVariableParameters = syntheticLambdaMethodNode.getNodeMetaData(LAMBDA_SHARED_VARIABLES);
        for (Parameter parameter : lambdaSharedVariableParameters) {
            String parameterName = parameter.getName();
//            loadReference(parameterName, controller);
//            if (parameter.getNodeMetaData(LambdaWriter.UseExistingReference.class)==null) {
//                parameter.setNodeMetaData(LambdaWriter.UseExistingReference.class,Boolean.TRUE);
//            }

            BytecodeVariable variable = compileStack.getVariable(parameterName, true);
            operandStack.loadOrStoreVariable(variable, false);
        }
        return lambdaSharedVariableParameters;
    }

    private String createAbstractMethodDesc(MethodNode syntheticLambdaMethodNode, ClassNode parameterType) {
        List<Parameter> lambdaSharedVariableList = new LinkedList<Parameter>(Arrays.asList(syntheticLambdaMethodNode.getNodeMetaData(LAMBDA_SHARED_VARIABLES)));

        prependThis(lambdaSharedVariableList);

        return BytecodeHelper.getMethodDescriptor(parameterType.redirect(), lambdaSharedVariableList.toArray(Parameter.EMPTY_ARRAY));
    }

    private Handle createBootstrapMethod(boolean isInterface) {
        return new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                isInterface
        );
    }

    private Object[] createBootstrapMethodArguments(String abstractMethodDesc, ClassNode lambdaClassNode, MethodNode syntheticLambdaMethodNode) {
        return new Object[]{
                Type.getType(abstractMethodDesc),
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        lambdaClassNode.getName(),
                        syntheticLambdaMethodNode.getName(),
                        BytecodeHelper.getMethodDescriptor(syntheticLambdaMethodNode),
                        false
                ),
                Type.getType(BytecodeHelper.getMethodDescriptor(syntheticLambdaMethodNode.getReturnType(), syntheticLambdaMethodNode.getNodeMetaData(ORIGINAL_PARAMETERS_WITH_EXACT_TYPE)))
        };
    }

    private String createMethodDescriptor(MethodNode abstractMethodNode) {
        return BytecodeHelper.getMethodDescriptor(
                abstractMethodNode.getReturnType().getTypeClass(),
                Arrays.stream(abstractMethodNode.getParameters())
                        .map(e -> e.getType().getTypeClass())
                        .toArray(Class[]::new)
        );
    }

    private boolean isFunctionInterface(ClassNode parameterType) {
        return parameterType.redirect().isInterface() && !parameterType.redirect().getAnnotations(ClassHelper.FunctionalInterface_Type).isEmpty();
    }

    public ClassNode getOrAddLambdaClass(LambdaExpression expression, int mods) {
        ClassNode lambdaClass = lambdaClassMap.get(expression);
        if (lambdaClass == null) {
            lambdaClass = createLambdaClass(expression, mods);
            lambdaClassMap.put(expression, lambdaClass);
            controller.getAcg().addInnerClass(lambdaClass);
            lambdaClass.addInterface(ClassHelper.GENERATED_LAMBDA_TYPE);
            lambdaClass.putNodeMetaData(WriterControllerFactory.class, factory);
        }
        return lambdaClass;
    }

    protected ClassNode createLambdaClass(LambdaExpression expression, int mods) {
        ClassNode outerClass = controller.getOutermostClass();
        ClassNode classNode = controller.getClassNode();
        String name = genLambdaClassName();
        boolean staticMethodOrInStaticClass = controller.isStaticMethod() || classNode.isStaticClass();

        InnerClassNode answer = new InnerClassNode(classNode, name, mods, ClassHelper.LAMBDA_TYPE.getPlainNodeReference());
        answer.setEnclosingMethod(controller.getMethodNode());
        answer.setSynthetic(true);
        answer.setUsingGenerics(outerClass.isUsingGenerics());
        answer.setSourcePosition(expression);

        if (staticMethodOrInStaticClass) {
            answer.setStaticClass(true);
        }
        if (controller.isInScriptBody()) {
            answer.setScriptBody(true);
        }

        addSyntheticLambdaMethodNode(expression, answer);

        return answer;
    }

    private String genLambdaClassName() {
        ClassNode classNode = controller.getClassNode();
        ClassNode outerClass = controller.getOutermostClass();
        MethodNode methodNode = controller.getMethodNode();

        return classNode.getName() + "$"
                + controller.getContext().getNextLambdaInnerName(outerClass, classNode, methodNode);
    }

    private void addSyntheticLambdaMethodNode(LambdaExpression expression, InnerClassNode answer) {
        Parameter[] parametersWithExactType = createParametersWithExactType(expression); // expression.getParameters();
        ClassNode returnType = expression.getNodeMetaData(StaticTypesMarker.INFERRED_RETURN_TYPE); //abstractMethodNode.getReturnType();
        Parameter[] localVariableParameters = getLambdaSharedVariables(expression);
        removeInitialValues(localVariableParameters);
        Parameter[] methodParameters = Stream.concat(Arrays.stream(localVariableParameters), Arrays.stream(parametersWithExactType)).toArray(Parameter[]::new);

        List<Parameter> methodParameterList = new LinkedList<Parameter>(Arrays.asList(methodParameters));

        Parameter thisParameter = prependThis(methodParameterList);

        MethodNode methodNode =
                answer.addMethod(
                        DO_CALL,
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                        returnType,
                        methodParameterList.toArray(Parameter.EMPTY_ARRAY),
                        ClassNode.EMPTY_ARRAY,
                        expression.getCode()
                );
        methodNode.putNodeMetaData(ORIGINAL_PARAMETERS_WITH_EXACT_TYPE, parametersWithExactType);
        methodNode.putNodeMetaData(LAMBDA_SHARED_VARIABLES, localVariableParameters);
        methodNode.setSourcePosition(expression);

        new TransformationVisitor(methodNode, thisParameter).visitMethod(methodNode);
    }

    private Parameter prependThis(List<Parameter> methodParameterList) {
        ClassNode classNode = controller.getClassNode();

        // FIXME the following code `classNode.setUsingGenerics(false)` is used to avoid the error:
        // ERROR MESSAGE: A transform used a generics containing ClassNode Test1 for the method public static int doCall(Test1 __this, java.lang.Integer e)  { ... } directly. You are not supposed to do this. Please create a new ClassNode referring to the old ClassNode and use the new ClassNode instead of the old one. Otherwise the compiler will create wrong descriptors and a potential NullPointerException in TypeResolver in the OpenJDK. If this is not your own doing, please report this bug to the writer of the transform.
        classNode.setUsingGenerics(false);

        Parameter thisParameter = new Parameter(classNode, THIS);
        thisParameter.setOriginType(classNode);
        thisParameter.setClosureSharedVariable(false);

        methodParameterList.add(0, thisParameter);

        return thisParameter;
    }

    private Parameter[] createParametersWithExactType(LambdaExpression expression) {
        Parameter[] parameters = expression.getParameters();
        if (parameters == null) {
            parameters = Parameter.EMPTY_ARRAY;
        }

        for (int i = 0; i < parameters.length; i++) {
            ClassNode inferredType = parameters[i].getNodeMetaData(StaticTypesMarker.INFERRED_TYPE);
            parameters[i].setType(inferredType);
            parameters[i].setOriginType(inferredType);
        }

        return parameters;
    }

    @Override
    protected ClassNode createClosureClass(final ClosureExpression expression, final int mods) {
        return staticTypesClosureWriter.createClosureClass(expression, mods);
    }

    private static final class TransformationVisitor extends ClassCodeVisitorSupport {
        private MethodNode methodNode;
        private Parameter thisParameter;

        public TransformationVisitor(MethodNode methodNode, Parameter thisParameter) {
            this.methodNode = methodNode;
            this.thisParameter = thisParameter;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return null;
        }

        @Override
        public void visitVariableExpression(VariableExpression expression) {
            if (expression.isClosureSharedVariable()) {
                final String variableName = expression.getName();
                Parameter[] parametersWithSameVariableName =
                        Arrays.stream(methodNode.getParameters())
                                .filter(e -> variableName.equals(e.getName()))
                                .toArray(Parameter[]::new);

                if (parametersWithSameVariableName.length != 1) {
                    throw new GroovyBugError(parametersWithSameVariableName.length + " parameters with same name " + variableName + " found(Expect only one matched).");
                }

                expression.setAccessedVariable(parametersWithSameVariableName[0]);
                expression.setClosureSharedVariable(false);

            }

            super.visitVariableExpression(expression);
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            if (!call.getMethodTarget().isStatic()) {
                Expression objectExpression = call.getObjectExpression();

                if (objectExpression instanceof VariableExpression) {
                    VariableExpression originalObjectExpression = (VariableExpression) objectExpression;
                    if (null == originalObjectExpression.getAccessedVariable()) {
                        VariableExpression thisVariable = new VariableExpression(thisParameter);
                        thisVariable.setSourcePosition(originalObjectExpression);

                        call.setObjectExpression(thisVariable);
                        call.setImplicitThis(false);
                    }
                }
            }

            super.visitMethodCallExpression(call);
        }
    }
}