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
package de.mirkosertic.bytecoder.core;

public class BytecodeInstructionGETSTATIC extends BytecodeInstruction {

    private final int index;
    private final BytecodeConstantPool constantPool;

    public BytecodeInstructionGETSTATIC(final BytecodeOpcodeAddress aIndex, final int aConstantPoolIndex, final BytecodeConstantPool aConstantPool) {
        super(aIndex);
        index = aConstantPoolIndex;
        constantPool = aConstantPool;
    }

    public BytecodeFieldRefConstant getConstant() {
        return (BytecodeFieldRefConstant) constantPool.constantByIndex(index - 1);
    }

    public BytecodeObjectTypeRef referencedClass() {
        final BytecodeFieldRefConstant theConstant = getConstant();
        final BytecodeClassinfoConstant theClass = theConstant.getClassIndex().getClassConstant();
        return BytecodeObjectTypeRef.fromUtf8Constant(theClass.getConstant());
    }

    public BytecodeUtf8Constant referencedField() {
        final BytecodeFieldRefConstant theConstant = getConstant();
        final BytecodeNameIndex theName = theConstant.getNameAndTypeIndex().getNameAndType().getNameIndex();
        return theName.getName();
    }
}