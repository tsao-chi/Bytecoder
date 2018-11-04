/*
 * Copyright 2018 Mirko Sertic
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
package de.mirkosertic.bytecoder.backend.wasm.ast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Module {

    private final TypesSection types;
    private final FunctionsSection functions;
    private final TablesSection tables;
    private final MemorySection mems;
    private final GlobalsSection globals;
    private final ElementSection elements;
    private final ImportsSection imports;
    private final ExportsSection exports;
    private final NameSection names;
    private final EventsSection events;
    public Module() {
        types = new TypesSection(this);
        exports = new ExportsSection(this);
        tables = new TablesSection(this);
        globals = new GlobalsSection(this);
        functions = new FunctionsSection(this);
        mems = new MemorySection(this);
        elements = new ElementSection(this);
        final DataSection data = new DataSection(this);
        final StartSection start = new StartSection(this);
        imports = new ImportsSection(this);
        names = new NameSection(this);
        events = new EventsSection(this);
    }

    public void writeTo(final TextWriter writer) throws IOException {
        writer.opening();
        writer.write("module");
        writer.space();
        writer.newLine();

        types.writeTo(writer);
        imports.writeTo(writer);
        mems.writeTo(writer);
        globals.writeTo(writer);
        tables.writeTo(writer);
        elements.writeTo(writer);
        functions.writeTo(writer);
        exports.writeTo(writer);
        writer.closing();
    }

    public TypesSection getTypes() {
        return types;
    }

    public GlobalsIndex globalsIndex() {
        return globals.globalsIndex();
    }

    public FunctionIndex functionIndex() {
        final FunctionIndex functionIndex = new FunctionIndex();
        imports.addFunctionsToIndex(functionIndex);
        functions.addFunctionsToIndex(functionIndex);
        return functionIndex;
    }

    public void writeTo(final BinaryWriter writer) throws IOException {

        final FunctionIndex functionIndex = functionIndex();

        final List<Memory> memoryIndex = new ArrayList<>();
        mems.addMemoriesToIndex(memoryIndex);

        writer.header();
        types.writeTo(writer);
        imports.writeTo(writer, memoryIndex);
        functions.writeTo(writer, functionIndex);
        tables.writeTo(writer);
        mems.writeTo(writer);
        globals.writeTo(writer);
        exports.writeTo(writer, functionIndex, memoryIndex);
        elements.writeTo(writer, functionIndex);
        functions.writeCodeTo(writer, functionIndex);
        names.writeCodeTo(writer);
        events.writeCodeTo(writer);
    }

    public MemorySection getMems() {
        return mems;
    }

    public FunctionsSection getFunctions() {
        return functions;
    }

    public ImportsSection getImports() {
        return imports;
    }

    public GlobalsSection getGlobals() {
        return globals;
    }

    public ExportsSection getExports() {
        return exports;
    }

    public TablesSection getTables() {
        return tables;
    }
}