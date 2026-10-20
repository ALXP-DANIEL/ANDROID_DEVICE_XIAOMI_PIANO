/* SPDX-License-Identifier: Apache-2.0 */
package com.android.tools.smali.dexlib2.writer.pool;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.MethodParameter;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;

/** Work around AOSP ClassPool's one-shot parameter-name iterable. */
public final class ReplayableDexPool extends DexPool {
    private ReplayableDexPool(Opcodes opcodes) { super(opcodes); }

    // PoolMethod is package-private, requiring this helper in the writer package.
    @Override protected DexPoolSectionProvider getSectionProvider() {
        return new DexPoolSectionProvider() {
            @Override public ClassPool getClassSection() {
                return new ClassPool(ReplayableDexPool.this) {
                    @Override public Iterable<CharSequence> getParameterNames(PoolMethod method) {
                        List<CharSequence> names = new ArrayList<>();
                        for (MethodParameter parameter : method.getParameters())
                            names.add(parameter.getName());
                        return names;
                    }
                };
            }
        };
    }

    public static void writeDex(File target, DexFile dex) throws IOException {
        ReplayableDexPool pool = new ReplayableDexPool(dex.getOpcodes());
        for (ClassDef definition : dex.getClasses()) pool.internClass(definition);
        FileDataStore data = new FileDataStore(target);
        try { pool.writeTo(data); }
        finally { data.close(); }
    }
}
