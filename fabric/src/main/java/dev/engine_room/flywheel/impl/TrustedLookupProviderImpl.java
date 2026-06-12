/*
 * TrustedLookupAccessor
 * Copyright (c) 2025 Burning_TNT<pangyl08@163.com>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package dev.engine_room.flywheel.impl;

import dev.engine_room.flywheel.lib.internal.TrustedLookupProvider;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/**
 * Fabric {@link TrustedLookupProvider}, sole purpose is to survive -sun-misc-unsafe-memory-access=deny.
 */
public final class TrustedLookupProviderImpl implements TrustedLookupProvider {
    private static MethodHandles.Lookup lookup;

    static {
        try {
            SequenceLayout VL_JNIInvokeInterface = MemoryLayout.sequenceLayout(8L, ValueLayout.ADDRESS);
            AddressLayout VL_P_JNIInvokeInterface = ValueLayout.ADDRESS.withTargetLayout(VL_JNIInvokeInterface);
            AddressLayout VL_PP_JNIInvokeInterface = ValueLayout.ADDRESS.withTargetLayout(VL_P_JNIInvokeInterface);
            SequenceLayout VL_JNINativeInterface = MemoryLayout.sequenceLayout(233L, ValueLayout.ADDRESS);
            AddressLayout VL_P_JNINativeInterface = ValueLayout.ADDRESS.withTargetLayout(VL_JNINativeInterface);
            AddressLayout VL_PP_JNINativeInterface = ValueLayout.ADDRESS.withTargetLayout(VL_P_JNINativeInterface);
            Linker LINKER = Linker.nativeLinker();

            try (Arena ARENA = Arena.ofConfined()) {
                SymbolLookup JVM = SymbolLookup.libraryLookup(System.mapLibraryName("jvm"), ARENA);
                MethodHandle JNI_GetCreatedJavaVMs = LINKER.downcallHandle(JVM.find("JNI_GetCreatedJavaVMs").orElseThrow(() -> new IllegalStateException("JNI_GetCreatedJavaVMs must exist.")), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                MethodHandle JVM_LatestUserDefinedLoader = LINKER.downcallHandle(JVM.find("JVM_LatestUserDefinedLoader").orElseThrow(() -> new IllegalStateException("JVM_LatestUserDefinedLoader must exist.")), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                MethodHandle JVM_FindClassFromCaller = LINKER.downcallHandle(JVM.find("JVM_FindClassFromCaller").orElseThrow(() -> new IllegalStateException("JVM_FindClassFromCaller must exist.")), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

                MemorySegment pVM = ARENA.allocate(VL_PP_JNIInvokeInterface);
                MemorySegment nVMs = ARENA.allocate(ValueLayout.JAVA_INT);
                int ec = (int) JNI_GetCreatedJavaVMs.invokeExact(pVM, 1, nVMs);
                if (ec != 0) {
                    throw new IllegalStateException("JNI_GetCreatedJavaVMs returned error code " + ec);
                }
                if (nVMs.get(ValueLayout.JAVA_INT, 0L) != 1) {
                    throw new IllegalStateException("There must be one VM.");
                }

                MethodHandle GetEnv = LINKER.downcallHandle(pVM.get(VL_PP_JNIInvokeInterface, 0L).get(VL_P_JNIInvokeInterface, 0L).getAtIndex(ValueLayout.ADDRESS, 6L), FunctionDescriptor.of(ValueLayout.JAVA_INT, VL_PP_JNIInvokeInterface, VL_PP_JNINativeInterface, ValueLayout.JAVA_INT));
                MemorySegment ppEnv = ARENA.allocate(VL_PP_JNINativeInterface);
                ec = (int) GetEnv.invokeExact(pVM, ppEnv, 0x00010008);
                if (ec != 0) {
                    throw new IllegalStateException("GetEnv returned error code " + ec);
                }

                MemorySegment pEnv = ppEnv.get(VL_PP_JNINativeInterface, 0L);
                MemorySegment pJNINativeInterface = pEnv.get(VL_P_JNINativeInterface, 0L);
                MethodHandle FindClass = LINKER.downcallHandle(pJNINativeInterface.getAtIndex(ValueLayout.ADDRESS, 6L), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)).bindTo(pEnv);
                MethodHandle NewGlobalRef = LINKER.downcallHandle(pJNINativeInterface.getAtIndex(ValueLayout.ADDRESS, 21L), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)).bindTo(pEnv);
                MethodHandle DeleteGlobalRef = LINKER.downcallHandle(pJNINativeInterface.getAtIndex(ValueLayout.ADDRESS, 22L), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)).bindTo(pEnv);
                MethodHandle GetStaticFieldID = LINKER.downcallHandle(pJNINativeInterface.getAtIndex(ValueLayout.ADDRESS, 144L), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)).bindTo(pEnv);
                MethodHandle GetStaticObjectField = LINKER.downcallHandle(pJNINativeInterface.getAtIndex(ValueLayout.ADDRESS, 145L), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)).bindTo(pEnv);
                MethodHandle SetStaticObjectField = LINKER.downcallHandle(pJNINativeInterface.getAtIndex(ValueLayout.ADDRESS, 154L), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)).bindTo(pEnv);

                MemorySegment lookupJClass = (MemorySegment) FindClass.invokeExact(ARENA.allocateFrom("java/lang/invoke/MethodHandles$Lookup"));
                MemorySegment lookupJClassRef = (MemorySegment) NewGlobalRef.invokeExact(lookupJClass);
                MemorySegment lookupJFieldID = (MemorySegment) GetStaticFieldID.invokeExact(lookupJClassRef, ARENA.allocateFrom("IMPL_LOOKUP"), ARENA.allocateFrom("Ljava/lang/invoke/MethodHandles$Lookup;"));
                MemorySegment lookupJObject = (MemorySegment) GetStaticObjectField.invokeExact(lookupJClassRef, lookupJFieldID);
                MemorySegment lookupJObjectRef = (MemorySegment) NewGlobalRef.invokeExact(lookupJObject);

                MemorySegment targetJName = ARENA.allocateFrom(TrustedLookupProviderImpl.class.getName().replace('.', '/'));
                MemorySegment userLoader = (MemorySegment) JVM_LatestUserDefinedLoader.invokeExact(pEnv);
                MemorySegment targetJClass = (MemorySegment) JVM_FindClassFromCaller.invokeExact(pEnv, targetJName, (byte) 0, userLoader, MemorySegment.NULL);
                MemorySegment targetJClassRef = (MemorySegment) NewGlobalRef.invokeExact(targetJClass);
                MemorySegment targetJFieldID = (MemorySegment) GetStaticFieldID.invokeExact(targetJClassRef, ARENA.allocateFrom("lookup"), ARENA.allocateFrom("Ljava/lang/invoke/MethodHandles$Lookup;"));
                SetStaticObjectField.invokeExact(targetJClassRef, targetJFieldID, lookupJObjectRef);

                DeleteGlobalRef.invoke(lookupJClassRef);
                DeleteGlobalRef.invoke(lookupJObjectRef);
                DeleteGlobalRef.invoke(targetJClassRef);
            }
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    @Override
    public MethodHandles.Lookup implLookup() {
        return lookup;
    }
}
