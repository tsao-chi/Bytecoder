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
package de.mirkosertic.bytecoder.backend;

import de.mirkosertic.bytecoder.api.ClassLibProvider;
import de.mirkosertic.bytecoder.backend.cpp.CPPCompilerBackend;
import de.mirkosertic.bytecoder.backend.js.JSSSACompilerBackend;
import de.mirkosertic.bytecoder.backend.llvm.LLVMCompilerBackend;
import de.mirkosertic.bytecoder.backend.wasm.WASMSSAASTCompilerBackend;
import de.mirkosertic.bytecoder.classlib.VM;
import de.mirkosertic.bytecoder.core.AnalysisStack;
import de.mirkosertic.bytecoder.core.BytecodeArrayTypeRef;
import de.mirkosertic.bytecoder.core.BytecodeClass;
import de.mirkosertic.bytecoder.core.BytecodeField;
import de.mirkosertic.bytecoder.core.BytecodeLinkedClass;
import de.mirkosertic.bytecoder.core.BytecodeLinkerContext;
import de.mirkosertic.bytecoder.core.BytecodeLoader;
import de.mirkosertic.bytecoder.core.BytecodeMethod;
import de.mirkosertic.bytecoder.core.BytecodeMethodSignature;
import de.mirkosertic.bytecoder.core.BytecodeObjectTypeRef;
import de.mirkosertic.bytecoder.core.BytecodePrimitiveTypeRef;
import de.mirkosertic.bytecoder.core.BytecodeTypeRef;
import de.mirkosertic.bytecoder.core.BytecodeUtf8Constant;
import de.mirkosertic.bytecoder.core.ReflectionConfiguration;
import de.mirkosertic.bytecoder.ssa.NaiveProgramGenerator;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class CompileTarget {

    public enum BackendType {
        js {
            @Override
            public CompileBackend createBackend() {
                return new JSSSACompilerBackend(NaiveProgramGenerator.FACTORY);
            }
        },
        wasm {
            @Override
            public CompileBackend createBackend() {
                return new WASMSSAASTCompilerBackend(NaiveProgramGenerator.FACTORY);
            }
        },
        wasm_llvm {
            @Override
            public CompileBackend createBackend() {
                return new LLVMCompilerBackend(NaiveProgramGenerator.FACTORY);
            }
        },
        cpp {
            @Override
            public CompileBackend createBackend() {
                return new CPPCompilerBackend(NaiveProgramGenerator.FACTORY);
            }
        };


        public abstract CompileBackend createBackend();
    }

    private final CompileBackend backend;
    private final BytecodeLoader bytecodeLoader;
    private final ClassLoader classLoader;

    public CompileTarget(final ClassLoader aClassLoader, final BackendType aType) {
        backend = aType.createBackend();
        bytecodeLoader = new BytecodeLoader(aClassLoader);
        classLoader = aClassLoader;

    }

    public CompileResult compile(
            final CompileOptions aOptions,
            final Class aClass,
            final String aMethodName,
            final BytecodeMethodSignature aSignature) {

        final BytecodeLinkerContext theLinkerContext = backend.newLinkerContext(bytecodeLoader, aOptions.getLogger());

        // We try to load all available reflection configuration
        // from the classpath
        try {
            final Enumeration<URL> reflectionConfigs = classLoader.getResources("bytecoder-reflection.json");
            while(reflectionConfigs.hasMoreElements()) {
                final URL url = reflectionConfigs.nextElement();
                aOptions.getLogger().info("Loading reflection configuration : {}", url);
                theLinkerContext.reflectionConfiguration().mergeWithConfigFrom(url, aOptions.getLogger());
            }
        } catch (final IOException e) {
            aOptions.getLogger().warn("Failed to load reflection configuration files : {}", e.getMessage());
        }

        final AnalysisStack analysisStack = new AnalysisStack();
        theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromRuntimeClass(SecurityManager.class), analysisStack)
                .tagWith(BytecodeLinkedClass.Tag.INSTANTIATED);

        final BytecodeLinkedClass theClassLinkedCass = theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromRuntimeClass(Class.class), analysisStack)
            .tagWith(BytecodeLinkedClass.Tag.INSTANTIATED);
        theClassLinkedCass.resolveConstructorInvocation(new BytecodeMethodSignature(
                BytecodePrimitiveTypeRef.VOID, new BytecodeTypeRef[] {}), analysisStack);

        // Lambda handling
        final BytecodeLinkedClass theCallsite = theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromRuntimeClass(CallSite.class), analysisStack);
        theCallsite.resolveVirtualMethod("invokeExact", new BytecodeMethodSignature(BytecodeObjectTypeRef.fromRuntimeClass(Object.class),
                new BytecodeTypeRef[] {new BytecodeArrayTypeRef(BytecodeObjectTypeRef.fromRuntimeClass(Object.class), 1)}), analysisStack);

        theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromRuntimeClass(VM.LambdaStaticImplCallsite.class), analysisStack)
            .tagWith(BytecodeLinkedClass.Tag.INSTANTIATED)
            .resolveConstructorInvocation(new BytecodeMethodSignature(
                        BytecodePrimitiveTypeRef.VOID,
                        new BytecodeTypeRef[]{
                                BytecodeObjectTypeRef.fromRuntimeClass(String.class),
                                BytecodeObjectTypeRef.fromRuntimeClass(MethodType.class),
                                BytecodeObjectTypeRef.fromRuntimeClass(MethodHandle.class)
                        }
                ), analysisStack);
        theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromRuntimeClass(VM.LambdaConstructorRefCallsite.class), analysisStack)
                .tagWith(BytecodeLinkedClass.Tag.INSTANTIATED)
                .resolveConstructorInvocation(new BytecodeMethodSignature(
                        BytecodePrimitiveTypeRef.VOID,
                        new BytecodeTypeRef[]{
                                BytecodeObjectTypeRef.fromRuntimeClass(MethodType.class),
                                BytecodeObjectTypeRef.fromRuntimeClass(MethodHandle.class),
                        }
                ), analysisStack);
        theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromRuntimeClass(VM.InvokeInterfaceCallsite.class), analysisStack)
                .tagWith(BytecodeLinkedClass.Tag.INSTANTIATED)
                .resolveConstructorInvocation(new BytecodeMethodSignature(
                        BytecodePrimitiveTypeRef.VOID,
                        new BytecodeTypeRef[]{
                                BytecodeObjectTypeRef.fromRuntimeClass(MethodType.class),
                                BytecodeObjectTypeRef.fromRuntimeClass(MethodHandle.class),
                        }
                ), analysisStack);
        theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromRuntimeClass(VM.InvokeVirtualCallsite.class), analysisStack)
                .tagWith(BytecodeLinkedClass.Tag.INSTANTIATED)
                .resolveConstructorInvocation(new BytecodeMethodSignature(
                        BytecodePrimitiveTypeRef.VOID,
                        new BytecodeTypeRef[]{
                                BytecodeObjectTypeRef.fromRuntimeClass(MethodType.class),
                                BytecodeObjectTypeRef.fromRuntimeClass(MethodHandle.class),
                        }
                ), analysisStack);
        theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromRuntimeClass(VM.InvokeSpecialCallsite.class), analysisStack)
                .tagWith(BytecodeLinkedClass.Tag.INSTANTIATED)
                .resolveConstructorInvocation(new BytecodeMethodSignature(
                        BytecodePrimitiveTypeRef.VOID,
                        new BytecodeTypeRef[]{
                                BytecodeObjectTypeRef.fromRuntimeClass(MethodType.class),
                                BytecodeObjectTypeRef.fromRuntimeClass(MethodHandle.class),
                        }
                ), analysisStack);

        // We have to link character set implementations
        // to make them available via reflection API
        final BytecodeLinkedClass utf8 = theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromUtf8Constant(new BytecodeUtf8Constant("sun/nio/cs/UTF_8")), analysisStack);
        utf8.tagWith(BytecodeLinkedClass.Tag.INSTANTIATED);
        utf8.reflectiveClass().setSupportsClassForName(true);
        utf8.resolveConstructorInvocation(new BytecodeMethodSignature(BytecodePrimitiveTypeRef.VOID, new BytecodeTypeRef[0]), analysisStack);

        final BytecodeLinkedClass utf16 = theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromUtf8Constant(new BytecodeUtf8Constant("sun/nio/cs/UTF_16")), analysisStack);
        utf16.tagWith(BytecodeLinkedClass.Tag.INSTANTIATED);
        utf16.reflectiveClass().setSupportsClassForName(true);
        utf16.resolveConstructorInvocation(new BytecodeMethodSignature(BytecodePrimitiveTypeRef.VOID, new BytecodeTypeRef[0]), analysisStack);

        final BytecodeLinkedClass iso88591 = theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromUtf8Constant(new BytecodeUtf8Constant("sun/nio/cs/ISO_8859_1")), analysisStack);
        iso88591.tagWith(BytecodeLinkedClass.Tag.INSTANTIATED);
        iso88591.reflectiveClass().setSupportsClassForName(true);
        iso88591.resolveConstructorInvocation(new BytecodeMethodSignature(BytecodePrimitiveTypeRef.VOID, new BytecodeTypeRef[0]), analysisStack);

        final BytecodeLinkedClass usAscii = theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromUtf8Constant(new BytecodeUtf8Constant("sun/nio/cs/US_ASCII")), analysisStack);
        usAscii.tagWith(BytecodeLinkedClass.Tag.INSTANTIATED);
        usAscii.reflectiveClass().setSupportsClassForName(true);
        usAscii.resolveConstructorInvocation(new BytecodeMethodSignature(BytecodePrimitiveTypeRef.VOID, new BytecodeTypeRef[0]), analysisStack);

        final BytecodeLinkedClass characterDataLatin1 = theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromUtf8Constant(new BytecodeUtf8Constant("java/lang/CharacterDataLatin1")), analysisStack);
        characterDataLatin1.tagWith(BytecodeLinkedClass.Tag.INSTANTIATED);
        characterDataLatin1.reflectiveClass().setSupportsClassForName(true);
        characterDataLatin1.resolveConstructorInvocation(new BytecodeMethodSignature(BytecodePrimitiveTypeRef.VOID, new BytecodeTypeRef[0]), analysisStack);

        // Helper methods for Boxing/Unboxing
        final BytecodeLinkedClass theByteClass = theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromRuntimeClass(Byte.class), analysisStack);
        final BytecodeMethodSignature theByteClassValueOfSignature = new BytecodeMethodSignature(BytecodeObjectTypeRef.fromRuntimeClass(Byte.class),
                new BytecodeTypeRef[]{BytecodePrimitiveTypeRef.BYTE});
        theByteClass.resolveStaticMethod("valueOf", theByteClassValueOfSignature, analysisStack);
        theByteClass.resolveVirtualMethod("byteValue", new BytecodeMethodSignature(BytecodePrimitiveTypeRef.BYTE, new BytecodeTypeRef[0]), analysisStack);

        final BytecodeLinkedClass theIntegerClass = theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromRuntimeClass(Integer.class), analysisStack);
        final BytecodeMethodSignature theIntegerClassValueOfSignature = new BytecodeMethodSignature(BytecodeObjectTypeRef.fromRuntimeClass(Integer.class),
                new BytecodeTypeRef[]{BytecodePrimitiveTypeRef.INT});
        theIntegerClass.resolveStaticMethod("valueOf", theIntegerClassValueOfSignature, analysisStack);
        theIntegerClass.resolveVirtualMethod("intValue", new BytecodeMethodSignature(BytecodePrimitiveTypeRef.INT, new BytecodeTypeRef[0]), analysisStack);

        final BytecodeLinkedClass theCharacterClass = theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromRuntimeClass(Character.class), analysisStack);
        final BytecodeMethodSignature theCharacterClassValueOfSignature = new BytecodeMethodSignature(BytecodeObjectTypeRef.fromRuntimeClass(Character.class),
                new BytecodeTypeRef[]{BytecodePrimitiveTypeRef.CHAR});
        theCharacterClass.resolveStaticMethod("valueOf", theCharacterClassValueOfSignature, analysisStack);
        theCharacterClass.resolveVirtualMethod("charValue", new BytecodeMethodSignature(BytecodePrimitiveTypeRef.CHAR, new BytecodeTypeRef[0]), analysisStack);

        final BytecodeLinkedClass theBooleanClass = theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromRuntimeClass(Boolean.class), analysisStack);
        final BytecodeMethodSignature theBooleanClassValueOfSignature = new BytecodeMethodSignature(BytecodeObjectTypeRef.fromRuntimeClass(Boolean.class),
                new BytecodeTypeRef[]{BytecodePrimitiveTypeRef.BOOLEAN});
        theBooleanClass.resolveStaticMethod("valueOf", theBooleanClassValueOfSignature, analysisStack);
        theBooleanClass.resolveVirtualMethod("booleanValue", new BytecodeMethodSignature(BytecodePrimitiveTypeRef.BOOLEAN, new BytecodeTypeRef[0]), analysisStack);

        final BytecodeLinkedClass theFloatClass = theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromRuntimeClass(Float.class), analysisStack);
        final BytecodeMethodSignature theFloatClassValueOfSignature = new BytecodeMethodSignature(BytecodeObjectTypeRef.fromRuntimeClass(Float.class),
                new BytecodeTypeRef[]{BytecodePrimitiveTypeRef.FLOAT});
        theFloatClass.resolveStaticMethod("valueOf", theFloatClassValueOfSignature, analysisStack);
        theFloatClass.resolveVirtualMethod("floatValue", new BytecodeMethodSignature(BytecodePrimitiveTypeRef.FLOAT, new BytecodeTypeRef[0]), analysisStack);

        final BytecodeLinkedClass theDoubleClass = theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromRuntimeClass(Double.class), analysisStack);
        final BytecodeMethodSignature theDoubleClassValueOfSignature = new BytecodeMethodSignature(BytecodeObjectTypeRef.fromRuntimeClass(Double.class),
                new BytecodeTypeRef[]{BytecodePrimitiveTypeRef.DOUBLE});
        theDoubleClass.resolveStaticMethod("valueOf", theDoubleClassValueOfSignature, analysisStack);
        theDoubleClass.resolveVirtualMethod("doubleValue", new BytecodeMethodSignature(BytecodePrimitiveTypeRef.DOUBLE, new BytecodeTypeRef[0]), analysisStack);

        final BytecodeLinkedClass theLongClass = theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromRuntimeClass(Long.class), analysisStack);
        final BytecodeMethodSignature theLongClassValueOfSignature = new BytecodeMethodSignature(BytecodeObjectTypeRef.fromRuntimeClass(Long.class),
                new BytecodeTypeRef[]{BytecodePrimitiveTypeRef.LONG});
        theLongClass.resolveStaticMethod("valueOf", theLongClassValueOfSignature, analysisStack);
        theLongClass.resolveVirtualMethod("longValue", new BytecodeMethodSignature(BytecodePrimitiveTypeRef.LONG, new BytecodeTypeRef[0]), analysisStack);

        final BytecodeLinkedClass theShortClass = theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromRuntimeClass(Short.class), analysisStack);
        final BytecodeMethodSignature theShortClassValueOfSignature = new BytecodeMethodSignature(BytecodeObjectTypeRef.fromRuntimeClass(Short.class),
                new BytecodeTypeRef[]{BytecodePrimitiveTypeRef.SHORT});
        theShortClass.resolveStaticMethod("valueOf", theShortClassValueOfSignature, analysisStack);
        theShortClass.resolveVirtualMethod("shortValue", new BytecodeMethodSignature(BytecodePrimitiveTypeRef.SHORT, new BytecodeTypeRef[0]), analysisStack);

        final BytecodeLinkedClass theStringClass = theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromRuntimeClass(String.class), analysisStack);
        theStringClass.resolveStaticMethod("decodeASCII",
                new BytecodeMethodSignature(BytecodePrimitiveTypeRef.INT,
                        new BytecodeTypeRef[] {
                                new BytecodeArrayTypeRef(BytecodePrimitiveTypeRef.BYTE, 1),
                                BytecodePrimitiveTypeRef.INT,
                                new BytecodeArrayTypeRef(BytecodePrimitiveTypeRef.CHAR, 1),
                                BytecodePrimitiveTypeRef.INT,
                                BytecodePrimitiveTypeRef.INT,
                        }), analysisStack);

        // Additional classes
        if (aOptions.getAdditionalClassesToLink() != null) {
            for (final String theClassname : aOptions.getAdditionalClassesToLink()) {
                final BytecodeLinkedClass theClass = theLinkerContext.resolveClass(new BytecodeObjectTypeRef(theClassname), analysisStack);
                theClass.tagWith(BytecodeLinkedClass.Tag.INSTANTIATED);
                theClass.reflectiveClass().setSupportsClassForName(true);
                theClass.resolveConstructorInvocation(new BytecodeMethodSignature(BytecodePrimitiveTypeRef.VOID, new BytecodeTypeRef[0]), analysisStack);

                for (final BytecodeField field : theClass.getBytecodeClass().fields()) {
                    if (field.getAccessFlags().isStatic()) {
                        theClass.resolveStaticField(field.getName(), analysisStack);
                    } else {
                        theClass.resolveInstanceField(field.getName(), analysisStack);
                    }
                }
            }
        }

        // Reflective classes are also fully linked, including all fields
        for (final ReflectionConfiguration.ReflectiveClass reflectiveClass : theLinkerContext.reflectionConfiguration().configuredClasses()) {
            final BytecodeLinkedClass theLinkedClass = theLinkerContext.resolveClass(new BytecodeObjectTypeRef(reflectiveClass.getName()), analysisStack);
            theLinkedClass.tagWith(BytecodeLinkedClass.Tag.INSTANTIATED);
            for (final BytecodeField field : theLinkedClass.getBytecodeClass().fields()) {
                if (field.getAccessFlags().isStatic()) {
                    theLinkedClass.resolveStaticField(field.getName(), analysisStack);
                } else {
                    theLinkedClass.resolveInstanceField(field.getName(), analysisStack);
                }
            }
        }

        final BytecodeObjectTypeRef theTypeRef = BytecodeObjectTypeRef.fromRuntimeClass(aClass);

        theLinkerContext.resolveClass(BytecodeObjectTypeRef.fromRuntimeClass(FileDescriptor.class), analysisStack)
                .tagWith(BytecodeLinkedClass.Tag.INSTANTIATED)
                .resolveStaticMethod("initDefaultFileHandles", new BytecodeMethodSignature(BytecodePrimitiveTypeRef.VOID, new BytecodeTypeRef[] {BytecodePrimitiveTypeRef.INT, BytecodePrimitiveTypeRef.INT, BytecodePrimitiveTypeRef.INT}), analysisStack);

        final BytecodeLinkedClass theClass = theLinkerContext.resolveClass(theTypeRef, analysisStack);
        theClass.tagWith(BytecodeLinkedClass.Tag.INSTANTIATED);
        final BytecodeMethod theMethod = theClass.getBytecodeClass().methodByNameAndSignatureOrNull(aMethodName, aSignature);
        if (theMethod == null) {
            throw new IllegalStateException("No method named " + aMethodName + " with signature " + aSignature + "found in " + theClass.getClassName().name());
        }
        if (theMethod.getAccessFlags().isStatic()) {
            theClass.resolveStaticMethod(aMethodName, aSignature, analysisStack);
        } else {
            theClass.resolveVirtualMethod(aMethodName, aSignature, analysisStack);
        }

        // Before code generation we have to make sure that all abstract method implementations are linked correctly
        aOptions.getLogger().info("Resolving abstract method hierarchy");
        theLinkerContext.resolveAbstractMethodsInSubclasses(analysisStack);

        // Ugly hack for deeply nested promises and anonymous inner classes
        boolean somethingAdded = true;
        final Set<BytecodeLinkedClass> theAlreadySeen = new HashSet<>();
        while (somethingAdded) {
            somethingAdded = false;
            // We have to link all callback implementations. They are not part of the dependency yet as
            // they are not invoked by the bytecode, but from the outside world. By adding them to the
            // dependency tree, we make sure they are available for invocation.
            final List<BytecodeLinkedClass> theLinkedClasses = theLinkerContext.linkedClasses().collect(Collectors.toList());
            for (final BytecodeLinkedClass theLinkedClass : theLinkedClasses) {
                if (theLinkedClass.isCallback()) {
                    if (theAlreadySeen.add(theLinkedClass)) {
                        somethingAdded = true;
                        final BytecodeClass theBytecodeClass = theLinkedClass.getBytecodeClass();
                        aOptions.getLogger()
                                .info("Resolving callback {}", theBytecodeClass.getThisInfo().getConstant().stringValue());
                        for (final BytecodeMethod theCallbackMethod : theBytecodeClass.getMethods()) {
                            if (!theCallbackMethod.isConstructor() && !theCallbackMethod.isClassInitializer()) {
                                aOptions.getLogger()
                                        .info("Resolving callback method {} {}", theCallbackMethod.getName().stringValue(),
                                                theCallbackMethod.getSignature().toString());
                                theLinkedClass.resolveVirtualMethod(theCallbackMethod.getName().stringValue(),
                                        theCallbackMethod.getSignature(), analysisStack);
                            }
                        }
                    }
                }
            }

            aOptions.getLogger().info("Resolving abstract method hierarchy");
            theLinkerContext.resolveAbstractMethodsInSubclasses(analysisStack);
        }

        final CompileResult theResult = backend.generateCodeFor(aOptions, theLinkerContext, aClass, aMethodName, aSignature, analysisStack);

        // Write some statistics
        theLinkerContext.getStatistics().writeTo(aOptions.getLogger());

        // Include all required resources from included modules
        final List<String> resourcesToInclude = new ArrayList<>();
        for (final ClassLibProvider provider : ClassLibProvider.availableProviders()) {
            Collections.addAll(resourcesToInclude, provider.additionalResources());
        }
        // Don't forget user specific ressources
        Collections.addAll(resourcesToInclude, aOptions.getAdditionalResources());

        // Finally, we add the list of additional resources to the result
        for (final String theResource : resourcesToInclude) {
            final URL theUrl = classLoader.getResource(theResource);
            if (theUrl != null) {
                aOptions.getLogger().info("Including resource {}", theResource);
                theResult.add(new CompileResult.URLContent(theResource, theUrl));
            } else {
                aOptions.getLogger().warn("Cannot find resource {}", theResource);
            }
        }

        return theResult;
    }

    public BytecodeMethodSignature toMethodSignature(final Method aMethod) {
        return bytecodeLoader.getSignatureParser().toMethodSignature(aMethod);
    }
}