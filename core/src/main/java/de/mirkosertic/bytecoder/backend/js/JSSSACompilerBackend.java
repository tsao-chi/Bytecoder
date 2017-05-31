/*
 * Copyright 2017 Mirko Sertic
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.mirkosertic.bytecoder.backend.js;

import de.mirkosertic.bytecoder.annotations.EmulatedByRuntime;
import de.mirkosertic.bytecoder.annotations.Import;
import de.mirkosertic.bytecoder.classlib.ExceptionRethrower;
import de.mirkosertic.bytecoder.classlib.java.lang.TArray;
import de.mirkosertic.bytecoder.classlib.java.lang.TClass;
import de.mirkosertic.bytecoder.classlib.java.lang.TString;
import de.mirkosertic.bytecoder.core.BytecodeAnnotation;
import de.mirkosertic.bytecoder.core.BytecodeArrayTypeRef;
import de.mirkosertic.bytecoder.core.BytecodeBasicBlock;
import de.mirkosertic.bytecoder.core.BytecodeClassinfoConstant;
import de.mirkosertic.bytecoder.core.BytecodeCodeAttributeInfo;
import de.mirkosertic.bytecoder.core.BytecodeControlFlowGraph;
import de.mirkosertic.bytecoder.core.BytecodeExceptionTableEntry;
import de.mirkosertic.bytecoder.core.BytecodeFieldRefConstant;
import de.mirkosertic.bytecoder.core.BytecodeInstruction;
import de.mirkosertic.bytecoder.core.BytecodeInstructionLOOKUPSWITCH;
import de.mirkosertic.bytecoder.core.BytecodeInstructionTABLESWITCH;
import de.mirkosertic.bytecoder.core.BytecodeLinkedClass;
import de.mirkosertic.bytecoder.core.BytecodeLinkerContext;
import de.mirkosertic.bytecoder.core.BytecodeMethodSignature;
import de.mirkosertic.bytecoder.core.BytecodeObjectTypeRef;
import de.mirkosertic.bytecoder.core.BytecodeOpcodeAddress;
import de.mirkosertic.bytecoder.core.BytecodePrimitiveTypeRef;
import de.mirkosertic.bytecoder.core.BytecodeProgram;
import de.mirkosertic.bytecoder.core.BytecodeTypeRef;
import de.mirkosertic.bytecoder.ssa.ArrayStoreExpression;
import de.mirkosertic.bytecoder.ssa.Block;
import de.mirkosertic.bytecoder.ssa.CheckCastExpression;
import de.mirkosertic.bytecoder.ssa.DirectInvokeMethodExpression;
import de.mirkosertic.bytecoder.ssa.Expression;
import de.mirkosertic.bytecoder.ssa.GotoExpression;
import de.mirkosertic.bytecoder.ssa.IFExpression;
import de.mirkosertic.bytecoder.ssa.InitVariableExpression;
import de.mirkosertic.bytecoder.ssa.InvokeStaticMethodExpression;
import de.mirkosertic.bytecoder.ssa.InvokeVirtualMethodExpression;
import de.mirkosertic.bytecoder.ssa.LookupSwitchExpression;
import de.mirkosertic.bytecoder.ssa.PutFieldExpression;
import de.mirkosertic.bytecoder.ssa.PutStaticExpression;
import de.mirkosertic.bytecoder.ssa.ReturnExpression;
import de.mirkosertic.bytecoder.ssa.ReturnVariableExpression;
import de.mirkosertic.bytecoder.ssa.TableSwitchExpression;
import de.mirkosertic.bytecoder.ssa.ThrowExpression;
import de.mirkosertic.bytecoder.ssa.Variable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

public class JSSSACompilerBackend extends AbstractJSBackend {

    public JSSSACompilerBackend() {
    }

    public String generateJumpCodeFor(BytecodeOpcodeAddress aTarget) {
        return "currentLabel = " + aTarget.getAddress()+";continue controlflowloop;";
    }

    @Override
    public String generateCodeFor(BytecodeLinkerContext aLinkerContext) {

        final AtomicLong theNumberOfVirtualCalls = new AtomicLong(0);
        final AtomicLong theNumberOfRealVirtualCalls = new AtomicLong(0);

        BytecodeLinkedClass theClassLinkedCass = aLinkerContext.linkClass(BytecodeObjectTypeRef.fromRuntimeClass(TClass.class));

        BytecodeLinkedClass theExceptionRethrower = aLinkerContext.linkClass(BytecodeObjectTypeRef.fromRuntimeClass(
                ExceptionRethrower.class));
        theExceptionRethrower.linkStaticMethod("registerExceptionOutcome", theRegisterExceptionOutcomeSignature);
        theExceptionRethrower.linkStaticMethod("getLastOutcomeOrNullAndReset", theGetLastExceptionOutcomeSignature);

        StringWriter theStrWriter = new StringWriter();
        final PrintWriter theWriter = new PrintWriter(theStrWriter);
        theWriter.println("'use strict';");

        theWriter.println("var bytecoder = {");

        theWriter.println();
        theWriter.println("     logDebug : function(aValue) { ");
        theWriter.println("         console.log(aValue);");
        theWriter.println("     }, ");

        theWriter.println();
        theWriter.println("     logByteArrayAsString : function(aArray) { ");
        theWriter.println("         var theResult = '';");
        theWriter.println("         for (var i=0;i<aArray.data.length;i++) {");
        theWriter.println("             theResult += String.fromCharCode(aArray.data[i]);");
        theWriter.println("         }");
        theWriter.println("         console.log(theResult);");
        theWriter.println("     }, ");
        theWriter.println();

        theWriter.println("     newString : function(aByteArray) { ");

        BytecodeObjectTypeRef theStringTypeRef = BytecodeObjectTypeRef.fromRuntimeClass(TString.class);
        BytecodeObjectTypeRef theArrayTypeRef = BytecodeObjectTypeRef.fromRuntimeClass(TArray.class);

        BytecodeMethodSignature theStringConstructorSignature = new BytecodeMethodSignature(BytecodePrimitiveTypeRef.VOID,
                new BytecodeTypeRef[]{new BytecodeArrayTypeRef(BytecodePrimitiveTypeRef.BYTE, 1)});

        // Construct a String
        theWriter.println("          var theNewString = " + JSWriterUtils.toClassName(theStringTypeRef) + ".emptyInstance();");
        theWriter.println("          var theBytes = " + JSWriterUtils.toClassName(theArrayTypeRef) + ".emptyInstance();");
        theWriter.println("          theBytes.data = aByteArray;");
        theWriter.println("          " + JSWriterUtils.toClassName(theStringTypeRef) + "." + JSWriterUtils.toMethodName("init", theStringConstructorSignature) + "(theNewString, theBytes);");
        theWriter.println("          return theNewString;");
        theWriter.println("     },");
        theWriter.println();
        theWriter.println("     newArray : function(aLength, aDefault) {");

        BytecodeObjectTypeRef theArrayType = BytecodeObjectTypeRef.fromRuntimeClass(TArray.class);
        theWriter.println("          var theInstance = " + JSWriterUtils.toClassName(theArrayType)+ ".emptyInstance();");
        theWriter.println("          theInstance.data = [];");
        theWriter.println("          theInstance.data.length = aLength;");
        theWriter.println("          for (var i=0;i<aLength;i++) {");
        theWriter.println("             theInstance.data[i] = aDefault;");
        theWriter.println("          }");
        theWriter.println("          return theInstance;");
        theWriter.println("     }");
        theWriter.println("}");
        theWriter.println();
        aLinkerContext.forEachClass(aEntry -> {

            if (aEntry.getValue().getBytecodeClass().getAccessFlags().isInterface()) {
                return;
            }

            final String theOverriddenParentClassName = getOverriddenParentClassFor(aEntry.getValue().getBytecodeClass());

            String theJSClassName = JSWriterUtils.toClassName(aEntry.getKey());
            theWriter.println("var " + theJSClassName + " = {");

            if (!aEntry.getValue().getBytecodeClass().getAccessFlags().isInterface()) {

                theWriter.println("    staticFields : {");

                theWriter.println("        name : '" + aEntry.getValue().getClassName().name() + "',");
                if (aEntry.getValue().hasClassInitializer()) {
                    theWriter.println("        classInitialized : false,");
                }
                aEntry.getValue().forEachStaticField(
                        aFieldEntry -> theWriter.println("        " + aFieldEntry.getKey() + " : null, // declared in " + aFieldEntry.getValue().getDeclaringType().name() ));
                theWriter.println("    },");
                theWriter.println();

                theWriter.println("    runtimeClass : {");
                theWriter.println("        jsType: function() {return " + theJSClassName + ";},");
                theWriter.println("        clazz: {");
                theWriter.println("            resolveVirtualMethod: function(aIdentifier) {");
                theWriter.println("                switch(aIdentifier) {");

                theClassLinkedCass.forEachVirtualMethod(
                        aClassMethod -> {
                            theWriter.println("                    case " + aClassMethod.getKey().getIdentifier() + ": // " + aClassMethod.getValue().getTargetMethod().getName().stringValue());
                            if ("getClass".equals(aClassMethod.getValue().getTargetMethod().getName().stringValue())) {
                                theWriter.println(
                                        "                        return " + JSWriterUtils.toClassName(theClassLinkedCass.getClassName()));
                            } else if ("toString".equals(aClassMethod.getValue().getTargetMethod().getName().stringValue())) {
                                    theWriter.println("                        throw 'Not implemented';");
                            } else if ("equals".equals(aClassMethod.getValue().getTargetMethod().getName().stringValue())) {
                                theWriter.println("                        throw 'Not implemented';");
                            } else if ("hashCode".equals(aClassMethod.getValue().getTargetMethod().getName().stringValue())) {
                                theWriter.println("                        throw 'Not implemented';");
                            } else if ("desiredAssertionStatus".equals(aClassMethod.getValue().getTargetMethod().getName().stringValue())) {
                                theWriter.println("                        return function(callsite) {return false};");
                            } else if ("getEnumConstants".equals(aClassMethod.getValue().getTargetMethod().getName().stringValue())) {
                                theWriter.println("                        return function(callsite) {return callsite.jsType().staticFields.$VALUES;};");
                            } else {
                                theWriter.println("                        throw {type: 'not implemented virtual name'} // " + aClassMethod.getValue().getTargetMethod().getName().stringValue());
                            }
                        });

                theWriter.println("                    default:");
                theWriter.println("                        throw {type: 'unknown virtual name'}");
                theWriter.println("                }");
                theWriter.println("            }");
                theWriter.println("        }");
                theWriter.println("    },");
                theWriter.println();

                theWriter.println("    resolveVirtualMethod : function(aIdentifier) {");
                theWriter.println("        switch(aIdentifier) {");
                aEntry.getValue().forEachVirtualMethod(aVirtualMethod -> {
                    BytecodeLinkedClass.LinkedMethod theLinkTarget = aVirtualMethod.getValue();
                    theWriter.println("            case " + aVirtualMethod.getKey().getIdentifier() + ":");
                    if (theLinkTarget.getTargetMethod() != BytecodeLinkedClass.GET_CLASS_PLACEHOLDER) {
                        theWriter.println(
                                "                return " + JSWriterUtils.toClassName(theLinkTarget.getDeclaringType()) + "." + JSWriterUtils.toMethodName(
                                        theLinkTarget.getTargetMethod().getName().stringValue(),
                                        theLinkTarget.getTargetMethod().getSignature()) + ";");
                    } else {
                        theWriter.println(
                                "                return function(callsite) {return " + theJSClassName + ".runtimeClass;};");
                    }
                });
                theWriter.println("            default:");
                theWriter.println("                throw {type: 'unknown virtual name'}");
                theWriter.println("        }");
                theWriter.println("    },");
                theWriter.println();

                theWriter.println("    classInitCheck : function() {");
                if (aEntry.getValue().hasClassInitializer()) {
                    theWriter.println("        if (" + theJSClassName + ".staticFields.classInitialized == false) {");
                    theWriter.println("            " + theJSClassName + ".staticFields.classInitialized = true;");
                    theWriter.println("            " + theJSClassName + ".VOIDclinit();");
                    theWriter.println("        }");
                }
                theWriter.println("    },");
                theWriter.println();


                theWriter.println("    emptyInstance : function() {");
                if (aEntry.getValue().hasClassInitializer()) {
                    theWriter.println("        " + theJSClassName + ".classInitCheck();");
                }
                theWriter.println("        return {data: {");
                aEntry.getValue().forEachMemberField(aField -> theWriter.println("            " + aField.getKey() + " : null, // declared in " + aField.getValue().getDeclaringType().name()));
                theWriter.println("        }, clazz: " + JSWriterUtils.toClassName(aEntry.getKey())+ "};");
                theWriter.println("    },");
                theWriter.println();

                theWriter.println("    thisIdentifier : function() {");
                theWriter.println("        return " + aEntry.getValue().getUniqueId());
                theWriter.println("    },");
                theWriter.println();

                theWriter.println("    instanceOfType : function(aType) {");
                theWriter.println("        switch(aType) {");

                for (BytecodeLinkedClass theType : aEntry.getValue().getImplementingTypes()) {
                    theWriter.println("            case " + theType.getUniqueId() +":");
                    theWriter.println("                return 1;");
                }

                theWriter.println("            default:");
                theWriter.println("                return 0;");
                theWriter.println("        }");
                theWriter.println("    },");
                theWriter.println();
            }

            aEntry.getValue().forEachMethod(aMethod -> {

                // Do not generate code for abstract methods
                if (aMethod.getAccessFlags().isAbstract()) {
                    return;
                }

                BytecodeMethodSignature theCurrentMethodSignature = aMethod.getSignature();
                BytecodeTypeRef[] theMethodArguments = theCurrentMethodSignature.getArguments();
                StringBuffer theArguments = new StringBuffer();
                if (!aMethod.getAccessFlags().isStatic()) {
                    theArguments.append("thisRef");
                }
                for (int i=1;i<=theMethodArguments.length;i++) {
                    if (theArguments.length() > 0) {
                        theArguments.append(",");
                    }
                    theArguments.append("p" + i);
                }

                if (aMethod.getAccessFlags().isNative()) {
                    if (aEntry.getValue().getBytecodeClass().getAnnotations().getAnnotationByType(EmulatedByRuntime.class.getName()) != null) {
                        return;
                    }
                    BytecodeAnnotation theImportAnnotation = aMethod.getAnnotations().getAnnotationByType(Import.class.getName());
                    if (theImportAnnotation == null) {
                        throw new IllegalStateException("No @Import annotation found. Potential linker error!");
                    }

                    JSModule theModule = modules.resolveModule(theImportAnnotation.getElementValueByName("module").stringValue());
                    JSFunction theFunction = theModule.resolveFunction(theImportAnnotation.getElementValueByName("name").stringValue());
                    theWriter.println();
                    theWriter.println("    " + JSWriterUtils.toMethodName(aMethod.getName().stringValue(), theCurrentMethodSignature) + " : function(" + theArguments.toString() + ") {");
                    theWriter.println("         " + theFunction.generateCode(theCurrentMethodSignature));
                    theWriter.println("    },");
                    return;
                }

                BytecodeCodeAttributeInfo theCode = aMethod.getCode(aEntry.getValue().getBytecodeClass());

                theWriter.println();
                theWriter.println("    " + JSWriterUtils.toMethodName(aMethod.getName().stringValue(), theCurrentMethodSignature) + " : function(" + theArguments.toString() + ") {");

                //theWriter.println("        console.log('" + theJSClassName + "." + aMethod.getName().stringValue() + "');");

                theWriter.println("        var frame = {");

                Map<String, String> theLocalVariables = new TreeMap<>();
                int p = 1;
                if (!aMethod.getAccessFlags().isStatic()) {
                    theLocalVariables.put("local1", "thisRef");
                    p++;
                }

                for (int i=0;i<theMethodArguments.length;i++) {
                    BytecodeTypeRef theRef = theMethodArguments[i];
                    if (theRef == BytecodePrimitiveTypeRef.LONG || theRef == BytecodePrimitiveTypeRef.DOUBLE) {
                        theLocalVariables.put("local" + p, "p" + (i+1));
                        p++;
                        theLocalVariables.put("local" + p, "null");
                        p++;
                    } else {
                        theLocalVariables.put("local" + p, "p" + (i+1));
                        p++;
                    }
                }

                while(p<=theCode.getMaxLocals()) {
                    theLocalVariables.put("local" + p, "null");
                    p++;
                }

                for (Map.Entry<String, String> theEntry : theLocalVariables.entrySet()) {
                    theWriter.println("            " + theEntry.getKey() + " : "  + theEntry.getValue() + ",");
                }

                theWriter.println("        };");

                BytecodeProgram theProgram = theCode.getProgramm();
                BytecodeControlFlowGraph theFlowGraph = new BytecodeControlFlowGraph(theProgram);

                String theInset = "            ";
                theWriter.println("        // Brute force static references init");
                Set<BytecodeObjectTypeRef> theStaticReferences = new HashSet<>();
                for (BytecodeBasicBlock theBlock : theFlowGraph.getBlocks()) {
                    Block theSSABlock = theBlock.getSsaBlock();
                    theStaticReferences.addAll(theSSABlock.getStaticReferences());
                }
                for (BytecodeObjectTypeRef theRef : theStaticReferences) {
                    theWriter.print("        ");
                    theWriter.print(JSWriterUtils.toClassName(theRef));
                    theWriter.println(".classInitCheck();");
                }

                theWriter.println("        // Begin name code");
                theWriter.println("        // # basic blocks in flow graph : " + theFlowGraph.getBlocks().size());
                theWriter.println("        var currentLabel = " + theFlowGraph.getBlocks().get(0).getStartAddress().getAddress() + ";");
                theWriter.println("        controlflowloop: while(true) switch(currentLabel) {");

                for (BytecodeBasicBlock theBlock : theFlowGraph.getBlocks()) {

                    Block theSSABlock = theBlock.getSsaBlock();

                    theWriter.println("         case " + theBlock.getStartAddress().getAddress() + ": {");

                    JSSSAWriter theJSWriter = new JSSSAWriter("             ", theWriter, aLinkerContext);

                    for (Expression theExpression : theSSABlock.getExpressions()) {
                        if (theExpression instanceof ReturnExpression) {
                            ReturnExpression theE = (ReturnExpression) theExpression;
                            theJSWriter.print("return");
                            theJSWriter.println(";");
                        } else if (theExpression instanceof InitVariableExpression) {
                            InitVariableExpression theE = (InitVariableExpression) theExpression;
                            Variable theVariable = theE.getVariable();
                            theJSWriter.print("var ");
                            theJSWriter.printVariableName(theVariable);
                            theJSWriter.print(" = ");
                            theJSWriter.print(theVariable.getValue());
                            theJSWriter.println(";");
                        } else if (theExpression instanceof PutStaticExpression) {
                            PutStaticExpression theE = (PutStaticExpression) theExpression;
                            BytecodeFieldRefConstant theField = theE.getField();
                            Variable theVariable = theE.getVariable();
                            theJSWriter.printStaticFieldReference(theField);
                            theJSWriter.print(" = ");
                            theJSWriter.printVariableName(theVariable);
                            theJSWriter.println(";");
                        } else if (theExpression instanceof ReturnVariableExpression) {
                            ReturnVariableExpression theE = (ReturnVariableExpression) theExpression;
                            Variable theVariable = theE.getVariable();
                            theJSWriter.print("return ");
                            theJSWriter.printVariableName(theVariable);
                            theJSWriter.println(";");
                        } else if (theExpression instanceof ThrowExpression) {
                            ThrowExpression theE = (ThrowExpression) theExpression;
                            Variable theVariable = theE.getVariable();
                            theJSWriter.print("throw ");
                            theJSWriter.printVariableName(theVariable);
                            theJSWriter.println(";");
                        } else if (theExpression instanceof InvokeVirtualMethodExpression) {
                            InvokeVirtualMethodExpression theE = (InvokeVirtualMethodExpression) theExpression;
                            theJSWriter.print(theE.getValue());
                            theJSWriter.println(";");
                        } else if (theExpression instanceof DirectInvokeMethodExpression) {
                            DirectInvokeMethodExpression theE = (DirectInvokeMethodExpression) theExpression;
                            theJSWriter.print(theE.getValue());
                            theJSWriter.println(";");
                        } else if (theExpression instanceof InvokeStaticMethodExpression) {
                            InvokeStaticMethodExpression theE = (InvokeStaticMethodExpression) theExpression;
                            theJSWriter.print(theE.getValue());
                            theJSWriter.println(";");
                        } else if (theExpression instanceof PutFieldExpression) {
                            PutFieldExpression theE = (PutFieldExpression) theExpression;
                            Variable theTarget = theE.getTarget();
                            BytecodeFieldRefConstant theField = theE.getField();
                            Variable thevalue = theE.getValue();
                            theJSWriter.printVariableName(theTarget);
                            theJSWriter.printInstanceFieldReference(theField);
                            theJSWriter.print(" = ");
                            theJSWriter.printVariableName(thevalue);
                            theJSWriter.println(";");
                        } else if (theExpression instanceof IFExpression) {
                            IFExpression theE = (IFExpression) theExpression;
                            Variable theBooleanExpression = theE.getBooleanExpression();
                            theJSWriter.print("if (");
                            theJSWriter.printVariableName(theBooleanExpression);
                            theJSWriter.println(") {");
                            theJSWriter.print(" ");
                            theJSWriter.println(generateJumpCodeFor(theE.getJumpTarget()));
                            theJSWriter.println("}");
                        } else if (theExpression instanceof GotoExpression) {
                            GotoExpression theE = (GotoExpression) theExpression;
                            theJSWriter.println(generateJumpCodeFor(theE.getJumpTarget()));
                        } else if (theExpression instanceof ArrayStoreExpression) {
                            ArrayStoreExpression theE = (ArrayStoreExpression) theExpression;
                            Variable theArray = theE.getArray();
                            Variable theIndex = theE.getIndex();
                            Variable theValue = theE.getValue();
                            theJSWriter.printVariableName(theArray);
                            theJSWriter.printArrayIndexReference(theIndex);
                            theJSWriter.print(" = ");
                            theJSWriter.printVariableName(theValue);
                            theJSWriter.println(";");
                        } else if (theExpression instanceof CheckCastExpression) {
                            CheckCastExpression theE = (CheckCastExpression) theExpression;
                            // Completely ignored
                        } else if (theExpression instanceof TableSwitchExpression) {
                            TableSwitchExpression theE = (TableSwitchExpression) theExpression;
                            Variable theVariable = theE.getVariable();
                            BytecodeInstructionTABLESWITCH theIns = theE.getInstruction();

                            theJSWriter.print("if (");
                            theJSWriter.printVariableName(theVariable);
                            theJSWriter.print(" < ");
                            theJSWriter.print(theIns.getLowValue());
                            theJSWriter.print(" || ");
                            theJSWriter.printVariableName(theVariable);
                            theJSWriter.print(" > ");
                            theJSWriter.print(theIns.getHighValue());
                            theJSWriter.println(") {");
                            theJSWriter.println(generateJumpCodeFor(theIns.getDefaultJumpTarget()));
                            theJSWriter.println("}");
                            theJSWriter.print("switch(");
                            theJSWriter.printVariableName(theVariable);
                            theJSWriter.print(" - ");
                            theJSWriter.print(theIns.getLowValue());
                            theJSWriter.println(") {");

                            long[] theOffsets = theIns.getOffsets();
                            for (int i=0;i<theOffsets.length;i++) {
                                theJSWriter.print(" case ");
                                theJSWriter.print(i);
                                theJSWriter.print("     ");
                                theJSWriter.println(generateJumpCodeFor(theIns.getOpcodeAddress().add((int) theOffsets[i])));
                            }

                            theJSWriter.println("}");
                            theJSWriter.println("throw 'Illegal jump target!';");
                        } else if (theExpression instanceof LookupSwitchExpression) {
                            LookupSwitchExpression theE = (LookupSwitchExpression) theExpression;
                            BytecodeInstructionLOOKUPSWITCH theIns = theE.getInstruction();
                            theJSWriter.print("switch(");
                            theJSWriter.printVariableName(theE.getVariable());
                            theJSWriter.println(") {");

                            for (BytecodeInstructionLOOKUPSWITCH.Pair thePair : theIns.getPairs()) {
                                theJSWriter.print(" case ");
                                theJSWriter.print(thePair.getMatch());
                                theJSWriter.println(":");

                                theJSWriter.println("       " + generateJumpCodeFor(theIns.getOpcodeAddress().add((int) thePair.getOffset())));
                            }

                            theJSWriter.println("}");
                            theJSWriter.println(generateJumpCodeFor(theIns.getDefaultJumpTarget()));
                        } else {
                            throw new IllegalStateException("Not implemented : " + theExpression);
                        }
                    }

                    theWriter.println("         }");
                }
                theWriter.println("        }");
                theWriter.println("    },");
            });

            theWriter.println("}");
            theWriter.println();
        });

        theWriter.flush();

        System.out.println("Total number of virtual method calls : " + theNumberOfVirtualCalls.get());
        System.out.println("Remaining virtual method calls " + theNumberOfRealVirtualCalls.get());

        return theStrWriter.toString();
    }

    private void writeExceptionHandlerCode(BytecodeLinkerContext aLinkerContext, BytecodeLinkedClass aExceptionRethrower,
            PrintWriter aWriter, BytecodeProgram aProgram,
            String aInset, BytecodeInstruction aInstruction, String aExceptionVariableName) {
        BytecodeExceptionTableEntry[] theActiveHandlers = aProgram.getActiveExceptionHandlers(aInstruction.getOpcodeAddress(), aProgram.getExceptionHandlers());
        if (theActiveHandlers.length == 0) {
            // Missing catch block
            aWriter.println(aInset + JSWriterUtils.toClassName(aExceptionRethrower.getClassName()) + "." + JSWriterUtils.toMethodName("registerExceptionOutcome", theRegisterExceptionOutcomeSignature) + "(" + aExceptionVariableName + ");");
            aWriter.println(aInset + "return;");
        } else {
            for (BytecodeExceptionTableEntry theEntry : theActiveHandlers) {
                if (!theEntry.isFinally()) {
                    BytecodeClassinfoConstant theConstant = theEntry.getCatchType();
                    BytecodeLinkedClass theLinkedClass = aLinkerContext.isLinkedOrNull(theConstant.getConstant());
                    if (theLinkedClass != null) {
                        aWriter.println(
                                aInset + "if (" + aExceptionVariableName + ".clazz.instanceOfType(" + theLinkedClass.getUniqueId()
                                        + ")) {");
                        aWriter.println(aInset + "    currentLabel = " + theEntry.getHandlerPc().getAddress() + ";");
                        aWriter.println(aInset + "    continue controlflowloop;");
                        aWriter.println(aInset + "}");
                    }
                }
            }
            aWriter.println(aInset + JSWriterUtils.toClassName(aExceptionRethrower.getClassName()) + "." + JSWriterUtils.toMethodName("registerExceptionOutcome", theRegisterExceptionOutcomeSignature) + "(" + aExceptionVariableName + ");");
            aWriter.println(aInset + "return;");
        }
    }
}